package com.minos.hud

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class OnnxImageAnalyzer(
    private val context: Context,
    modelName: String = "yolov8n.onnx",
    private val getIsScanning: () -> Boolean,
    private val getSensitivityThreshold: () -> Float,
    private val getMaxDetections: () -> Int,
    private val getAutoMag: () -> Boolean,
    private val getDigitalZoom: () -> Float,
    private val setDigitalZoom: (Float) -> Unit,
    private val getIsCaptureOn: () -> Boolean,
    private val getDetectionMode: () -> DetectionMode = { DetectionMode.ALL },
    private val getProfile: () -> TrackingProfile = { TrackingProfile.OUTDOOR },
    private val getQualityPreset: () -> QualitySpeedPreset = { QualitySpeedPreset.BALANCED },
    private val getLockedTrackId: () -> String? = { null },
    private val onTriggerHighResCapture: ((xMin: Float, yMin: Float, xMax: Float, yMax: Float, padding: Float, onCaptured: (Bitmap?) -> Unit) -> Unit)? = null,
    private val onTargetsDetected: (magTargets: List<MagTrackTarget>, yoloTargets: List<YoloTarget>, inferenceTimeMs: Long, rotatedWidth: Int, rotatedHeight: Int) -> Unit,
    private val onFpsUpdated: (fps: Int) -> Unit,
    private val onModelLoadError: ((String) -> Unit)? = null
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var ortEnv: OrtEnvironment? = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    private var licensePlateDetector: LicensePlateDetector? = null
    private val captureManager = CaptureManager(context, onTriggerHighResCapture)
    private val captureExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val MODEL_INPUT_SIZE = 640
    }

    // Pre-allocated reusable buffers to eliminate Garbage Collection churn
    private var modelInputSize = MODEL_INPUT_SIZE
    private var tensorBuffer: FloatBuffer = FloatBuffer.allocate(1 * 3 * modelInputSize * modelInputSize)
    private var pixelArray = IntArray(modelInputSize * modelInputSize)
    private var letterboxBitmap: Bitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
    private var letterboxCanvas = Canvas(letterboxBitmap)
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var nextTrackId = 1
    private val activeTracks = mutableListOf<MagTrackTarget>()

    private var lastFpsUpdateTime = 0L
    private var frameCount = 0

    private var lastTrackUpdateNs = 0L
    private var lastPlateDetectionNs = 0L
    private val plateIntervalNs = 400_000_000L // 400 ms time throttle

    private var lastTelemetryTime = 0L
    private var plateScansCount = 0
    private var expiredTracksCount = 0

    private val trackIouThreshold = 0.25f
    private val fallbackDistanceSquared = 0.04f

    private val labels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    private val defaultClasses = labels.map { it.lowercase().trim() }.toSet()

    private var modelLoadError: String? = null

    init {
        try {
            licensePlateDetector = LicensePlateDetector(context)
        } catch (e: Exception) {
            Log.e("OnnxImageAnalyzer", "Failed to init LicensePlateDetector", e)
        }

        try {
            val env = ortEnv
            if (env != null) {
                val modelBytes = context.assets.open(modelName).use { it.readBytes() }
                
                var session: OrtSession? = null
                try {
                    val nnapiOptions = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(4)
                        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                        addNnapi()
                    }
                    session = env.createSession(modelBytes, nnapiOptions)
                    Log.i("OnnxImageAnalyzer", "Successfully loaded $modelName with NNAPI execution provider.")
                } catch (e: Exception) {
                    Log.w("OnnxImageAnalyzer", "NNAPI initialization failed for $modelName (${e.message}). Falling back to CPU execution.", e)
                    val cpuOptions = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(4)
                        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    }
                    session = env.createSession(modelBytes, cpuOptions)
                    Log.i("OnnxImageAnalyzer", "Successfully loaded $modelName with CPU execution provider.")
                }
                ortSession = session

                modelInputSize = 640
                reallocateBuffers(modelInputSize)
            }
        } catch (e: Exception) {
            modelLoadError = "Failed to load $modelName: ${e.message}"
            Log.e("OnnxImageAnalyzer", modelLoadError!!, e)
            onModelLoadError?.invoke(modelLoadError!!)
        }
    }

    private fun reallocateBuffers(size: Int) {
        synchronized(this) {
            modelInputSize = size
            tensorBuffer = FloatBuffer.allocate(1 * 3 * size * size)
            pixelArray = IntArray(size * size)
            if (!letterboxBitmap.isRecycled) letterboxBitmap.recycle()
            letterboxBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            letterboxCanvas = Canvas(letterboxBitmap)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis()

        mainHandler.post { updateFps() }

        if (!getIsScanning()) {
            imageProxy.close()
            return
        }

        val qualityPreset = getQualityPreset()
        if (qualityPreset.modelInputSize != modelInputSize) {
            reallocateBuffers(qualityPreset.modelInputSize)
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        val rawBitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            null
        }

        imageProxy.close()

        if (rawBitmap == null) return

        val rotatedBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotBmp = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            rawBitmap.recycle()
            rotBmp
        } else {
            rawBitmap
        }
        
        VideoBuffer.addFrame(rotatedBitmap)

        val letterboxInfo = preprocessLetterbox(rotatedBitmap)
        val env = ortEnv ?: run {
            rotatedBitmap.recycle()
            return
        }

        try {
            val session = ortSession ?: run {
                rotatedBitmap.recycle()
                return
            }

            val inputTensor = OnnxTensor.createTensor(env, tensorBuffer, longArrayOf(1, 3, modelInputSize.toLong(), modelInputSize.toLong()))
            val inputName = session.inputNames.iterator().next()
            val inputs = mapOf(inputName to inputTensor)
            val outputs = session.run(inputs)

            if (outputs != null) {
                val sensitivity = getSensitivityThreshold()
                val maxDet = getMaxDetections()
                val targets = postProcess(outputs, rotatedBitmap, letterboxInfo, sensitivity, maxDet).toMutableList()

                val plateTargets = updateMagTrackTargets(targets, rotatedBitmap, startTime)
                targets.addAll(plateTargets)

                val inferenceTime = System.currentTimeMillis() - startTime
                val width = rotatedBitmap.width
                val height = rotatedBitmap.height

                val magTracksCopy = synchronized(activeTracks) { activeTracks.toList() }
                val yoloTargetsCopy = targets.toList()

                mainHandler.post {
                    onTargetsDetected(magTracksCopy, yoloTargetsCopy, inferenceTime, width, height)
                }
            }
            inputTensor.close()
        } catch (e: Exception) {
            Log.e("OnnxImageAnalyzer", "Inference failed", e)
            mainHandler.post {
                onModelLoadError?.invoke("Inference failed: ${e.message}")
            }
        } finally {
            rotatedBitmap.recycle()
        }
    }

    private data class LetterboxInfo(val scale: Float, val padX: Float, val padY: Float)

    private fun preprocessLetterbox(bitmap: Bitmap): LetterboxInfo {
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        val scale = min(modelInputSize / srcW, modelInputSize / srcH)
        val dstW = srcW * scale
        val dstH = srcH * scale
        val padX = (modelInputSize - dstW) / 2f
        val padY = (modelInputSize - dstH) / 2f

        letterboxCanvas.drawColor(Color.BLACK)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(padX, padY)
        }
        letterboxCanvas.drawBitmap(bitmap, matrix, letterboxPaint)

        tensorBuffer.rewind()
        letterboxBitmap.getPixels(pixelArray, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize)

        val totalPixels = modelInputSize * modelInputSize
        for (i in 0 until totalPixels) {
            val pixel = pixelArray[i]
            tensorBuffer.put(i, ((pixel shr 16) and 0xFF) / 255f)
            tensorBuffer.put(i + totalPixels, ((pixel shr 8) and 0xFF) / 255f)
            tensorBuffer.put(i + 2 * totalPixels, (pixel and 0xFF) / 255f)
        }
        tensorBuffer.rewind()

        return LetterboxInfo(scale, padX, padY)
    }

    private fun postProcess(
        outputs: OrtSession.Result,
        sourceBitmap: Bitmap,
        info: LetterboxInfo,
        sensitivityThreshold: Float,
        maxDetections: Int
    ): List<YoloTarget> {
        val outputTensor = outputs.get(0) as OnnxTensor
        val buffer = outputTensor.floatBuffer
        buffer.rewind()
        val shape = outputTensor.info.shape

        val dim1 = shape[1].toInt()
        val dim2 = shape[2].toInt()
        val isTransposed = dim2 == 84 || dim2 == 85
        val numElements = if (isTransposed) dim1 else dim2
        val numChannels = if (isTransposed) dim2 else dim1

        val candidateTargets = mutableListOf<YoloTarget>()
        val srcW = sourceBitmap.width.toFloat()
        val srcH = sourceBitmap.height.toFloat()
        val mode = getDetectionMode()
        val profile = getProfile()
        val effectiveSensitivity = maxOf(profile.confidenceThreshold, sensitivityThreshold)

        for (i in 0 until numElements) {
            var maxScore = 0f
            var maxClassId = -1

            for (c in 4 until numChannels) {
                val score = if (isTransposed) {
                    buffer.get(i * numChannels + c)
                } else {
                    buffer.get(c * numElements + i)
                }
                if (score > maxScore) {
                    maxScore = score
                    maxClassId = c - 4
                }
            }

            val rawName = labels.getOrNull(maxClassId) ?: "unknown"
            val rawLower = rawName.lowercase().trim()

            // Class Whitelist Filter based on Tracking Profile or DetectionMode
            val isAllowed = if (profile.allowedClasses != null) {
                rawLower in profile.allowedClasses
            } else {
                when (mode) {
                    DetectionMode.ALL -> true
                    DetectionMode.PEOPLE -> rawLower == "person"
                    DetectionMode.VEHICLES -> rawLower in listOf("car", "truck", "bus", "motorcycle", "bicycle")
                    DetectionMode.ANIMALS -> rawLower in listOf("bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe")
                    DetectionMode.PLATES -> rawLower in listOf("car", "truck", "bus", "motorcycle", "plate")
                    DetectionMode.CUSTOM -> true
                }
            }
            if (!isAllowed) {
                continue
            }

            val classThreshold = maxOf(0.15f, effectiveSensitivity)

            if (maxScore >= classThreshold) {
                val cx = if (isTransposed) buffer.get(i * numChannels + 0) else buffer.get(0 * numElements + i)
                val cy = if (isTransposed) buffer.get(i * numChannels + 1) else buffer.get(1 * numElements + i)
                val w = if (isTransposed) buffer.get(i * numChannels + 2) else buffer.get(2 * numElements + i)
                val h = if (isTransposed) buffer.get(i * numChannels + 3) else buffer.get(3 * numElements + i)

                val xMin = ((cx - w / 2f) - info.padX) / (srcW * info.scale)
                val yMin = ((cy - h / 2f) - info.padY) / (srcH * info.scale)
                val xMax = ((cx + w / 2f) - info.padX) / (srcW * info.scale)
                val yMax = ((cy + h / 2f) - info.padY) / (srcH * info.scale)

                candidateTargets.add(
                    YoloTarget(
                        id = "TGT-${candidateTargets.size}",
                        label = rawName,
                        rawLabel = rawName,
                        confidence = maxScore,
                        xMin = xMin.coerceIn(0f, 1f),
                        yMin = yMin.coerceIn(0f, 1f),
                        xMax = xMax.coerceIn(0f, 1f),
                        yMax = yMax.coerceIn(0f, 1f)
                    )
                )
            }
        }

        val nmsSelected = nms(candidateTargets)
        val finalTargets = nmsSelected.take(maxDetections).map { target ->
            val left = (target.xMin * sourceBitmap.width)
            val top = (target.yMin * sourceBitmap.height)
            val w = ((target.xMax - target.xMin) * sourceBitmap.width)
            val h = ((target.yMax - target.yMin) * sourceBitmap.height)

            val padding = BestFrameSelector.getPaddingForLabel(target.rawLabel)

            val padX = w * padding
            val padY = h * padding

            val paddedLeft = (left - padX).toInt().coerceAtLeast(0)
            val paddedTop = (top - padY).toInt().coerceAtLeast(0)
            val paddedRight = (left + w + padX).toInt().coerceAtMost(sourceBitmap.width - 1)
            val paddedBottom = (top + h + padY).toInt().coerceAtMost(sourceBitmap.height - 1)

            val paddedW = (paddedRight - paddedLeft).coerceAtLeast(1)
            val paddedH = (paddedBottom - paddedTop).coerceAtLeast(1)

            // Conditional crop allocation to eliminate GC memory churn
            val isVehicle = target.rawLabel.lowercase().trim() in listOf("car", "bus", "truck", "motorcycle")
            val needsCrop = getIsCaptureOn() || getAutoMag() || isVehicle

            val crop = if (needsCrop) {
                try {
                    val cropped = Bitmap.createBitmap(sourceBitmap, paddedLeft, paddedTop, paddedW, paddedH)
                    val result = cropped.copy(Bitmap.Config.ARGB_8888, false)
                    cropped.recycle()
                    result
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

            target.copy(
                label = getTacticalLabel(target.rawLabel),
                rawLabel = target.rawLabel,
                crop = crop
            )
        }

        return finalTargets
    }

    private fun calculateIou(
        axMin: Float, ayMin: Float, axMax: Float, ayMax: Float,
        bxMin: Float, byMin: Float, bxMax: Float, byMax: Float
    ): Float {
        val interXmin = maxOf(axMin, bxMin)
        val interYmin = maxOf(ayMin, byMin)
        val interXmax = minOf(axMax, bxMax)
        val interYmax = minOf(ayMax, byMax)

        val interWidth = maxOf(0f, interXmax - interXmin)
        val interHeight = maxOf(0f, interYmax - interYmin)
        val interArea = interWidth * interHeight

        val areaA = maxOf(0f, axMax - axMin) * maxOf(0f, ayMax - ayMin)
        val areaB = maxOf(0f, bxMax - bxMin) * maxOf(0f, byMax - byMin)

        val unionArea = areaA + areaB - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    private fun processLicensePlateIfVehicle(yolo: YoloTarget, plateTargets: MutableList<YoloTarget>) {
        val raw = yolo.rawLabel.lowercase().trim()
        if (raw in listOf("car", "bus", "truck", "motorcycle")) {
            yolo.crop?.let { vehicleCrop ->
                if (vehicleCrop.width >= 320 && vehicleCrop.height >= 180) {
                    licensePlateDetector?.detectAndCropPlate(vehicleCrop)?.let { plateResult ->
                        val p = plateResult.plateTarget
                        val vW = yolo.xMax - yolo.xMin
                        val vH = yolo.yMax - yolo.yMin
                        val globalPlate = p.copy(
                            xMin = yolo.xMin + p.xMin * vW,
                            yMin = yolo.yMin + p.yMin * vH,
                            xMax = yolo.xMin + p.xMax * vW,
                            yMax = yolo.yMin + p.yMax * vH
                        )
                        plateTargets.add(globalPlate)
                    }
                }
            }
        }
    }

    private fun updateMagTrackTargets(targets: List<YoloTarget>, fullFrame: Bitmap, startTime: Long): List<YoloTarget> {
        val nowNs = System.nanoTime()
        val dt = if (lastTrackUpdateNs == 0L) {
            0.033f
        } else {
            ((nowNs - lastTrackUpdateNs) / 1_000_000_000f).coerceIn(0.01f, 0.20f)
        }
        lastTrackUpdateNs = nowNs

        val plateTargets = mutableListOf<YoloTarget>()

        val profile = getProfile()
        val qualityPreset = getQualityPreset()
        val lockedId = getLockedTrackId()

        val effectivePlateIntervalMs = minOf(profile.plateScanIntervalMs, qualityPreset.plateScanIntervalMs)
        val effectivePlateIntervalNs = effectivePlateIntervalMs * 1_000_000L

        // Time-Based Deterministic Plate Scanner (Profile & Tap-to-Lock filtering)
        if (profile.isPlateDetectorEnabled && (nowNs - lastPlateDetectionNs >= effectivePlateIntervalNs) && (getIsCaptureOn() || getAutoMag())) {
            val vehicleCandidates = targets.filter {
                val raw = it.rawLabel.lowercase().trim()
                val isVeh = raw in listOf("car", "bus", "truck", "motorcycle")
                val isLockedMatch = (lockedId == null) || (it.id == lockedId) || activeTracks.any { trk -> trk.id == lockedId && trk.rawLabel.equals(raw, ignoreCase = true) }
                isVeh && isLockedMatch && it.crop != null && it.crop.width >= 200 && it.crop.height >= 120
            }

            val topVehicle = vehicleCandidates.maxWithOrNull(
                compareBy<YoloTarget> { (it.xMax - it.xMin) * (it.yMax - it.yMin) }.thenBy { it.confidence }
            )

            if (topVehicle != null) {
                lastPlateDetectionNs = nowNs
                plateScansCount++
                processLicensePlateIfVehicle(topVehicle, plateTargets)
            }
        }

        if (getAutoMag() && targets.isNotEmpty()) {
            val bestTarget = targets.maxByOrNull { it.confidence }
            if (bestTarget != null && bestTarget.confidence > 0.7f && getDigitalZoom() < 2f) {
                mainHandler.post { setDigitalZoom(2f) }
            }
        }

        val updatedTracks = mutableListOf<MagTrackTarget>()
        val unassignedTargets = targets.toMutableList()
        val remainingActiveTracks = synchronized(activeTracks) { activeTracks.toMutableList() }

        // 1. IOU Matching Phase with Clamped Velocity Estimation
        while (remainingActiveTracks.isNotEmpty() && unassignedTargets.isNotEmpty()) {
            var bestIou = 0f
            var bestTrackIdx = -1
            var bestTargetIdx = -1

            for (tIdx in remainingActiveTracks.indices) {
                val track = remainingActiveTracks[tIdx]
                val predXmin = track.xMin + track.vx * dt
                val predYmin = track.yMin + track.vy * dt
                val predXmax = track.xMax + track.vx * dt
                val predYmax = track.yMax + track.vy * dt

                for (yIdx in unassignedTargets.indices) {
                    val yolo = unassignedTargets[yIdx]
                    val sameClass = track.rawLabel.isEmpty() || track.rawLabel.equals(yolo.rawLabel, ignoreCase = true)
                    if (!sameClass) continue

                    val iou = calculateIou(
                        predXmin, predYmin, predXmax, predYmax,
                        yolo.xMin, yolo.yMin, yolo.xMax, yolo.yMax
                    )
                    if (iou > bestIou) {
                        bestIou = iou
                        bestTrackIdx = tIdx
                        bestTargetIdx = yIdx
                    }
                }
            }

            if (bestIou >= trackIouThreshold && bestTrackIdx != -1 && bestTargetIdx != -1) {
                val matchedTrack = remainingActiveTracks.removeAt(bestTrackIdx)
                val matchedTarget = unassignedTargets.removeAt(bestTargetIdx)

                val targetCenterX = (matchedTarget.xMin + matchedTarget.xMax) / 2f
                val targetCenterY = (matchedTarget.yMin + matchedTarget.yMax) / 2f

                val measuredVx = ((targetCenterX - matchedTrack.relX) / dt).coerceIn(-1.5f, 1.5f)
                val measuredVy = ((targetCenterY - matchedTrack.relY) / dt).coerceIn(-1.5f, 1.5f)

                val newVx = 0.80f * measuredVx + 0.20f * matchedTrack.vx
                val newVy = 0.80f * measuredVy + 0.20f * matchedTrack.vy

                val updatedTrack = matchedTrack.copy(
                    rawLabel = matchedTarget.rawLabel,
                    relX = targetCenterX,
                    relY = targetCenterY,
                    coordinateLabel = "X:${(matchedTarget.xMin * 100).toInt()} Y:${(matchedTarget.yMin * 100).toInt()} Z:${(matchedTarget.confidence * 100).toInt()}%",
                    crop = matchedTarget.crop ?: matchedTrack.crop,
                    xMin = matchedTarget.xMin,
                    yMin = matchedTarget.yMin,
                    xMax = matchedTarget.xMax,
                    yMax = matchedTarget.yMax,
                    vx = newVx,
                    vy = newVy,
                    lastUpdateNs = nowNs,
                    missedCount = 0
                )
                updatedTracks.add(updatedTrack)
            } else {
                break
            }
        }

        // 2. Fallback Center Distance Matching Phase
        while (remainingActiveTracks.isNotEmpty() && unassignedTargets.isNotEmpty()) {
            var bestDistSq = Float.MAX_VALUE
            var bestTrackIdx = -1
            var bestTargetIdx = -1

            for (tIdx in remainingActiveTracks.indices) {
                val track = remainingActiveTracks[tIdx]
                val predCx = track.relX + track.vx * dt
                val predCy = track.relY + track.vy * dt

                for (yIdx in unassignedTargets.indices) {
                    val yolo = unassignedTargets[yIdx]
                    val sameClass = track.rawLabel.isEmpty() || track.rawLabel.equals(yolo.rawLabel, ignoreCase = true)
                    if (!sameClass) continue

                    val targetCenterX = (yolo.xMin + yolo.xMax) / 2f
                    val targetCenterY = (yolo.yMin + yolo.yMax) / 2f
                    val dx = predCx - targetCenterX
                    val dy = predCy - targetCenterY
                    val distSq = dx * dx + dy * dy

                    if (distSq < bestDistSq) {
                        bestDistSq = distSq
                        bestTrackIdx = tIdx
                        bestTargetIdx = yIdx
                    }
                }
            }

            if (bestDistSq < fallbackDistanceSquared && bestTrackIdx != -1 && bestTargetIdx != -1) {
                val matchedTrack = remainingActiveTracks.removeAt(bestTrackIdx)
                val matchedTarget = unassignedTargets.removeAt(bestTargetIdx)

                val targetCenterX = (matchedTarget.xMin + matchedTarget.xMax) / 2f
                val targetCenterY = (matchedTarget.yMin + matchedTarget.yMax) / 2f

                val measuredVx = ((targetCenterX - matchedTrack.relX) / dt).coerceIn(-1.5f, 1.5f)
                val measuredVy = ((targetCenterY - matchedTrack.relY) / dt).coerceIn(-1.5f, 1.5f)

                val newVx = 0.80f * measuredVx + 0.20f * matchedTrack.vx
                val newVy = 0.80f * measuredVy + 0.20f * matchedTrack.vy

                val updatedTrack = matchedTrack.copy(
                    rawLabel = matchedTarget.rawLabel,
                    relX = targetCenterX,
                    relY = targetCenterY,
                    coordinateLabel = "X:${(matchedTarget.xMin * 100).toInt()} Y:${(matchedTarget.yMin * 100).toInt()} Z:${(matchedTarget.confidence * 100).toInt()}%",
                    crop = matchedTarget.crop ?: matchedTrack.crop,
                    xMin = matchedTarget.xMin,
                    yMin = matchedTarget.yMin,
                    xMax = matchedTarget.xMax,
                    yMax = matchedTarget.yMax,
                    vx = newVx,
                    vy = newVy,
                    lastUpdateNs = nowNs,
                    missedCount = 0
                )
                updatedTracks.add(updatedTrack)
            } else {
                break
            }
        }

        // 3. Expire unmatched tracks after 2 missed frames
        for (unmatchedTrack in remainingActiveTracks) {
            val missed = unmatchedTrack.missedCount + 1
            if (missed <= 2) {
                updatedTracks.add(unmatchedTrack.copy(missedCount = missed, vx = unmatchedTrack.vx * 0.85f, vy = unmatchedTrack.vy * 0.85f))
            } else {
                expiredTracksCount++
            }
        }

        // 4. Create new tracks for unassigned targets
        unassignedTargets.take(8 - updatedTracks.size).forEach { yolo ->
            val cx = (yolo.xMin + yolo.xMax) / 2f
            val cy = (yolo.yMin + yolo.yMax) / 2f

            val newTrack = MagTrackTarget(
                id = "TRACK-${nextTrackId++ % 1000}",
                trackLabel = "AUTO MAG-TRACK // ${yolo.rawLabel.uppercase()}",
                rawLabel = yolo.rawLabel,
                coordinateLabel = "X:${(yolo.xMin * 100).toInt()} Y:${(yolo.yMin * 100).toInt()} Z:${(yolo.confidence * 100).toInt()}%",
                relX = cx,
                relY = cy,
                crop = yolo.crop,
                xMin = yolo.xMin,
                yMin = yolo.yMin,
                xMax = yolo.xMax,
                yMax = yolo.yMax,
                vx = 0f,
                vy = 0f,
                lastUpdateNs = nowNs,
                missedCount = 0
            )
            updatedTracks.add(newTrack)
        }

        synchronized(activeTracks) {
            activeTracks.clear()
            activeTracks.addAll(updatedTracks)
        }

        if (getIsCaptureOn()) {
            updatedTracks.forEach { track ->
                val raw = track.rawLabel.lowercase().trim()
                val category = when (raw) {
                    "car", "bus", "truck", "motorcycle", "bicycle", "person" -> EventCategory.PEOPLE_VEHICLES
                    "dog", "cat", "bird", "bear", "horse", "sheep", "cow", "elephant", "zebra", "giraffe" -> EventCategory.ANIMALS
                    else -> null
                }

                if (category != null) {
                    track.crop?.let { bitmap ->
                        val conf = track.coordinateLabel.substringAfter("Z:").substringBefore("%").toFloatOrNull()?.div(100f) ?: 0.5f
                        captureExecutor.execute {
                            if (!bitmap.isRecycled) {
                                val score = BestFrameSelector.calculateScore(bitmap, confidence = conf)
                                captureManager.processDetection(
                                    id = track.id,
                                    label = raw.uppercase(),
                                    category = category,
                                    bitmap = bitmap,
                                    score = score,
                                    fullFrame = fullFrame,
                                    xMin = track.xMin,
                                    yMin = track.yMin,
                                    xMax = track.xMax,
                                    yMax = track.yMax,
                                    rawLabel = track.rawLabel,
                                    lockedTrackId = lockedId,
                                    minStableFrames = qualityPreset.minStableFrames,
                                    minSharpnessThreshold = qualityPreset.minSharpnessScore,
                                    cooldownMs = profile.captureCooldownMs,
                                    minCropWidth = qualityPreset.minCropWidth,
                                    minCropHeight = qualityPreset.minCropHeight,
                                    minPlateCropWidth = qualityPreset.minPlateCropWidth,
                                    minPlateCropHeight = qualityPreset.minPlateCropHeight
                                )
                            }
                        }
                    }
                }
            }
            
            plateTargets.forEach { plateTarget ->
                plateTarget.crop?.let { bitmap ->
                    captureExecutor.execute {
                        if (!bitmap.isRecycled) {
                            val score = BestFrameSelector.calculateScore(bitmap, confidence = plateTarget.confidence)
                            val plateId = "PLATE-${(plateTarget.xMin * 100).toInt()}-${(plateTarget.yMin * 100).toInt()}"
                            captureManager.processDetection(
                                id = plateId,
                                label = "LICENSE PLATE",
                                category = EventCategory.PLATES,
                                bitmap = bitmap,
                                score = score,
                                fullFrame = fullFrame,
                                xMin = plateTarget.xMin,
                                yMin = plateTarget.yMin,
                                xMax = plateTarget.xMax,
                                yMax = plateTarget.yMax,
                                rawLabel = "plate",
                                lockedTrackId = lockedId,
                                minStableFrames = qualityPreset.minStableFrames,
                                minSharpnessThreshold = qualityPreset.minSharpnessScore,
                                cooldownMs = profile.captureCooldownMs,
                                minCropWidth = qualityPreset.minCropWidth,
                                minCropHeight = qualityPreset.minCropHeight,
                                minPlateCropWidth = qualityPreset.minPlateCropWidth,
                                minPlateCropHeight = qualityPreset.minPlateCropHeight
                            )
                        }
                    }
                }
            }
        }

        // 1-second Telemetry Logging
        val totalAnalyzerTime = System.currentTimeMillis() - startTime
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastTelemetryTime >= 1000) {
            Log.i("OnnxImageAnalyzer", "Telemetry: profile=${profile.name}, preset=${qualityPreset.name}, lockedId=${lockedId ?: "none"}, input=${modelInputSize}x${modelInputSize}, inference=${System.currentTimeMillis() - startTime}ms, analyzer=${totalAnalyzerTime}ms, FPS=${frameCount}, activeTracks=${updatedTracks.size}, plateScans=${plateScansCount}, expiredTracks=${expiredTracksCount}")
            plateScansCount = 0
            expiredTracksCount = 0
            lastTelemetryTime = nowMs
        }

        return plateTargets
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsUpdateTime >= 1000) {
            val fps = frameCount
            frameCount = 0
            lastFpsUpdateTime = now
            onFpsUpdated(fps)
        }
    }

    private fun getTacticalLabel(baseLabel: String): String {
        val label = baseLabel.uppercase()
        return when {
            label == "PERSON" -> "BIO-SIGN // PERSON"
            label in listOf("BICYCLE", "CAR", "MOTORCYCLE", "AIRPLANE", "BUS", "TRAIN", "TRUCK", "BOAT") ->
                "VEHICLE // $label"
            label in listOf("DOG", "CAT", "BIRD", "HORSE", "SHEEP", "COW", "ELEPHANT", "BEAR", "ZEBRA", "GIRAFFE") ->
                "ANIMAL // $label"
            else -> "OBJECT // $label"
        }
    }

    private fun nms(boxes: MutableList<YoloTarget>): List<YoloTarget> {
        boxes.sortByDescending { it.confidence }
        val selected = mutableListOf<YoloTarget>()
        val active = BooleanArray(boxes.size) { true }
        for (i in boxes.indices) {
            if (active[i]) {
                selected.add(boxes[i])
                for (j in i + 1 until boxes.size) {
                    if (active[j] && iou(boxes[i], boxes[j]) > 0.45f) active[j] = false
                }
            }
        }
        return selected
    }

    private fun iou(a: YoloTarget, b: YoloTarget): Float {
        val areaA = (a.xMax - a.xMin) * (a.yMax - a.yMin)
        val areaB = (b.xMax - b.xMin) * (b.yMax - b.yMin)
        val intersectionArea = max(0f, min(a.xMax, b.xMax) - max(a.xMin, b.xMin)) *
                max(0f, min(a.yMax, b.yMax) - max(a.yMin, b.yMin))
        return intersectionArea / (areaA + areaB - intersectionArea)
    }

    override fun close() {
        ortSession?.close()
        licensePlateDetector?.close()
        captureExecutor.shutdown()
        if (!letterboxBitmap.isRecycled) {
            letterboxBitmap.recycle()
        }
    }
}
