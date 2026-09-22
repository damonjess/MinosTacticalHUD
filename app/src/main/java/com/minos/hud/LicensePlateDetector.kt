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
            val cpuOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                try {
                    // addXnnpack(mapOf("intra_op_num_threads" to "4"))
                } catch (e: Throwable) {
                    // XNNPACK provider unsupported or omitted in build
                }
            }
            try {
                ortSession = ortEnv.createSession(modelBytes, cpuOptions)
                Log.i("LicensePlateDetector", "Successfully loaded license_plate_yolov5s.onnx with optimized CPU execution provider.")
            } catch (e: Exception) {
                Log.w("LicensePlateDetector", "Optimized CPU initialization failed (${e.message}). Falling back to basic CPU execution.", e)
                val fallbackOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                }
                ortSession = ortEnv.createSession(modelBytes, fallbackOptions)
                Log.i("LicensePlateDetector", "Successfully loaded license_plate_yolov5s.onnx with basic CPU execution provider.")
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
        if (vehicleBitmap.isRecycled || vehicleBitmap.width < 40 || vehicleBitmap.height < 20) return null

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
            
            val dim1 = shape[1].toInt()
            val dim2 = shape[2].toInt()
            val isTransposed = dim1 < dim2 && (dim1 == 5 || dim1 == 6 || dim1 == 84 || dim1 == 85)
            val numBoxes = if (isTransposed) dim2 else dim1
            val numFeatures = if (isTransposed) dim1 else dim2
            var maxConf = confidenceThreshold.coerceAtLeast(0.12f)

            for (i in 0 until numBoxes) {
                val cxRaw = if (isTransposed) buffer.get(0 * numBoxes + i) else buffer.get(i * numFeatures + 0)
                val cyRaw = if (isTransposed) buffer.get(1 * numBoxes + i) else buffer.get(i * numFeatures + 1)
                val wRaw  = if (isTransposed) buffer.get(2 * numBoxes + i) else buffer.get(i * numFeatures + 2)
                val hRaw  = if (isTransposed) buffer.get(3 * numBoxes + i) else buffer.get(i * numFeatures + 3)
                val objConf = if (isTransposed) buffer.get(4 * numBoxes + i) else buffer.get(i * numFeatures + 4)
                val clsConf = if (numFeatures > 5) {
                    if (isTransposed) buffer.get(5 * numBoxes + i) else buffer.get(i * numFeatures + 5)
                } else 1.0f
                val confidence = objConf * clsConf

                if (confidence > maxConf) {
                    val cx = (cxRaw - padX) / (scale * srcW)
                    val cy = (cyRaw - padY) / (scale * srcH)
                    val w = wRaw / (scale * srcW)
                    val h = hRaw / (scale * srcH)
                    
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
