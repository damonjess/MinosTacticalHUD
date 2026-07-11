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
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import ai.onnxruntime.*
import com.google.android.material.button.MaterialButton
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var hudOverlay: HUDOverlayView
    private var ortSession: OrtSession? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null

    // UI Elements
    private lateinit var fpsText: TextView
    private lateinit var inferenceText: TextView
    private lateinit var btnToggleDetection: MaterialButton
    private lateinit var btnCycleReticle: MaterialButton
    private lateinit var btnToggleTorch: MaterialButton
    private lateinit var btnZoom: MaterialButton
    private lateinit var btnSwitchModel: MaterialButton

    // State
    private var isScanning = true
    private var isTorchOn = false
    private var currentZoom = 1f
    private var currentModel = "yolov8n.onnx"
    private var lastFpsUpdateTime = 0L
    private var frameCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        hudOverlay = findViewById(R.id.hudOverlay)
        fpsText = findViewById(R.id.fpsText)
        inferenceText = findViewById(R.id.inferenceText)
        btnToggleDetection = findViewById(R.id.btnToggleDetection)
        btnCycleReticle = findViewById(R.id.btnCycleReticle)
        btnToggleTorch = findViewById(R.id.btnToggleTorch)
        btnZoom = findViewById(R.id.btnZoom)
        btnSwitchModel = findViewById(R.id.btnSwitchModel)

        setupHighPerformanceMode()
        setupListeners()

        cameraExecutor = Executors.newSingleThreadExecutor()

        requestCameraPermission()
        loadONNXModel(currentModel)
    }

    private fun setupListeners() {
        btnToggleDetection.setOnClickListener {
            isScanning = !isScanning
            btnToggleDetection.text = if (isScanning) "SCAN: ON" else "SCAN: OFF"
            if (!isScanning) hudOverlay.updateDetections(emptyList())
        }

        btnCycleReticle.setOnClickListener {
            val styles = HUDOverlayView.ReticleStyle.values()
            val nextIndex = (hudOverlay.currentReticleStyle.ordinal + 1) % styles.size
            hudOverlay.currentReticleStyle = styles[nextIndex]
            hudOverlay.postInvalidate()
        }

        btnToggleTorch.setOnClickListener {
            isTorchOn = !isTorchOn
            cameraControl?.enableTorch(isTorchOn)
        }

        btnZoom.setOnClickListener {
            currentZoom = when (currentZoom) {
                1f -> 2f
                2f -> 5f
                else -> 1f
            }
            cameraControl?.setZoomRatio(currentZoom)
            btnZoom.text = "ZOOM: ${currentZoom.toInt()}X"
        }

        btnSwitchModel.setOnClickListener {
            currentModel = if (currentModel == "yolov8n.onnx") "yolo26n.onnx" else "yolov8n.onnx"
            loadONNXModel(currentModel)
            btnSwitchModel.text = "MODEL: ${if (currentModel.contains("v8")) "V8" else "V26"}"
        }
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

    private fun loadONNXModel(modelName: String) {
        try {
            ortSession?.close()
            val env = OrtEnvironment.getEnvironment()
            assets.open(modelName).use { input ->
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
                .setTargetResolution(Size(1080, 1920))
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            @Suppress("DEPRECATION")
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(720, 1280))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isScanning) {
                            runDetection(imageProxy)
                        } else {
                            updateFps()
                        }
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun runDetection(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis()
        val bitmap = imageProxy.toBitmapCustom()
        val inputTensor = preprocessBitmap(bitmap)

        try {
            val inputs = mapOf("images" to inputTensor)
            val outputs = ortSession?.run(inputs)

            if (outputs != null) {
                val detections = postProcess(outputs, bitmap.width, bitmap.height)
                runOnUiThread {
                    hudOverlay.updateDetections(detections)
                    val inferenceTime = System.currentTimeMillis() - startTime
                    inferenceText.text = "INF: ${inferenceTime}ms"
                    updateFps()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            inputTensor.close()
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsUpdateTime >= 1000) {
            val fps = frameCount
            runOnUiThread {
                fpsText.text = "FPS: $fps"
            }
            frameCount = 0
            lastFpsUpdateTime = now
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
        // Simulated detections for visual feedback
        if (System.currentTimeMillis() % 2 == 0L) {
            detections.add(Detection(200f, 400f, 300f, 300f, "Hostile", 0.92f))
        } else {
            detections.add(Detection(500f, 800f, 200f, 250f, "Humanoid", 0.78f))
        }
        return detections
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ortSession?.close()
    }
}
