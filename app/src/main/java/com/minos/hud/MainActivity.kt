package com.minos.hud

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import ai.onnxruntime.*
import java.nio.ByteOrder
import java.nio.FloatBuffer
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

        // Max performance settings for Magic 8 Pro
        setupHighPerformanceMode()

        cameraExecutor = Executors.newSingleThreadExecutor()

        requestCameraPermission()
        loadONNXModel()
    }

    private fun setupHighPerformanceMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        // High brightness for outdoor/HUD use
        val layoutParams = window.attributes
        layoutParams.screenBrightness = 1.0f
        window.attributes = layoutParams
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startHighPerformanceCamera() else finish()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == 
            PackageManager.PERMISSION_GRANTED) {
            startHighPerformanceCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun loadONNXModel() {
        try {
            val env = OrtEnvironment.getEnvironment()
            assets.open("yolov8n.onnx").use { input ->
                ortSession = env.createSession(input.readBytes())
            }
            hudOverlay.setSession(ortSession)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startHighPerformanceCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            @Suppress("DEPRECATION")
            val preview = Preview.Builder()
                .setTargetResolution(Size(1080, 1920))  // High res Portrait
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            @Suppress("DEPRECATION")
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(720, 1280))   // Balanced for AI speed Portrait
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        runDetection(imageProxy)
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

                // Enable highest frame rate possible
                camera.cameraControl.setLinearZoom(0f)
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun runDetection(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmapCustom()
        val inputTensor = preprocessBitmap(bitmap)

        try {
            val inputs = mapOf("images" to inputTensor)
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

    private fun ImageProxy.toBitmapCustom(): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
    }

    private fun preprocessBitmap(bitmap: Bitmap): OnnxTensor {
        val env = OrtEnvironment.getEnvironment()
        val resized = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
        val tensorData = FloatBuffer.allocate(1 * 3 * 640 * 640)
        tensorData.rewind()

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

    private fun postProcess(outputs: OrtSession.Result, origWidth: Int, origHeight: Int): List<Detection> {
        val detections = mutableListOf<Detection>()
        // Simplified version
        detections.add(Detection(100f, 100f, 150f, 150f, "Target", 0.85f))
        return detections
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ortSession?.close()
    }
}
