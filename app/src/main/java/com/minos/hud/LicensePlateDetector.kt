package com.minos.hud

import ai.onnxruntime.*
import android.content.Context
import android.graphics.*
import java.nio.FloatBuffer
import java.util.*

data class PlateDetectionResult(
    val plateTarget: YoloTarget,
    val plateCrop: Bitmap
)

class LicensePlateDetector(context: Context) : AutoCloseable {
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private val modelInputSize = 640

    init {
        try {
            val modelBytes = context.assets.open("license_plate_yolov5s.onnx").readBytes()
            ortSession = ortEnv.createSession(modelBytes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun detectAndCropPlate(vehicleBitmap: Bitmap): PlateDetectionResult? {
        val session = ortSession ?: return null
        if (vehicleBitmap.isRecycled || vehicleBitmap.width < 100 || vehicleBitmap.height < 60) return null

        val matrix = Matrix()
        val scale = modelInputSize.toFloat() / maxOf(vehicleBitmap.width, vehicleBitmap.height)
        matrix.postScale(scale, scale)
        
        val resizedBitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resizedBitmap)
        canvas.drawColor(Color.DKGRAY)
        canvas.drawBitmap(vehicleBitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))

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

        var bestResult: PlateDetectionResult? = null
        try {
            val inputTensor = OnnxTensor.createTensor(ortEnv, imgData, longArrayOf(1, 3, modelInputSize.toLong(), modelInputSize.toLong()))
            val results = session.run(Collections.singletonMap(session.inputNames.iterator().next(), inputTensor))
            
            val outputTensor = results[0] as OnnxTensor
            val buffer = outputTensor.floatBuffer
            val shape = outputTensor.info.shape
            
            val numBoxes = shape[1].toInt()
            val numFeatures = shape[2].toInt()
            var maxConf = 0.52f

            for (i in 0 until numBoxes) {
                val offset = i * numFeatures
                val objConf = buffer.get(offset + 4)
                val clsConf = if (numFeatures > 5) buffer.get(offset + 5) else 1.0f
                val confidence = objConf * clsConf

                if (confidence > maxConf) {
                    val cx = buffer.get(offset + 0) / (scale * vehicleBitmap.width)
                    val cy = buffer.get(offset + 1) / (scale * vehicleBitmap.height)
                    val w = buffer.get(offset + 2) / (scale * vehicleBitmap.width)
                    val h = buffer.get(offset + 3) / (scale * vehicleBitmap.height)
                    
                    val aspectRatio = w / maxOf(0.01f, h)
                    // Plausible plate aspect ratios
                    if (aspectRatio in 1.4f..8.5f) {
                        val xMin = (cx - w / 2f).coerceIn(0f, 1f)
                        val yMin = (cy - h / 2f).coerceIn(0f, 1f)
                        val xMax = (cx + w / 2f).coerceIn(0f, 1f)
                        val yMax = (cy + h / 2f).coerceIn(0f, 1f)

                        val pLeft = (xMin * vehicleBitmap.width).toInt().coerceIn(0, vehicleBitmap.width - 1)
                        val pTop = (yMin * vehicleBitmap.height).toInt().coerceIn(0, vehicleBitmap.height - 1)
                        val pWidth = ((xMax - xMin) * vehicleBitmap.width).toInt().coerceIn(1, vehicleBitmap.width - pLeft)
                        val pHeight = ((yMax - yMin) * vehicleBitmap.height).toInt().coerceIn(1, vehicleBitmap.height - pTop)

                        val plateCrop = try {
                            val raw = Bitmap.createBitmap(vehicleBitmap, pLeft, pTop, pWidth, pHeight)
                            raw.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: Exception) { null }

                        if (plateCrop != null) {
                            maxConf = confidence
                            bestResult = PlateDetectionResult(
                                plateTarget = YoloTarget(
                                    id = UUID.randomUUID().toString(),
                                    label = "LICENSE_PLATE",
                                    rawLabel = "plate",
                                    confidence = confidence,
                                    xMin = xMin,
                                    yMin = yMin,
                                    xMax = xMax,
                                    yMax = yMax,
                                    crop = plateCrop
                                ),
                                plateCrop = plateCrop
                            )
                        }
                    }
                }
            }
            inputTensor.close()
            results.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            resizedBitmap.recycle()
        }
        
        return bestResult
    }

    override fun close() {
        ortSession?.close()
    }
}
