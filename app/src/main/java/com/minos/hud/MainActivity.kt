package com.minos.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.preference.PreferenceManager
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var stillExecutor: ExecutorService
    private var cameraControl by mutableStateOf<CameraControl?>(null)
    private var cameraInfo by mutableStateOf<CameraInfo?>(null)
    private var imageCapture: ImageCapture? = null

    private var hudOverlay: HUDOverlayView? = null
    private var previewView by mutableStateOf<PreviewView?>(null)
    private var imageAnalyzer: OnnxImageAnalyzer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        Configuration.getInstance().load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        Configuration.getInstance().userAgentValue = packageName

        enableEdgeToEdge()
        setupHighPerformanceMode()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MinosHUD::InferenceWakeLock").apply {
            acquire(30 * 60 * 1000L)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                val isThrottling = status >= PowerManager.THERMAL_STATUS_MODERATE
                if (isThrottling) {
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                        Log.i("MainActivity", "Thermal throttling: releasing wake lock (status=$status)")
                    }
                    toggleSustainedPerformanceMode(false)
                } else {
                    if (wakeLock?.isHeld == false) {
                        wakeLock?.acquire(30 * 60 * 1000L)
                        Log.i("MainActivity", "Thermal recovered: acquiring wake lock (status=$status)")
                    }
                    toggleSustainedPerformanceMode(true)
                }
            }
            powerManager.addThermalStatusListener(thermalListener!!)
            // Explicitly apply initial state since listener only fires on changes
            thermalListener!!.onThermalStatusChanged(powerManager.currentThermalStatus)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        stillExecutor = Executors.newSingleThreadExecutor()

        imageAnalyzer = OnnxImageAnalyzer(
            context = this,
            modelName = "yolov8n.onnx",
            getIsScanning = { viewModel.isScanning },
            getSensitivityThreshold = { viewModel.sensitivityThreshold },
            getMaxDetections = { viewModel.maxDetections },
            getAutoMag = { viewModel.autoMag },
            getDigitalZoom = { viewModel.digitalZoom },
            setDigitalZoom = { newZoom ->
                viewModel.digitalZoom = newZoom
                cameraControl?.setZoomRatio(newZoom)
            },
            getIsCaptureOn = { viewModel.isCaptureOn },
            getDetectionMode = { viewModel.detectionMode },
            getProfile = { viewModel.selectedProfile },
            getQualityPreset = { viewModel.qualityPreset },
            getLockedTrackId = { viewModel.lockedTrackId },
            onTriggerHighResCapture = { xMin, yMin, xMax, yMax, padding, onCaptured ->
                val capture = imageCapture
                if (capture != null) {
                    try {
                        capture.takePicture(
                            stillExecutor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                    try {
                                        val rawBitmap = imageProxy.toBitmap()
                                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                        val rotatedBitmap = if (rotationDegrees != 0) {
                                            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                                            val rot = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                                            rawBitmap.recycle()
                                            rot
                                        } else {
                                            rawBitmap
                                        }

                                        val highResCrop = BestFrameSelector.cropDetection(
                                            rotatedBitmap,
                                            xMin,
                                            yMin,
                                            xMax,
                                            yMax,
                                            padding = padding
                                        )
                                        if (rotatedBitmap != highResCrop && !rotatedBitmap.isRecycled) {
                                            rotatedBitmap.recycle()
                                        }
                                        onCaptured(highResCrop)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Error cropping high-res still image", e)
                                        onCaptured(null)
                                    } finally {
                                        imageProxy.close()
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e("MainActivity", "High-res ImageCapture failed", exception)
                                    onCaptured(null)
                                }
                            }
                        )
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to invoke takePicture", e)
                        onCaptured(null)
                    }
                } else {
                    onCaptured(null)
                }
            },
            onBoxesReady = { yoloTargets, frameTimeMs, rotatedWidth, rotatedHeight ->
                hudOverlay?.setCameraSourceDimensions(rotatedWidth, rotatedHeight)
                hudOverlay?.updateTargets(yoloTargets, frameTimeMs)
            },
            onTargetsDetected = { magTargets, _, inferenceTimeMs, _, _ ->
                viewModel.updateTrackedTargets(magTargets)
                viewModel.inferenceValue = inferenceTimeMs
                hudOverlay?.magTrackTargets = magTargets
            },
            onFpsUpdated = { fps ->
                viewModel.fpsValue = fps
            },
            onModelLoadError = { error ->
                viewModel.modelLoadError = error
            }
        )

        EventRepository.loadEvents(this)

        setContent {
            TacticalHudTheme {
                val context = LocalContext.current
                LaunchedEffect(previewView) {
                    if (previewView != null && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startHighPerformanceCamera()
                    }
                }

                when (viewModel.currentScreen) {
                    Screen.HUD -> MainContent(
                        viewModel = viewModel,
                        cameraControl = cameraControl,
                        cameraInfo = cameraInfo,
                        previewView = previewView,
                        onPreviewViewCreated = { view ->
                            previewView = view
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                startHighPerformanceCamera()
                            }
                        },
                        onHudOverlayCreated = { overlay ->
                            hudOverlay = overlay
                            overlay.onTargetLockedId = { trackId -> viewModel.lockTarget(trackId) }
                        },
                        onReleaseTarget = {
                            hudOverlay?.releaseTarget()
                            viewModel.releaseLock()
                        },
                        onLogsClick = { viewModel.currentScreen = Screen.EVENT_LOG }
                    )
                    Screen.TACTICAL_MAP -> TacticalMapScreen(onBack = { viewModel.currentScreen = Screen.HUD })
                    Screen.EVENT_LOG -> EventLogScreen(onBack = { viewModel.currentScreen = Screen.HUD })
                }
            }
        }

        requestRequiredPermissions()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            startHighPerformanceCamera()
        } else {
            finish()
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startHighPerformanceCamera()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun setupHighPerformanceMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toggleSustainedPerformanceMode(true)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    private fun toggleSustainedPerformanceMode(enable: Boolean) {
        try {
            if (packageManager.hasSystemFeature("android.hardware.sustained_performance_mode")) {
                window.setSustainedPerformanceMode(enable)
                Log.i("MainActivity", "Sustained performance mode set to: $enable")
            } else {
                Log.i("MainActivity", "Sustained performance mode not supported on this device.")
            }
        } catch (_: Exception) {
            // Sustained performance mode may not be supported on all devices
        }
    }

    private fun startHighPerformanceCamera() {
        val view = previewView ?: return
        val analyzer = imageAnalyzer ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(view.surfaceProvider)
            }

            val cameraImageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor, analyzer)
                }

            val highResImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(95)
                .build()
            imageCapture = highResImageCapture

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    cameraImageAnalysis,
                    highResImageCapture
                )
                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        
        // Only acquire if we are not thermally throttled
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isThrottling = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        } else false
        
        if (!isThrottling && wakeLock?.isHeld == false) {
            wakeLock?.acquire(30 * 60 * 1000L)
        }
    }

    override fun onPause() {
        super.onPause()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                powerManager.removeThermalStatusListener(it)
            }
        }
        imageAnalyzer?.close()
        cameraExecutor.shutdown()
        stillExecutor.shutdown()
        VideoBuffer.clear()
    }
}
