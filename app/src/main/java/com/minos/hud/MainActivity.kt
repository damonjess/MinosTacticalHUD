package com.minos.hud

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import ai.onnxruntime.*
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var hudOverlay: HUDOverlayView
    private var ortSession: OrtSession? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        hudOverlay = findViewById(R.id.hudOverlay)

        cameraExecutor = Executors.newSingleThreadExecutor()

        requestCameraPermission()
        loadONNXModel()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera() else finish()
    }

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == 
                PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun loadONNXModel() {
        try {
            val env = OrtEnvironment.getEnvironment()
            // Put your yolov8n.onnx in assets/ and load it here
            assets.open("yolov8n.onnx").use { input ->
                ortSession = env.createSession(input.readBytes())
            }
            hudOverlay.setSession(ortSession)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        // Run YOLO inference here
                        runDetection(imageProxy)
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun runDetection(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmapCustom()
        val inputTensor = preprocessBitmap(bitmap)

        try {
            val inputs = mapOf("images" to inputTensor)  // YOLOv8 input name is usually "images"
            val outputs = ortSession?.run(inputs)

            if (outputs != null) {
                val detections = postProcess(outputs, bitmap.width, bitmap.height)
                hudOverlay.updateDetections(detections)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            inputTensor.close()
        }
    }

    // Helper: Convert ImageProxy to Bitmap
    private fun ImageProxy.toBitmapCustom(): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
    }

    // Preprocessing for YOLOv8 (640x640 input)
    private fun preprocessBitmap(bitmap: Bitmap): OnnxTensor {
        val env = OrtEnvironment.getEnvironment()
        val resized = Bitmap.createScaledBitmap(bitmap, 640, 640, true)

        val tensorData = FloatBuffer.allocate(1 * 3 * 640 * 640)
        tensorData.rewind()

        // YOLOv8 expects NCHW format: [1, 3, 640, 640]
        // Planar format (all reds, then all greens, then all blues)
        for (c in 0 until 3) {
            for (y in 0 until 640) {
                for (x in 0 until 640) {
                    val pixel = resized.getPixel(x, y)
                    val value = when (c) {
                        0 -> Color.red(pixel)
                        1 -> Color.green(pixel)
                        else -> Color.blue(pixel)
                    }
                    tensorData.put(value / 255f)
                }
            }
        }

        tensorData.rewind()
        val shape = longArrayOf(1, 3, 640, 640)
        return OnnxTensor.createTensor(env, tensorData, shape)
    }

    // Post-processing (basic NMS + boxes)
    private fun postProcess(outputs: OrtSession.Result, origWidth: Int, origHeight: Int): List<Detection> {
        val detections = mutableListOf<Detection>()
        // YOLOv8 output is usually shape [1, 84, 8400] or similar
        // This is a simplified version - adjust based on your model output

        // TODO: Parse actual output tensors (outputs[0] is usually the main one)
        // For now, placeholder detections for testing
        detections.add(Detection(100f, 100f, 150f, 150f, "Target", 0.85f))

        return detections
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ortSession?.close()
    }
}
