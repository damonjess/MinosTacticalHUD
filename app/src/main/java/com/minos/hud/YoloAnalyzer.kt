package com.minos.hud

import android.content.Context
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class YoloAnalyzer(
    context: Context,
    private val modelPath: String = "yolov8n.tflite",
    private val onTargetsDetected: (List<DynamicYoloBox>, Long) -> Unit
) : ImageAnalysis.Analyzer {

    private val tflite: Interpreter
    private val labels = mutableListOf<String>()

    private val modelInputSize = 640
    private val confidenceThreshold = 0.45f

    // Reusable byte buffer pre-allocated once to prevent GC pauses
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 84 * 8400 * 4)
        .order(ByteOrder.nativeOrder())

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        tflite = Interpreter(FileUtil.loadMappedFile(context, modelPath), options)

        try {
            labels.addAll(FileUtil.loadLabels(context, "coco_labels.txt"))
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

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis()

        val rawBitmap = imageProxy.toBitmap() ?: run {
            imageProxy.close()
            return
        }

        // Apply camera rotation
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotBmp = android.graphics.Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            rawBitmap.recycle()
            rotBmp
        } else {
            rawBitmap
        }

        // Prepare Tensor Image
        var tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(modelInputSize, modelInputSize, ResizeOp.ResizeMethod.BILINEAR))
            .build()
        tensorImage = imageProcessor.process(tensorImage)

        // Run Inference into pre-allocated buffer
        outputBuffer.rewind()
        tflite.run(tensorImage.buffer, outputBuffer)
        outputBuffer.rewind()

        val candidateList = mutableListOf<DynamicYoloBox>()
        val floatBuffer = outputBuffer.asFloatBuffer()

        for (i in 0 until 8400) {
            var maxScore = 0f
            var maxClassId = -1

            for (c in 0 until min(80, labels.size)) {
                val score = floatBuffer.get((4 + c) * 8400 + i)
                if (score > maxScore) {
                    maxScore = score
                    maxClassId = c
                }
            }

            if (maxScore > confidenceThreshold) {
                val cx = floatBuffer.get(0 * 8400 + i)
                val cy = floatBuffer.get(1 * 8400 + i)
                val w = floatBuffer.get(2 * 8400 + i)
                val h = floatBuffer.get(3 * 8400 + i)

                val xMin = ((cx - w / 2f) / modelInputSize).coerceIn(0f, 1f)
                val yMin = ((cy - h / 2f) / modelInputSize).coerceIn(0f, 1f)
                val xMax = ((cx + w / 2f) / modelInputSize).coerceIn(0f, 1f)
                val yMax = ((cy + h / 2f) / modelInputSize).coerceIn(0f, 1f)

                val label = labels.getOrNull(maxClassId) ?: "UNKNOWN"

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
}
