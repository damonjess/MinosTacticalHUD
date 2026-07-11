package com.minos.hud

import android.content.Context
import android.graphics.Bitmap
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

class YoloAnalyzer(
    context: Context,
    private val modelPath: String = "yolov8n.tflite", // Place model file in assets/
    private val onTargetsDetected: (List<DynamicYoloBox>, Long) -> Unit
) : ImageAnalysis.Analyzer {

    private val tflite: Interpreter
    private val labels = mutableListOf<String>()
    
    // YOLO model parameters
    private val modelInputSize = 640 
    private val confidenceThreshold = 0.45f

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        tflite = Interpreter(FileUtil.loadMappedFile(context, modelPath), options)
        
        try {
            labels.addAll(FileUtil.loadLabels(context, "coco_labels.txt"))
        } catch (e: Exception) {
            labels.addAll(YoloLabels.SYSTEM_CATALOG)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis()
        
        // 1. Convert ImageProxy to Bitmap
        val bitmap = imageProxy.toBitmap() ?: run {
            imageProxy.close()
            return
        }

        // 2. Prepare TensorFlow Tensor Image
        var tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(modelInputSize, modelInputSize, ResizeOp.ResizeMethod.BILINEAR))
            .build()
        tensorImage = imageProcessor.process(tensorImage)

        // 3. Setup output buffers
        // Shape: [1, 84, 8400] for YOLOv8
        val outputBuffer = ByteBuffer.allocateDirect(1 * 84 * 8400 * 4).order(ByteOrder.nativeOrder())

        // 4. Run Inference
        tflite.run(tensorImage.buffer, outputBuffer)
        outputBuffer.rewind()

        // 5. Parse Predictions
        val dynamicDetectedList = mutableListOf<DynamicYoloBox>()
        val floatBuffer = outputBuffer.asFloatBuffer()
        
        // Simple parsing logic for YOLOv8 output [84 x 8400]
        // [0..3] -> cx, cy, w, h
        // [4..83] -> class scores
        for (i in 0 until 8400) {
            var maxScore = 0f
            var maxClassId = -1
            
            for (c in 0 until 80) {
                val score = floatBuffer.get( (4 + c) * 8400 + i)
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

                val xMin = (cx - w / 2f) / modelInputSize
                val yMin = (cy - h / 2f) / modelInputSize
                val xMax = (cx + w / 2f) / modelInputSize
                val yMax = (cy + h / 2f) / modelInputSize

                val label = labels.getOrNull(maxClassId) ?: "UNKNOWN"
                
                dynamicDetectedList.add(
                    DynamicYoloBox(
                        label = label,
                        confidence = maxScore,
                        relativeAnchor = Offset((xMin + xMax) / 2f, (yMin + yMax) / 2f),
                        xMin = xMin,
                        yMin = yMin,
                        xMax = xMax,
                        yMax = yMax,
                        infoTag = "${label.uppercase()} // ${ (maxScore * 100).toInt()}% CONF"
                    )
                )
            }
        }

        val totalInferenceTime = System.currentTimeMillis() - startTime
        onTargetsDetected(dynamicDetectedList, totalInferenceTime)
        
        imageProxy.close()
    }
}
