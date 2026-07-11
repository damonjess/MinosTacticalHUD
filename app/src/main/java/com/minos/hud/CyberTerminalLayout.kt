package com.minos.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FixedCyberTerminalScreen(viewModel: CyberSecViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF010408))
    ) {
        // =================================================================
        // UPPER HALF: PREMIUM TACTICAL CAMERA VIEWPORT (Strict 55% Screen Height)
        // =================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f)
                .background(Color(0xFF03090F))
        ) {
            // Camera Stream sits isolated inside this upper zone
            // Box(Modifier.fillMaxSize()) // Raw CameraX Preview Hook Here

            // --- BULLETPROOF MATRIX HEADER BLOCK ---
            // A solid backdrop that completely isolates metrics and prevents vertical overlapping
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF010408)) // Solid mask to fully black out background noise
                    .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 12.dp)
            ) {
                // Simulated Bottom Border
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF00FF66).copy(alpha = 0.2f)))
                Spacer(modifier = Modifier.height(8.dp))

                // Row 1: Primary System Title String
                Text(
                    text = "PANOPTICORE // SENSOR MATRIX",
                    color = Color(0xFF00FF66),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                
                // Explicit padding separation to force line clearance
                Spacer(modifier = Modifier.height(4.dp))
                
                // Row 2: Status Identifier String
                Text(
                    text = "SYS.STATUS // ACTIVE INTERCEPT",
                    color = Color(0xFF00E5FF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Row 3: Performance Telemetry (Grid Alignment)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    // FPS Block with predefined minimum horizontal constraint
                    Column(modifier = Modifier.width(100.dp)) {
                        Text(
                            text = "FPS: ${viewModel.currentFps}",
                            color = Color(0xFF00FF66).copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    // Inference Block completely isolated horizontally
                    Column {
                        Text(
                            text = "INF: ${viewModel.inferenceTimeMs}ms",
                            color = Color(0xFF00FF66).copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // --- INTERCEPTOR VECTOR HUD OVERLAYS ---
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val matrixGreen = Color(0xFF00FF66)
                val alertCyan = Color(0xFF00E5FF)

                // Perfect Crosshair Center Lock Ring
                drawCircle(color = matrixGreen.copy(alpha = 0.2f), radius = h * 0.3f, center = center, style = Stroke(1.5f))
                drawCircle(color = alertCyan, radius = 10f, center = center, style = Stroke(2f))

                // Tactical Scoping Corner Notches
                val edge = 40f
                val size = 25f
                // Top-Left Corner Vector
                drawLine(alertCyan, Offset(edge, edge), Offset(edge + size, edge), strokeWidth = 2f)
                drawLine(alertCyan, Offset(edge, edge), Offset(edge, edge + size), strokeWidth = 2f)
                // Top-Right Corner Vector
                drawLine(alertCyan, Offset(w - edge, edge), Offset(w - edge - size, edge), strokeWidth = 2f)
                drawLine(alertCyan, Offset(w - edge, edge), Offset(w - edge, edge + size), strokeWidth = 2f)
            }
        }

        // =================================================================
        // LOWER HALF: INTERACTIVE TERMINAL MANAGEMENT CONTROL CONSOLE (45% Height)
        // =================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
                .background(Color(0xFF050C14))
        ) {
            // Top Border for Control Console
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF00FF66).copy(alpha = 0.2f)))

            // Interactive Tab Control Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Color(0xFF02060B))
            ) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF050C14)), contentAlignment = Alignment.Center) {
                    Text("GEOLOG PANEL", color = Color(0xFF00FF66), fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("GPS TACTICAL MAP", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("TOOLS CLOSE", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }

            // Command Control Sub-Modules
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Section 1: Sensors Switches
                Column {
                    Text("TRACKING + SENSOR OVERLAYS", color = Color(0xFFFFA500), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        TerminalButtonFrame("MOTION ARRAY\nON", active = true, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(8.dp))
                        TerminalButtonFrame("AUTO TARGET\nLOCK", active = true, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(8.dp))
                        TerminalButtonFrame("RADAR SWEEP\nON", active = true, modifier = Modifier.weight(1f))
                    }
                }

                // Section 2: Render Selection Modules
                Column {
                    Text("VISUAL RENDER + TARGET VIEW", color = Color(0xFFFFA500), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        TerminalButtonFrame("RENDER\nCLASSIC", active = false, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(8.dp))
                        TerminalButtonFrame("TARGET\nBIG", active = true, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(8.dp))
                        TerminalButtonFrame("TARGET\nNORMAL", active = false, modifier = Modifier.weight(1f))
                    }
                }

                // Section 3: Hardware Precision Adjustment
                Column {
                    Text("DIGITAL CAMERA ZOOM: ${viewModel.digitalZoom.toInt()}", color = Color(0xFF00FF66), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Slider(
                        value = viewModel.digitalZoom,
                        onValueChange = { viewModel.digitalZoom = it },
                        valueRange = 1f..16f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFF00FF66),
                            inactiveTrackColor = Color(0xFF222222)
                        ),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TerminalButtonFrame(text: String, active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(44.dp)
            .background(if (active) Color(0xFF06231A) else Color(0xFF111111))
            .border(1.dp, if (active) Color(0xFF00FF66) else Color.Gray.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) Color(0xFF00FF66) else Color.Gray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 13.sp
        )
    }
}
