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
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
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
    private val getImageCapture: () -> ImageCapture?,
    private val onTargetsDetected: (magTargets: List<MagTrackTarget>, yoloTargets: List<YoloTarget>, inferenceTimeMs: Long, rotatedWidth: Int, rotatedHeight: Int) -> Unit,
    private val onFpsUpdated: (fps: Int) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var ortEnv: OrtEnvironment? = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    private var licensePlateDetector: LicensePlateDetector? = null
    private val captureManager = CaptureManager(context)
    private val highResCooldownMap = mutableMapOf<String, Long>()
    private val captureExecutor = Executors.newSingleThreadExecutor()

    // Pre-allocated reusable buffers to eliminate Garbage Collection churn
    private val modelInputSize = 640
    private val tensorBuffer: FloatBuffer = FloatBuffer.allocate(1 * 3 * modelInputSize * modelInputSize)
    private val pixelArray = IntArray(modelInputSize * modelInputSize)
    private val letterboxBitmap: Bitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas = Canvas(letterboxBitmap)
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var nextTrackId = 1
    private val activeTracks = mutableListOf<MagTrackTarget>()

    private var lastFpsUpdateTime = 0L
    private var frameCount = 0

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

    init {
        try {
            licensePlateDetector = LicensePlateDetector(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val env = ortEnv
            if (env != null) {
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    try {
                        addNnapi()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                context.assets.open(modelName).use { input ->
                    ortSession = env.createSession(input.readBytes(), options)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // Native Bitmap extraction
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
            val inputs = mapOf("images" to inputTensor)
            val outputs = session.run(inputs)

            if (outputs != null) {
                val sensitivity = getSensitivityThreshold()
                val maxDet = getMaxDetections()
                val targets = postProcess(outputs, rotatedBitmap, letterboxInfo, sensitivity, maxDet).toMutableList()

                val plateTargets = updateMagTrackTargets(targets)
                targets.addAll(plateTargets)

                val inferenceTime = System.currentTimeMillis() - startTime
                val width = rotatedBitmap.width
                val height = rotatedBitmap.height

                val magTracksCopy = activeTracks.toList()
                val yoloTargetsCopy = targets.toList()

                mainHandler.post {
                    onTargetsDetected(magTracksCopy, yoloTargetsCopy, inferenceTime, width, height)
                }
            }
            inputTensor.close()
        } catch (e: Exception) {
            e.printStackTrace()
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

            if (maxScore >= sensitivityThreshold) {
                val cx = if (isTransposed) buffer.get(i * numChannels + 0) else buffer.get(0 * numElements + i)
                val cy = if (isTransposed) buffer.get(i * numChannels + 1) else buffer.get(1 * numElements + i)
                val w = if (isTransposed) buffer.get(i * numChannels + 2) else buffer.get(2 * numElements + i)
                val h = if (isTransposed) buffer.get(i * numChannels + 3) else buffer.get(3 * numElements + i)

                val xMin = ((cx - w / 2f) - info.padX) / (srcW * info.scale)
                val yMin = ((cy - h / 2f) - info.padY) / (srcH * info.scale)
                val xMax = ((cx + w / 2f) - info.padX) / (srcW * info.scale)
                val yMax = ((cy + h / 2f) - info.padY) / (srcH * info.scale)

                val rawName = labels.getOrNull(maxClassId) ?: "unknown"

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
        return nmsSelected.take(maxDetections).map { target ->
            val left = (target.xMin * sourceBitmap.width)
            val top = (target.yMin * sourceBitmap.height)
            val w = ((target.xMax - target.xMin) * sourceBitmap.width)
            val h = ((target.yMax - target.yMin) * sourceBitmap.height)

            val padX = w * 0.4f
            val padY = h * 0.4f

            val paddedLeft = (left - padX).toInt().coerceAtLeast(0)
            val paddedTop = (top - padY).toInt().coerceAtLeast(0)
            val paddedRight = (left + w + padX).toInt().coerceAtMost(sourceBitmap.width - 1)
            val paddedBottom = (top + h + padY).toInt().coerceAtMost(sourceBitmap.height - 1)

            val paddedW = (paddedRight - paddedLeft).coerceAtLeast(1)
            val paddedH = (paddedBottom - paddedTop).coerceAtLeast(1)

            val crop = try {
                val cropped = Bitmap.createBitmap(sourceBitmap, paddedLeft, paddedTop, paddedW, paddedH)
                cropped.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                null
            }

            target.copy(
                label = getTacticalLabel(target.rawLabel),
                rawLabel = target.rawLabel,
                crop = crop
            )
        }
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
        val raw = yolo.rawLabel.lowercase()
        if (raw in listOf("car", "bus", "truck", "motorcycle")) {
            yolo.crop?.let { vehicleCrop ->
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

    private fun updateMagTrackTargets(targets: List<YoloTarget>): List<YoloTarget> {
        if (getAutoMag() && targets.isNotEmpty()) {
            val bestTarget = targets.maxByOrNull { it.confidence }
            if (bestTarget != null && bestTarget.confidence > 0.7f && getDigitalZoom() < 2f) {
                mainHandler.post { setDigitalZoom(2f) }
            }
        }

        val updatedTracks = mutableListOf<MagTrackTarget>()
        val unassignedTargets = targets.toMutableList()
        val remainingActiveTracks = activeTracks.toMutableList()
        val plateTargets = mutableListOf<YoloTarget>()

        // 1. SORT / IOU Matching Phase with Velocity Prediction
        while (remainingActiveTracks.isNotEmpty() && unassignedTargets.isNotEmpty()) {
            var bestIou = 0f
            var bestTrackIdx = -1
            var bestTargetIdx = -1

            for (tIdx in remainingActiveTracks.indices) {
                val track = remainingActiveTracks[tIdx]
                val predXmin = track.xMin + track.vx
                val predYmin = track.yMin + track.vy
                val predXmax = track.xMax + track.vx
                val predYmax = track.yMax + track.vy

                for (yIdx in unassignedTargets.indices) {
                    val yolo = unassignedTargets[yIdx]
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

            if (bestIou >= 0.15f && bestTrackIdx != -1 && bestTargetIdx != -1) {
                val matchedTrack = remainingActiveTracks.removeAt(bestTrackIdx)
                val matchedTarget = unassignedTargets.removeAt(bestTargetIdx)

                val targetCenterX = (matchedTarget.xMin + matchedTarget.xMax) / 2f
                val targetCenterY = (matchedTarget.yMin + matchedTarget.yMax) / 2f

                val newVx = 0.6f * (targetCenterX - matchedTrack.relX) + 0.4f * matchedTrack.vx
                val newVy = 0.6f * (targetCenterY - matchedTrack.relY) + 0.4f * matchedTrack.vy

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
                    vy = newVy
                )
                updatedTracks.add(updatedTrack)

                processLicensePlateIfVehicle(matchedTarget, plateTargets)
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
                val predCx = track.relX + track.vx
                val predCy = track.relY + track.vy

                for (yIdx in unassignedTargets.indices) {
                    val yolo = unassignedTargets[yIdx]
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

            if (bestDistSq < 0.16f && bestTrackIdx != -1 && bestTargetIdx != -1) {
                val matchedTrack = remainingActiveTracks.removeAt(bestTrackIdx)
                val matchedTarget = unassignedTargets.removeAt(bestTargetIdx)

                val targetCenterX = (matchedTarget.xMin + matchedTarget.xMax) / 2f
                val targetCenterY = (matchedTarget.yMin + matchedTarget.yMax) / 2f

                val newVx = 0.6f * (targetCenterX - matchedTrack.relX) + 0.4f * matchedTrack.vx
                val newVy = 0.6f * (targetCenterY - matchedTrack.relY) + 0.4f * matchedTrack.vy

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
                    vy = newVy
                )
                updatedTracks.add(updatedTrack)

                processLicensePlateIfVehicle(matchedTarget, plateTargets)
            } else {
                break
            }
        }

        // 3. New Track Creation for Unassigned Targets
        unassignedTargets.take(4 - updatedTracks.size).forEach { yolo ->
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
                vy = 0f
            )
            updatedTracks.add(newTrack)

            processLicensePlateIfVehicle(yolo, plateTargets)
        }

        activeTracks.clear()
        activeTracks.addAll(updatedTracks)

        if (getIsCaptureOn()) {
            val now = System.currentTimeMillis()
            updatedTracks.forEach { track ->
                val raw = track.rawLabel.lowercase().trim()
                val category = when (raw) {
                    "car", "bus", "truck", "motorcycle", "bicycle", "person" -> EventCategory.PEOPLE_VEHICLES
                    "dog", "cat", "bird", "bear", "horse", "sheep", "cow", "elephant", "zebra", "giraffe" -> EventCategory.ANIMALS
                    else -> null
                }

                if (category != null) {
                    val lastCaptureTime = highResCooldownMap[track.id] ?: 0L

                    // 8-second cooldown prevents shutter spam
                    if (now - lastCaptureTime > 8000L) {
                        val imageCaptureInstance = getImageCapture()
                        if (imageCaptureInstance != null) {
                            highResCooldownMap[track.id] = now

                            val target = YoloTarget(
                                id = track.id,
                                label = track.trackLabel,
                                rawLabel = track.rawLabel,
                                confidence = 0.9f,
                                xMin = track.xMin,
                                yMin = track.yMin,
                                xMax = track.xMax,
                                yMax = track.yMax
                            )

                            imageCaptureInstance.takePicture(
                                captureExecutor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        processHighResCrop(image, target, category)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        exception.printStackTrace()
                                    }
                                }
                            )
                        } else {
                            // Fallback to low-res frame capture if ImageCapture is unavailable
                            track.crop?.let { bitmap ->
                                val score = BestFrameSelector.calculateScore(bitmap, 0f, 0f, 1f, 1f)
                                captureManager.processDetection(track.id, raw.uppercase(), category, bitmap, score)
                            }
                        }
                    }
                }
            }
        }
        return plateTargets
    }

    private fun processHighResCrop(image: ImageProxy, yoloTarget: YoloTarget, category: EventCategory) {
        try {
            val rotation = image.imageInfo.rotationDegrees
            val rawBitmap = image.toBitmap()

            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotatedHighRes = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)

            val cropLeft = (yoloTarget.xMin * rotatedHighRes.width).toInt().coerceAtLeast(0)
            val cropTop = (yoloTarget.yMin * rotatedHighRes.height).toInt().coerceAtLeast(0)
            val cropWidth = ((yoloTarget.xMax - yoloTarget.xMin) * rotatedHighRes.width)
                .toInt().coerceAtMost(rotatedHighRes.width - cropLeft)
            val cropHeight = ((yoloTarget.yMax - yoloTarget.yMin) * rotatedHighRes.height)
                .toInt().coerceAtMost(rotatedHighRes.height - cropTop)

            if (cropWidth > 0 && cropHeight > 0) {
                val highResCrop = Bitmap.createBitmap(rotatedHighRes, cropLeft, cropTop, cropWidth, cropHeight)

                EventRepository.saveEvent(
                    context = context,
                    label = "HI-RES // ${yoloTarget.rawLabel.uppercase()}",
                    category = category,
                    bitmap = highResCrop
                )

                val raw = yoloTarget.rawLabel.lowercase().trim()
                if (raw in listOf("car", "bus", "truck", "motorcycle")) {
                    licensePlateDetector?.detectAndCropPlate(highResCrop)?.let { plateResult ->
                        EventRepository.saveEvent(
                            context = context,
                            label = "PLATE // ${raw.uppercase()}",
                            category = EventCategory.PLATES,
                            bitmap = plateResult.plateCrop
                        )
                    }
                }
            }

            if (rawBitmap != rotatedHighRes) rawBitmap.recycle()
            rotatedHighRes.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
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
