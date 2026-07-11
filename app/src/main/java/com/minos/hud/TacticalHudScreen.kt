package com.minos.hud

import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TacticalHudScreen(viewModel: TacticalHudViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }

    Box(modifier = Modifier.fillMaxSize().background(CyberSecPalette.TerminalBlack)) {
        
        // --- LIVE HARDWARE CAMERA BACKDROP ---
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    // Setup real-time Image Analyzer loop
                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor, YoloAnalyzer(ctx) { realBoxes, inferenceTime ->
                                // Instantly feed physical object parameters to our custom overlay states
                                viewModel.detectedObjects = realBoxes.filter { box ->
                                    box.confidence >= (viewModel.motionSensitivity / 100f)
                                }
                                viewModel.inferenceTimeMs = inferenceTime.toInt()
                            })
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, 
                            CameraSelector.DEFAULT_BACK_CAMERA, 
                            preview, 
                            imageAnalyzer
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                
                previewView
            }
        )

        // --- FIXED NON-OVERLAPPING TERMINAL HEADER ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CyberSecPalette.TerminalBlack.copy(alpha = 0.4f))
                .padding(start = 16.dp, top = 28.dp, end = 16.dp, bottom = 8.dp)
        ) {
            Text(
                text = "PANOPTICORE // SENSOR MATRIX",
                color = CyberSecPalette.CyberNetGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = "SYS.STATUS // ACTIVE INTERCEPT",
                color = CyberSecPalette.TextMutedCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "FPS: ${viewModel.currentFps}",
                    color = CyberSecPalette.CyberNetGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = "INF: ${viewModel.inferenceTimeMs}ms",
                    color = CyberSecPalette.CyberNetGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }

        // --- REALTIME TARGETING & OVERLAY CANVAS ---
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val greenMatrix = CyberSecPalette.CyberNetGreen
            val neonCyan = CyberSecPalette.TextMutedCyan

            // Outer Scope Brackets
            val pad = 40f
            val len = 30f
            // Top Left Scope Corner
            drawLine(neonCyan, Offset(pad, pad + 200), Offset(pad, pad + 200 + len), 3f)
            drawLine(neonCyan, Offset(pad, pad + 200), Offset(pad + len, pad + 200), 3f)

            // Render Multi-Target YOLO Boxes bounding your desktop modules
            viewModel.detectedObjects.forEach { obj ->
                val xMetricMin = obj.xMin * w
                val yMetricMin = obj.yMin * h
                val boxWidth = (obj.xMax - obj.xMin) * w
                val boxHeight = (obj.yMax - obj.yMin) * h

                // Primary Vector Box
                drawRect(
                    color = greenMatrix.copy(alpha = 0.7f),
                    topLeft = Offset(xMetricMin, yMetricMin),
                    size = Size(boxWidth, boxHeight),
                    style = Stroke(width = 2f)
                )

                // Precision Crosshair ticks onto targets
                drawCircle(
                    color = neonCyan,
                    radius = 6f,
                    center = Offset(obj.relativeAnchor.x * w, obj.relativeAnchor.y * h),
                    style = Stroke(1.5f)
                )
            }
        }

        // --- CONTROL DECKS AND MATRIX SLIDERS ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(CyberSecPalette.DeepScannerGrid)
        ) {
            // Navigation Bar Split
            Row(modifier = Modifier.fillMaxWidth().height(48.dp).background(CyberSecPalette.TerminalBlack)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("GEOLOG PANEL", color = CyberSecPalette.CyberNetGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("GPS TACTICAL\nMAP", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("TOOLS CLOSE", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }

            // Controls Drawer Parameters
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("TRACKING + SENSOR OVERLAYS", color = CyberSecPalette.AlertAmber, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Box(modifier = Modifier.weight(1f).border(1.dp, CyberSecPalette.CyberNetGreen).padding(8.dp), contentAlignment = Alignment.Center) {
                        Text("MOTION ARRAY\nON", color = CyberSecPalette.CyberNetGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f).border(1.dp, CyberSecPalette.CyberNetGreen).padding(8.dp), contentAlignment = Alignment.Center) {
                        Text("AUTO TARGET\nLOCK", color = CyberSecPalette.CyberNetGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f).border(1.dp, CyberSecPalette.CyberNetGreen).padding(8.dp), contentAlignment = Alignment.Center) {
                        Text("RADAR SWEEP\nON", color = CyberSecPalette.CyberNetGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text("VISUAL RENDER + TARGET VIEW", color = CyberSecPalette.AlertAmber, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Box(modifier = Modifier.weight(1f).background(Color(0xFF222222)).padding(8.dp), contentAlignment = Alignment.Center) {
                        Text("RENDER\nCLASSIC", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f).border(1.dp, CyberSecPalette.CyberNetGreen).padding(8.dp), contentAlignment = Alignment.Center) {
                        Text("TARGET\nBIG", color = CyberSecPalette.CyberNetGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f).background(Color(0xFF222222)).padding(8.dp), contentAlignment = Alignment.Center) {
                        Text("TARGET\nNORMAL", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("DIGITAL CAMERA ZOOM: ${viewModel.digitalZoom.toInt()}", color = CyberSecPalette.CyberNetGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Slider(
                    value = viewModel.digitalZoom,
                    onValueChange = { viewModel.digitalZoom = it },
                    valueRange = 1f..12f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = CyberSecPalette.CyberNetGreen)
                )
            }
        }
    }
}
