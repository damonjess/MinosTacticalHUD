package com.minos.hud

import ai.onnxruntime.*
import android.content.Context
import android.graphics.*
import java.nio.FloatBuffer
import java.util.*

class LicensePlateDetector(context: Context) : AutoCloseable {
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession
    private val modelInputSize = 640

    init {
        val modelBytes = context.assets.open("license_plate_yolov5s.onnx").readBytes()
        ortSession = ortEnv.createSession(modelBytes)
    }

    fun detectPlate(bitmap: Bitmap): YoloTarget? {
        val matrix = Matrix()
        val scale = modelInputSize.toFloat() / maxOf(bitmap.width, bitmap.height)
        matrix.postScale(scale, scale)
        
        val resizedBitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resizedBitmap)
        canvas.drawColor(Color.GRAY)
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))

        val imgData = FloatBuffer.allocate(1 * 3 * modelInputSize * modelInputSize)
        imgData.rewind()
        
        val pixels = IntArray(modelInputSize * modelInputSize)
        resizedBitmap.getPixels(pixels, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize)
        
        for (i in 0 until modelInputSize * modelInputSize) {
            val clr = pixels[i]
            imgData.put(i, ((clr shr 16) and 0xFF) / 255.0f)
            imgData.put(i + modelInputSize * modelInputSize, ((clr shr 8) and 0xFF) / 255.0f)
            imgData.put(i + 2 * modelInputSize * modelInputSize, (clr and 0xFF) / 255.0f)
        }
        imgData.rewind()

        val inputTensor = OnnxTensor.createTensor(ortEnv, imgData, longArrayOf(1, 3, modelInputSize.toLong(), modelInputSize.toLong()))
        val results = ortSession.run(Collections.singletonMap(ortSession.inputNames.iterator().next(), inputTensor))
        
        val output = results[0].value as Array<Array<FloatArray>>
        val candidates = output[0] // shape: [candidates, 6]

        var bestPlate: YoloTarget? = null
        var maxConf = 0.45f

        for (candidate in candidates) {
            val confidence = candidate[4] * candidate[5]
            if (confidence > maxConf) {
                val cx = candidate[0] / modelInputSize
                val cy = candidate[1] / modelInputSize
                val w = candidate[2] / modelInputSize
                val h = candidate[3] / modelInputSize
                
                // Aspect ratio check for plate plausibility
                val aspectRatio = w / h
                if (aspectRatio in 1.35f..8.5f) {
                    maxConf = confidence
                    bestPlate = YoloTarget(
                        id = UUID.randomUUID().toString(),
                        label = "LICENSE_PLATE",
                        confidence = confidence,
                        xMin = cx - w / 2,
                        yMin = cy - h / 2,
                        xMax = cx + w / 2,
                        yMax = cy + h / 2
                    )
                }
            }
        }
        
        resizedBitmap.recycle()
        inputTensor.close()
        results.close()
        
        return bestPlate
    }

    override fun close() {
        ortSession.close()
    }
}
