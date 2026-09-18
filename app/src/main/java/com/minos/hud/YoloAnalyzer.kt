package com.minos.hud

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

class YoloAnalyzer(
    context: Context,
    private val modelPath: String = "yolov8n.onnx",
    var detectionMode: DetectionMode = DetectionMode.ALL,
    private val onTargetsDetected: (List<DynamicYoloBox>, Long) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private val labels = mutableListOf<String>()

    private val modelInputSize = 640
    private val confidenceThreshold = 0.45f

    // Pre-allocated reusable buffers to prevent GC pauses
    private val tensorBuffer = FloatBuffer.allocate(1 * 3 * modelInputSize * modelInputSize)
    private val pixelArray = IntArray(modelInputSize * modelInputSize)
    private val letterboxBitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas = Canvas(letterboxBitmap)
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        try {
            val modelBytes = context.assets.open(modelPath).use { it.readBytes() }
            try {
                val nnapiOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    addNnapi()
                }
                ortSession = ortEnv.createSession(modelBytes, nnapiOptions)
                Log.i("YoloAnalyzer", "Successfully loaded $modelPath with NNAPI execution provider.")
            } catch (e: Exception) {
                Log.w("YoloAnalyzer", "NNAPI initialization failed for $modelPath (${e.message}). Falling back to CPU execution.", e)
                val cpuOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                }
                ortSession = ortEnv.createSession(modelBytes, cpuOptions)
                Log.i("YoloAnalyzer", "Successfully loaded $modelPath with CPU execution provider.")
            }
        } catch (e: Exception) {
            Log.e("YoloAnalyzer", "Failed to load $modelPath", e)
            e.printStackTrace()
        }

        try {
            context.assets.open("coco_labels.txt").use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.forEach { labels.add(it) }
                }
            }
        } catch (e: Exception) {
            labels.addAll(
                listOf(
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
            )
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis()
        val session = ortSession ?: run {
            imageProxy.close()
            return
        }

        val rawBitmap = imageProxy.toBitmap() ?: run {
            imageProxy.close()
            return
        }

        // Apply camera rotation
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotBmp = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            rawBitmap.recycle()
            rotBmp
        } else {
            rawBitmap
        }

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

        val candidateList = mutableListOf<DynamicYoloBox>()

        try {
            val inputTensor = OnnxTensor.createTensor(
                ortEnv,
                tensorBuffer,
                longArrayOf(1, 3, modelInputSize.toLong(), modelInputSize.toLong())
            )
            val results = session.run(Collections.singletonMap(session.inputNames.iterator().next(), inputTensor))

            if (results != null) {
                val outputTensor = results.get(0) as OnnxTensor
                val floatBuffer = outputTensor.floatBuffer
                floatBuffer.rewind()
                val shape = outputTensor.info.shape

                val dim1 = shape[1].toInt()
                val dim2 = shape[2].toInt()
                val isTransposed = dim2 == 84 || dim2 == 85
                val numElements = if (isTransposed) dim1 else dim2
                val numChannels = if (isTransposed) dim2 else dim1

                for (i in 0 until numElements) {
                    var maxScore = 0f
                    var maxClassId = -1

                    for (c in 4 until numChannels) {
                        val score = if (isTransposed) {
                            floatBuffer.get(i * numChannels + c)
                        } else {
                            floatBuffer.get(c * numElements + i)
                        }
                        if (score > maxScore) {
                            maxScore = score
                            maxClassId = c - 4
                        }
                    }

                    if (maxScore > confidenceThreshold) {
                        val cx = if (isTransposed) floatBuffer.get(i * numChannels + 0) else floatBuffer.get(0 * numElements + i)
                        val cy = if (isTransposed) floatBuffer.get(i * numChannels + 1) else floatBuffer.get(1 * numElements + i)
                        val w = if (isTransposed) floatBuffer.get(i * numChannels + 2) else floatBuffer.get(2 * numElements + i)
                        val h = if (isTransposed) floatBuffer.get(i * numChannels + 3) else floatBuffer.get(3 * numElements + i)

                        val xMin = (((cx - w / 2f) - padX) / (srcW * scale)).coerceIn(0f, 1f)
                        val yMin = (((cy - h / 2f) - padY) / (srcH * scale)).coerceIn(0f, 1f)
                        val xMax = (((cx + w / 2f) - padX) / (srcW * scale)).coerceIn(0f, 1f)
                        val yMax = (((cy + h / 2f) - padY) / (srcH * scale)).coerceIn(0f, 1f)

                        val label = labels.getOrNull(maxClassId) ?: "UNKNOWN"

                        if (!isLabelAllowedInMode(label, detectionMode)) {
                            continue
                        }

                        candidateList.add(
                            DynamicYoloBox(
                                label = label,
                                confidence = maxScore,
                                relativeAnchor = Offset((xMin + xMax) / 2f, (yMin + yMax) / 2f),
                                xMin = xMin,
                                yMin = yMin,
                                xMax = xMax,
                                yMax = yMax,
                                infoTag = "${label.uppercase()} // ${(maxScore * 100).toInt()}% CONF"
                            )
                        )
                    }
                }
                inputTensor.close()
                results.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val filteredBoxes = applyNms(candidateList)
        val totalInferenceTime = System.currentTimeMillis() - startTime
        onTargetsDetected(filteredBoxes, totalInferenceTime)

        bitmap.recycle()
        imageProxy.close()
    }

    private fun applyNms(boxes: MutableList<DynamicYoloBox>): List<DynamicYoloBox> {
        boxes.sortByDescending { it.confidence }
        val selected = mutableListOf<DynamicYoloBox>()
        val active = BooleanArray(boxes.size) { true }

        for (i in boxes.indices) {
            if (active[i]) {
                selected.add(boxes[i])
                for (j in i + 1 until boxes.size) {
                    if (active[j] && calculateIoU(boxes[i], boxes[j]) > 0.45f) {
                        active[j] = false
                    }
                }
            }
        }
        return selected
    }

    private fun calculateIoU(a: DynamicYoloBox, b: DynamicYoloBox): Float {
        val areaA = (a.xMax - a.xMin) * (a.yMax - a.yMin)
        val areaB = (b.xMax - b.xMin) * (b.yMax - b.yMin)
        val intersectionArea = max(0f, min(a.xMax, b.xMax) - max(a.xMin, b.xMin)) *
                max(0f, min(a.yMax, b.yMax) - max(a.yMin, b.yMin))
        return intersectionArea / (areaA + areaB - intersectionArea)
    }

    private fun isLabelAllowedInMode(label: String, mode: DetectionMode): Boolean {
        val l = label.lowercase()
        return when (mode) {
            DetectionMode.ALL -> true
            DetectionMode.PEOPLE -> l == "person"
            DetectionMode.VEHICLES -> l in listOf("car", "truck", "bus", "motorcycle", "bicycle")
            DetectionMode.ANIMALS -> l in ANIMAL_CLASSES
            DetectionMode.PLATES -> l in listOf("car", "truck", "bus", "motorcycle", "plate")
            DetectionMode.CUSTOM -> true // Let the user configure this later
        }
    }

    override fun close() {
        ortSession?.close()
        if (!letterboxBitmap.isRecycled) {
            letterboxBitmap.recycle()
        }
    }
}
