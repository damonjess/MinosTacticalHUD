package com.minos.hud

import androidx.camera.view.PreviewView
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun PanopticoreScreen(viewModel: PanopticoreViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    Box(modifier = Modifier.fillMaxSize().background(PanopticoreColors.Background)) {
        // 1. Camera Layer
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Tactical Overlay Canvas
        TacticalCanvas(viewModel)

        // 3. Telemetry Header
        TelemetryHeader(viewModel)

        // 4. Control Deck
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            ControlDeck(viewModel)
        }
    }
}

@Composable
fun TacticalCanvas(viewModel: PanopticoreViewModel) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val radarRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarRotation"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        
        // Draw Frame Brackets
        val bracketSize = 40f
        val thickness = 4f
        val color = PanopticoreColors.CyberCyan
        
        // Top Left
        drawLine(color, Offset(20f, 20f), Offset(20f + bracketSize, 20f), thickness)
        drawLine(color, Offset(20f, 20f), Offset(20f, 20f + bracketSize), thickness)
        // Top Right
        drawLine(color, Offset(w - 20f, 20f), Offset(w - 20f - bracketSize, 20f), thickness)
        drawLine(color, Offset(w - 20f, 20f), Offset(w - 20f, 20f + bracketSize), thickness)
        // Bottom Left
        drawLine(color, Offset(20f, h - 20f), Offset(20f + bracketSize, h - 20f), thickness)
        drawLine(color, Offset(20f, h - 20f), Offset(20f, h - 20f - bracketSize), thickness)
        // Bottom Right
        drawLine(color, Offset(w - 20f, h - 20f), Offset(w - 20f - bracketSize, h - 20f), thickness)
        drawLine(color, Offset(w - 20f, h - 20f), Offset(w - 20f, h - 20f - bracketSize), thickness)

        // Draw Radar
        if (viewModel.radarSweepEnabled) {
            val radarRadius = 80f
            val radarCenter = Offset(w - 120f, 150f)
            drawCircle(color.copy(alpha = 0.2f), radarRadius, radarCenter, style = Stroke(2f))
            rotate(radarRotation, radarCenter) {
                drawLine(color, radarCenter, Offset(radarCenter.x, radarCenter.y - radarRadius), 3f)
            }
        }

        // Draw Targets
        viewModel.trackedTargets.forEach { target ->
            val rectWidth = (target.xMax - target.xMin) * w
            val rectHeight = (target.yMax - target.yMin) * h
            val topLeft = Offset(target.xMin * w, target.yMin * h)
            
            // Bounding Box
            drawRect(
                color = PanopticoreColors.PrimaryGreen,
                topLeft = topLeft,
                size = Size(rectWidth, rectHeight),
                style = Stroke(2f)
            )
            
            // Crosshair
            val centerX = target.anchor.x * w
            val centerY = target.anchor.y * h
            val crossSize = 15f
            drawLine(PanopticoreColors.CyberCyan, Offset(centerX - crossSize, centerY), Offset(centerX + crossSize, centerY), 1.5f)
            drawLine(PanopticoreColors.CyberCyan, Offset(centerX, centerY - crossSize), Offset(centerX, centerY + crossSize), 1.5f)
        }
    }
}

@Composable
fun TelemetryHeader(viewModel: PanopticoreViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            "PANOPTICORE // SENSOR MATRIX",
            style = PanopticoreTypography.TechHeader,
            color = PanopticoreColors.PrimaryGreen
        )
        Text(
            "SYS.STATUS // ACTIVE INTERCEPT",
            style = PanopticoreTypography.TechBody,
            color = PanopticoreColors.CyberCyan,
            modifier = Modifier.padding(top = 4.dp)
        )
        
        Row(modifier = Modifier.padding(top = 12.dp)) {
            MetricBlock("FPS", viewModel.currentFps.toString())
            Spacer(modifier = Modifier.width(24.dp))
            MetricBlock("INF", "${viewModel.inferenceTimeMs}ms")
        }
    }
}

@Composable
fun MetricBlock(label: String, value: String) {
    Column {
        Text(label, style = PanopticoreTypography.TechBody, color = Color.Gray)
        Text(value, style = PanopticoreTypography.MetricValue)
    }
}

@Composable
fun ControlDeck(viewModel: PanopticoreViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanopticoreColors.Surface.copy(alpha = 0.9f))
    ) {
        // Tabs
        Row(modifier = Modifier.fillMaxWidth().height(50.dp)) {
            TabItem("GEOLOG PANEL", viewModel.activePanel == HudPanel.GEOLOG, Modifier.weight(1f)) {
                viewModel.activePanel = HudPanel.GEOLOG
            }
            TabItem("GPS TACTICAL MAP", viewModel.activePanel == HudPanel.GPS, Modifier.weight(1f)) {
                viewModel.activePanel = HudPanel.GPS
            }
            TabItem("TOOLS CLOSE", viewModel.activePanel == HudPanel.TOOLS, Modifier.weight(1f)) {
                viewModel.activePanel = HudPanel.TOOLS
            }
        }

        Divider(color = Color.DarkGray, thickness = 1.dp)

        // Content
        if (viewModel.activePanel == HudPanel.GEOLOG) {
            GeologPanel(viewModel)
        }
    }
}

@Composable
fun TabItem(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (active) PanopticoreColors.PrimaryGreen.copy(alpha = 0.1f) else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = PanopticoreTypography.TechBody,
            color = if (active) PanopticoreColors.PrimaryGreen else Color.Gray,
            textAlign = TextAlign.Center
        )
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(PanopticoreColors.PrimaryGreen)
            )
        }
    }
}

@Composable
fun GeologPanel(viewModel: PanopticoreViewModel) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("TRACKING + SENSOR OVERLAYS", style = PanopticoreTypography.TechHeader, color = PanopticoreColors.TacticalAmber)
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            TacticalButton("MOTION ARRAY", viewModel.motionArrayEnabled, Modifier.weight(1f)) {
                viewModel.motionArrayEnabled = !viewModel.motionArrayEnabled
            }
            Spacer(Modifier.width(8.dp))
            TacticalButton("AUTO TARGET", viewModel.autoTargetLock, Modifier.weight(1f)) {
                viewModel.autoTargetLock = !viewModel.autoTargetLock
            }
            Spacer(Modifier.width(8.dp))
            TacticalButton("RADAR SWEEP", viewModel.radarSweepEnabled, Modifier.weight(1f)) {
                viewModel.radarSweepEnabled = !viewModel.radarSweepEnabled
            }
        }

        Text("VISUAL RENDER + TARGET VIEW", style = PanopticoreTypography.TechHeader, color = PanopticoreColors.TacticalAmber, modifier = Modifier.padding(top = 8.dp))
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            TacticalButton("CLASSIC", viewModel.renderMode == RenderMode.CLASSIC, Modifier.weight(1f)) {
                viewModel.renderMode = RenderMode.CLASSIC
            }
            Spacer(Modifier.width(8.dp))
            TacticalButton("BIG", viewModel.renderMode == RenderMode.BIG, Modifier.weight(1f)) {
                viewModel.renderMode = RenderMode.BIG
            }
            Spacer(Modifier.width(8.dp))
            TacticalButton("NORMAL", viewModel.renderMode == RenderMode.NORMAL, Modifier.weight(1f)) {
                viewModel.renderMode = RenderMode.NORMAL
            }
        }

        Spacer(Modifier.height(16.dp))
        
        TacticalSlider("DIGITAL ZOOM", viewModel.digitalZoom, 1f..10f) { viewModel.digitalZoom = it }
        TacticalSlider("SENSITIVITY", viewModel.motionSensitivity, 0f..1f) { viewModel.motionSensitivity = it }
    }
}

@Composable
fun TacticalButton(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier
            .height(45.dp)
            .clickable { onClick() }
            .border(1.dp, if (active) PanopticoreColors.PrimaryGreen else Color.DarkGray),
        color = if (active) PanopticoreColors.PrimaryGreen.copy(alpha = 0.1f) else Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = PanopticoreTypography.TechBody,
                color = if (active) PanopticoreColors.PrimaryGreen else Color.White,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun TacticalSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = PanopticoreTypography.TechBody, color = PanopticoreColors.PrimaryGreen)
            Spacer(Modifier.weight(1f))
            Text(value.toString().take(4), style = PanopticoreTypography.MetricValue)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = PanopticoreColors.PrimaryGreen,
                inactiveTrackColor = Color.DarkGray
            )
        )
    }
}
