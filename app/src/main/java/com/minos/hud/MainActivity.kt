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
    private lateinit var btnSettings: MaterialButton
    private lateinit var dossierOverlay: View
    private lateinit var settingsOverlay: View
    private lateinit var btnCloseDossier: View
    private lateinit var btnCloseSettings: View
    private lateinit var btnNewScan: View
    private lateinit var switchBoundingBoxes: androidx.appcompat.widget.SwitchCompat
    private lateinit var seekBarSensitivity: android.widget.SeekBar
    private lateinit var txtSensitivityValue: TextView

    // State
    private var isScanning = true
    private var isTorchOn = false
    private var currentZoom = 1f
    private var currentModel = "yolov8n.onnx"
    private var lastFpsUpdateTime = 0L
    private var frameCount = 0
    private var sensitivityThreshold = 0.50f

    // Tactical HUD State (Mag-Track)
    private var digitalZoom = 8f
    private var motionSensitivity = 36f
    private var activePanel = "GEOLOG"
    private var motionArrayOn = true
    private var autoTargetLock = true
    private var targetViewMode = "BIG"

    private val trackedTargets = listOf(
        MagTrackTarget("TRACK-01", "AUTO MAG-TRACK // TRACK-01", "X80 Y543 A1 TARGET", 0.28f, 0.32f, "https://picsum.photos/seed/t1/200/150"),
        MagTrackTarget("TRACK-02", "AUTO MAG-TRACK // TRACK-02", "X122 Y324 A3 FILTER", 0.48f, 0.35f, "https://picsum.photos/seed/t2/200/150"),
        MagTrackTarget("TRACK-03", "AUTO MAG-TRACK // TRACK-03", "X116 Y204 A9 Z:19%", 0.18f, 0.74f, "https://picsum.photos/seed/t3/200/150"),
        MagTrackTarget("TRACK-04", "AUTO MAG-TRACK // TRACK-04", "X304 Y214 Z4 Z:12%", 0.83f, 0.74f, "https://picsum.photos/seed/t4/200/150")
    )

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
        btnSettings = findViewById(R.id.btnSettings)
        dossierOverlay = findViewById(R.id.dossierOverlay)
        settingsOverlay = findViewById(R.id.settingsOverlay)
        btnCloseDossier = findViewById(R.id.btnCloseDossier)
        btnCloseSettings = findViewById(R.id.btnCloseSettings)
        btnNewScan = findViewById(R.id.btnNewScan)
        switchBoundingBoxes = findViewById(R.id.switchBoundingBoxes)
        seekBarSensitivity = findViewById(R.id.seekBarSensitivity)
        txtSensitivityValue = findViewById(R.id.txtSensitivityValue)

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
            if (!isScanning) hudOverlay.updateTargets(emptyList())
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

        btnSettings.setOnClickListener {
            isScanning = false
            settingsOverlay.visibility = View.VISIBLE
        }

        btnCloseSettings.setOnClickListener {
            settingsOverlay.visibility = View.GONE
            isScanning = true
        }

        switchBoundingBoxes.setOnCheckedChangeListener { _, isChecked ->
            hudOverlay.isYoloBoxesEnabled = isChecked
            hudOverlay.postInvalidate()
        }

        seekBarSensitivity.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(10)
                sensitivityThreshold = value / 100f
                hudOverlay.sensitivityThreshold = sensitivityThreshold
                txtSensitivityValue.text = "$value%"
                hudOverlay.postInvalidate()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Dossier Control Listeners
        hudOverlay.setOnClickListener {
            showDossier()
        }

        btnCloseDossier.setOnClickListener {
            dossierOverlay.visibility = View.GONE
            isScanning = true
        }

        btnNewScan.setOnClickListener {
            dossierOverlay.visibility = View.GONE
            isScanning = true
        }
    }

    private fun showDossier() {
        isScanning = false
        dossierOverlay.visibility = View.VISIBLE
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
                val targets = postProcess(outputs, bitmap.width, bitmap.height)
                runOnUiThread {
                    hudOverlay.updateTargets(targets)
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

    private fun postProcess(outputs: OrtSession.Result, origWidth: Int, origHeight: Int): List<YoloTarget> {
        val targets = mutableListOf<YoloTarget>()
        // Simulated targets with normalized coordinates and biometric tags
        val randomId = (1000..9999).random()
        
        // Mocking live telemetry feed logic
        if (System.currentTimeMillis() % 2 == 0L) {
            targets.add(YoloTarget("TGT-01", "SUBJECT RECON // SIGMA-$randomId", 0.92f, 0.2f, 0.25f, 0.5f, 0.65f))
        } else {
            targets.add(YoloTarget("TGT-02", "INTEL TRACE // DELTA-$randomId", 0.78f, 0.6f, 0.15f, 0.85f, 0.45f))
        }

        // Filter by sensitivityThreshold
        return targets.filter { it.confidence >= sensitivityThreshold }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ortSession?.close()
    }
}
