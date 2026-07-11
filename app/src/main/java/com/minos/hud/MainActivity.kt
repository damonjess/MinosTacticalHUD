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

    private fun runDetection(imageProxy: androidx.camera.core.ImageProxy) {
        // TODO: Preprocess image → run ortSession.run() → post-process boxes
        // Then call hudOverlay.updateDetections(boxes)
        hudOverlay.postInvalidate()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ortSession?.close()
    }
}
