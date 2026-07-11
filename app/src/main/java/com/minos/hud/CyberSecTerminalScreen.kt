package com.minos.hud

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minos.hud.CyberSecPalette as SecColor

@Composable
fun CyberSecTerminalScreen(viewModel: CyberSecViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val context = LocalContext.current
    
    // Initialize the sound system
    val soundManager = remember { CyberSecSoundManager(context) }
    
    DisposableEffect(Unit) {
        onDispose {
            soundManager.release()
        }
    }

    // --- ANIMATION SPECIFICATIONS ---
    val infiniteTransition = rememberInfiniteTransition(label = "terminal_loops")
    
    // 1. Radar Scanning Grid Sweep
    val gridScanLine by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3500, easing = LinearEasing), RepeatMode.Restart),
        label = "scanner"
    )

    // 2. High-Frequency Cyber Glitch Pulse for Breach Alerts
    val alertAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alert_pulse"
    )

    // Trigger radar sounds periodically relative to the sweep line position
    LaunchedEffect(gridScanLine) {
        if (gridScanLine > 0.48f && gridScanLine < 0.52f) {
            soundManager.playScan()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(SecColor.TerminalBlack)) {
        
        // --- 1. LIVE HUD CANVAS LAYER ---
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Render Animated Scanner Bar
            val scanY = gridScanLine * h
            drawLine(
                color = SecColor.CyberNetGreen.copy(alpha = 0.4f),
                start = Offset(0f, scanY),
                end = Offset(w, scanY),
                strokeWidth = 3f
            )

            // Dynamic Target Vectors
            viewModel.trackedTargets.forEach { target ->
                val left = target.xMin * w
                val top = target.yMin * h
                val boxW = (target.xMax - target.xMin) * w
                val boxH = (target.yMax - target.yMin) * h
                
                // Switch alpha based on severity
                val calculatedAlpha = if (target.threatClassification == "BREACH") alertAlpha else 0.8f
                val stateColor = when (target.threatClassification) {
                    "BREACH" -> SecColor.FirewallRed.copy(alpha = calculatedAlpha)
                    "SUSPECTED" -> SecColor.AlertAmber
                    else -> SecColor.CyberNetGreen
                }

                // Dynamic Expanding Target Corners
                val len = 30f
                val thick = 3f
                
                // Top-Left Dynamic Bracket
                drawPath(Path().apply {
                    moveTo(left, top + len)
                    lineTo(left, top)
                    lineTo(left + len, top)
                }, color = stateColor, style = Stroke(thick))
                
                // Bottom-Right Dynamic Bracket
                drawPath(Path().apply {
                    moveTo(left + boxW - len, top + boxH)
                    lineTo(left + boxW, top + boxH)
                    lineTo(left + boxW, top + boxH - len)
                }, color = stateColor, style = Stroke(thick))
            }
        }

        // --- 2. HEADER READOUT SYSTEMS ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SecColor.TerminalBlack.copy(alpha = 0.85f))
                .padding(start = 16.dp, top = 32.dp, end = 16.dp, bottom = 12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SEC_OPS // TERMINAL_ACTIVE", color = SecColor.CyberNetGreen, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                
                // Animate alpha on the alert indicator if breach exists
                val extremeThreat = viewModel.trackedTargets.any { it.threatClassification == "BREACH" }
                Box(
                    modifier = Modifier
                        .background(SecColor.FirewallRed.copy(alpha = if(extremeThreat) alertAlpha * 0.3f else 0.1f))
                        .border(1.dp, SecColor.FirewallRed.copy(alpha = if(extremeThreat) alertAlpha else 1f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("THREAT ALARM", color = SecColor.FirewallRed, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- 3. BOTTOM DECK WITH AUDIO TRIGGER HOOKS ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(SecColor.DeepScannerGrid.copy(alpha = 0.95f))
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(45.dp).background(SecColor.TerminalBlack)) {
                val tabs = listOf("PACKET_DECK" to "PACKET SNIFFER", "PORT_SCAN" to "PORT MAPPER")
                tabs.forEach { (key, label) ->
                    val active = viewModel.activeModule == key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { 
                                soundManager.playClick() // Audio confirmation
                                viewModel.activeModule = key 
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (active) SecColor.CyberNetGreen else Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        if (active) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(SecColor.CyberNetGreen)
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("MITIGATION DIRECTIVES", color = SecColor.AlertAmber, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    
                    // Danger mitigation action triggers instant alarm siren cascade
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(45.dp)
                            .background(SecColor.TerminalBlack)
                            .border(1.dp, SecColor.FirewallRed)
                            .clickable { 
                                soundManager.playAlarm() 
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("ISOLATE NETWORK", color = SecColor.FirewallRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(45.dp)
                            .background(SecColor.TerminalBlack)
                            .border(1.dp, SecColor.CyberNetGreen)
                            .clickable { 
                                soundManager.playClick() 
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("DECRYPT NODE", color = SecColor.CyberNetGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
