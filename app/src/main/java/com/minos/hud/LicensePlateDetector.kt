package com.minos.hud

import ai.onnxruntime.*
import android.content.Context
import android.graphics.*
import android.util.Log
import java.nio.FloatBuffer
import java.util.*

data class PlateDetectionResult(
    val plateTarget: YoloTarget,
    val plateCrop: Bitmap
)

class LicensePlateDetector(
    context: Context,
    var minConfidence: Float = 0.25f,
    var minAspectRatio: Float = 0.8f,
    var maxAspectRatio: Float = 10.0f
) : AutoCloseable {
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private val modelInputSize = 640

    val isLoaded: Boolean
        get() = ortSession != null

    // Pre-allocated reusable buffers to eliminate GC churn
    private val resizedBitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
    private val resizedCanvas = Canvas(resizedBitmap)
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imgData = FloatBuffer.allocate(1 * 3 * modelInputSize * modelInputSize)
    private val pixels = IntArray(modelInputSize * modelInputSize)

    init {
        try {
            val modelBytes = context.assets.open("license_plate_yolov5s.onnx").readBytes()
            try {
                val nnapiOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    addNnapi()
                }
                ortSession = ortEnv.createSession(modelBytes, nnapiOptions)
                Log.i("LicensePlateDetector", "Successfully loaded license_plate_yolov5s.onnx with NNAPI execution provider.")
            } catch (e: Exception) {
                Log.w("LicensePlateDetector", "NNAPI initialization failed for license_plate_yolov5s.onnx (${e.message}). Falling back to CPU execution.", e)
                val cpuOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                }
                ortSession = ortEnv.createSession(modelBytes, cpuOptions)
                Log.i("LicensePlateDetector", "Successfully loaded license_plate_yolov5s.onnx with CPU execution provider.")
            }
        } catch (e: Exception) {
            Log.e("LicensePlateDetector", "Failed to load license_plate_yolov5s.onnx", e)
            e.printStackTrace()
        }
    }

    fun detectAndCropPlate(
        vehicleBitmap: Bitmap,
        confidenceThreshold: Float = minConfidence,
        minRatio: Float = minAspectRatio,
        maxRatio: Float = maxAspectRatio
    ): PlateDetectionResult? {
        val session = ortSession ?: return null
        if (vehicleBitmap.isRecycled || vehicleBitmap.width < 80 || vehicleBitmap.height < 40) return null

        val srcW = vehicleBitmap.width.toFloat()
        val srcH = vehicleBitmap.height.toFloat()
        val scale = minOf(modelInputSize / srcW, modelInputSize / srcH)
        val dstW = srcW * scale
        val dstH = srcH * scale
        val padX = (modelInputSize - dstW) / 2f
        val padY = (modelInputSize - dstH) / 2f

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(padX, padY)
        }
        
        resizedCanvas.drawColor(Color.BLACK)
        resizedCanvas.drawBitmap(vehicleBitmap, matrix, filterPaint)

        imgData.rewind()
        
        resizedBitmap.getPixels(pixels, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize)
        
        val totalPixels = modelInputSize * modelInputSize
        for (i in 0 until totalPixels) {
            val clr = pixels[i]
            imgData.put(i, ((clr shr 16) and 0xFF) / 255.0f)
            imgData.put(i + totalPixels, ((clr shr 8) and 0xFF) / 255.0f)
            imgData.put(i + 2 * totalPixels, (clr and 0xFF) / 255.0f)
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
            var maxConf = confidenceThreshold.coerceAtLeast(0.15f)

            for (i in 0 until numBoxes) {
                val offset = i * numFeatures
                val objConf = buffer.get(offset + 4)
                val clsConf = if (numFeatures > 5) buffer.get(offset + 5) else 1.0f
                val confidence = objConf * clsConf

                if (confidence > maxConf) {
                    val cx = (buffer.get(offset + 0) - padX) / (scale * srcW)
                    val cy = (buffer.get(offset + 1) - padY) / (scale * srcH)
                    val w = buffer.get(offset + 2) / (scale * srcW)
                    val h = buffer.get(offset + 3) / (scale * srcH)
                    
                    val aspectRatio = w / maxOf(0.01f, h)
                    // Plausible plate aspect ratios (supports oblique angles, square plates, wide ratios)
                    if (aspectRatio in minRatio..maxRatio) {
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
                            val result = raw.copy(Bitmap.Config.ARGB_8888, false)
                            raw.recycle()
                            result
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
        }
        
        return bestResult
    }

    override fun close() {
        ortSession?.close()
        if (!resizedBitmap.isRecycled) {
            resizedBitmap.recycle()
        }
    }
}
