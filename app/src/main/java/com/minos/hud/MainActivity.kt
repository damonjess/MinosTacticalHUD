package com.minos.hud

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.preference.PreferenceManager
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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var cameraExecutor: ExecutorService
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var imageCapture: ImageCapture? = null

    private var hudOverlay: HUDOverlayView? = null
    private var previewView: PreviewView? = null
    private var imageAnalyzer: OnnxImageAnalyzer? = null

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

        cameraExecutor = Executors.newSingleThreadExecutor()

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
            getImageCapture = { imageCapture },
            onTargetsDetected = { magTargets, yoloTargets, inferenceTimeMs, rotatedWidth, rotatedHeight ->
                viewModel.updateTrackedTargets(magTargets)
                viewModel.inferenceValue = inferenceTimeMs
                hudOverlay?.setCameraSourceDimensions(rotatedWidth, rotatedHeight)
                hudOverlay?.magTrackTargets = magTargets
                hudOverlay?.updateTargets(yoloTargets)
            },
            onFpsUpdated = { fps ->
                viewModel.fpsValue = fps
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
                        previewView = previewView,
                        onPreviewViewCreated = { view ->
                            previewView = view
                        },
                        onHudOverlayCreated = { overlay ->
                            hudOverlay = overlay
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
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
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
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor, analyzer)
                }

            val imageCaptureUseCase = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            imageCapture = imageCaptureUseCase

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    cameraImageAnalysis,
                    imageCaptureUseCase
                )
                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        imageAnalyzer?.close()
        cameraExecutor.shutdown()
    }
}
