package com.minos.hud

import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.face.Face
import java.util.concurrent.Executors

@Composable
fun CameraCaptureScreen(
    onPhotoCaptured: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    
    // Live reactive metrics tracking identified bounds
    var detectedFaces by remember { mutableStateOf<List<Face>>(listOf()) }
    var frameWidth by remember { mutableIntStateOf(1) }
    var frameHeight by remember { mutableIntStateOf(1) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        
        // 1. Hardware Live Camera View Layer
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    // Configure resolution processing pipelines optimized for ML Kit targets
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(720, 1280))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(
                        cameraExecutor,
                        TacticalFaceAnalyzer { faces, width, height ->
                            detectedFaces = faces
                            frameWidth = width
                            frameHeight = height
                        }
                    )

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Real-Time HUD Target Tracking Bracket Overlays
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaleX = size.width / frameHeight.toFloat()  // Rotated dimension alignment checks
            val scaleY = size.height / frameWidth.toFloat()

            detectedFaces.forEach { face ->
                val boundingBox = face.boundingBox
                
                // Adjust coordinate bounds mapping matrix to fit actual screen aspects
                val left = boundingBox.left * scaleX
                val top = boundingBox.top * scaleY
                val right = boundingBox.right * scaleX
                val bottom = boundingBox.bottom * scaleY
                
                val width = right - left
                val height = bottom - top
                val bracketLength = width * 0.2f // Length of crosshair corner notches

                // Draw high-visibility tactical targeting corner frames
                // Top-Left corner bracket
                drawLine(Color(0xFF00FF00), Offset(left, top), Offset(left + bracketLength, top), strokeWidth = 6f)
                drawLine(Color(0xFF00FF00), Offset(left, top), Offset(left, top + bracketLength), strokeWidth = 6f)
                
                // Top-Right corner bracket
                drawLine(Color(0xFF00FF00), Offset(right, top), Offset(right - bracketLength, top), strokeWidth = 6f)
                drawLine(Color(0xFF00FF00), Offset(right, top), Offset(right, top + bracketLength), strokeWidth = 6f)
                
                // Bottom-Left corner bracket
                drawLine(Color(0xFF00FF00), Offset(left, bottom), Offset(left + bracketLength, bottom), strokeWidth = 6f)
                drawLine(Color(0xFF00FF00), Offset(left, bottom), Offset(left, bottom - bracketLength), strokeWidth = 6f)
                
                // Bottom-Right corner bracket
                drawLine(Color(0xFF00FF00), Offset(right, bottom), Offset(right - bracketLength, bottom), strokeWidth = 6f)
                drawLine(Color(0xFF00FF00), Offset(right, bottom), Offset(right, bottom - bracketLength), strokeWidth = 6f)
            }
        }

        // 3. System HUD Diagnostics Dashboard
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                .background(Color(0xAA000000))
                .padding(8.dp)
        ) {
            Text(
                text = "SYS_STATUS: ACTIVE SCANNING PIPELINE",
                color = Color(0xFF00FF00),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "TARGETS IN FIELD: ${detectedFaces.size}",
                color = if (detectedFaces.isNotEmpty()) Color.Red else Color(0xFF00FF00),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        // 4. Manual Override / Capture Action Row Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x88CC0000))
            ) {
                Text("ABORT", fontFamily = FontFamily.Monospace, color = Color.White)
            }

            // High-Performance Automated Scan Action Trigger
            FilledTonalButton(
                onClick = {
                    // To automatically pass the active frame, capture the current frame buffer 
                    // from the PreviewView, convert to bitmap context, and invoke onPhotoCaptured()
                },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFF00FF00),
                    contentColor = Color.Black
                ),
                modifier = Modifier.size(72.dp),
                shape = androidx.compose.foundation.shape.CircleShape
            ) {
                // Outer tracking circle button display asset
            }
        }
    }
}
