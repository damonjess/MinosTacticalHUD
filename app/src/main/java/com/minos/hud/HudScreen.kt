package com.minos.hud

import androidx.camera.core.CameraControl
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun MainContent(
    viewModel: MainViewModel,
    cameraControl: CameraControl?,
    previewView: PreviewView?,
    onPreviewViewCreated: (PreviewView) -> Unit,
    onHudOverlayCreated: (HUDOverlayView) -> Unit,
    onLogsClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    onPreviewViewCreated(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        AndroidView(
            factory = { ctx ->
                HUDOverlayView(ctx, null).also {
                    it.magTrackTargets = viewModel.trackedTargets
                    it.isYoloBoxesEnabled = viewModel.isYoloBoxesEnabled
                    it.sensitivityThreshold = viewModel.sensitivityThreshold
                    onHudOverlayCreated(it)
                }
            },
            update = {
                it.isYoloBoxesEnabled = viewModel.isYoloBoxesEnabled
                it.sensitivityThreshold = viewModel.sensitivityThreshold
            },
            modifier = Modifier.fillMaxSize()
        )

        CaptureHUD(
            viewModel = viewModel,
            cameraControl = cameraControl,
            fpsProvider = { viewModel.fpsValue },
            inferenceProvider = { viewModel.inferenceValue },
            onLogsClick = onLogsClick,
            onSettingsClick = {
                viewModel.activePanel = "SETTINGS"
                viewModel.panelVisible = true
            }
        )

        if (viewModel.showDossier) {
            DossierOverlay(viewModel = viewModel)
        }

        if (viewModel.panelVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF030708))
                    .clickable { viewModel.panelVisible = false }
            ) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.9f)
                        .wrapContentHeight(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF05101A)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF00FF9D))
                ) {
                    SettingsContent(
                        viewModel = viewModel,
                        onClose = { viewModel.panelVisible = false }
                    )
                }
            }
        }

        viewModel.selectedTarget?.let { target ->
            LargeTargetViewer(
                target = target,
                onClose = { viewModel.selectedTarget = null }
            )
        }
    }
}

@Composable
fun CaptureHUD(
    viewModel: MainViewModel,
    cameraControl: CameraControl?,
    fpsProvider: () -> Int = { viewModel.fpsValue },
    inferenceProvider: () -> Long = { viewModel.inferenceValue },
    onLogsClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MINOS HUD",
                color = Color(0xFF00FF9D),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IsolatedTelemetryDisplay(
                    fpsProvider = fpsProvider,
                    inferenceProvider = inferenceProvider
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onLogsClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111111)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF00FF9D))
                ) {
                    Text("LOGS ${EventRepository.events.size}", color = Color.White, fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onSettingsClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111111)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.Gray)
                ) {
                    Text("SET", color = Color.White, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Text(
            text = "LIVE  •  ${viewModel.trackedTargets.size} TARGETS  •  ${if (viewModel.isScanning) "Scanning" else "Paused"}",
            color = Color(0xFF00A8FF),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(text = "PROFILE  •  ${viewModel.currentProfile}", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(6.dp))
            Row {
                ProfileButton("INDOOR", viewModel.currentProfile == "INDOOR") { viewModel.currentProfile = "INDOOR" }
                Spacer(modifier = Modifier.width(8.dp))
                ProfileButton("OUTDOOR", viewModel.currentProfile == "OUTDOOR") { viewModel.currentProfile = "OUTDOOR" }
                Spacer(modifier = Modifier.width(8.dp))
                ProfileButton("MOVING", viewModel.currentProfile == "MOVING") { viewModel.currentProfile = "MOVING" }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF050C14))
                .padding(16.dp)
        ) {
            Text(
                text = "LOW LIGHT — ENABLE TORCH",
                color = Color(0xFFD4AF37),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        viewModel.isTorchEnabled = !viewModel.isTorchEnabled
                        cameraControl?.enableTorch(viewModel.isTorchEnabled)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (viewModel.isTorchEnabled) Color(0xFF00FF9D) else Color.Transparent),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.Gray),
                    modifier = Modifier.width(100.dp)
                ) {
                    Text("TORCH", color = if (viewModel.isTorchEnabled) Color.Black else Color.White)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "${String.format("%.1f", viewModel.digitalZoom)}x",
                    color = Color(0xFF00FF9D),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "EXP ${viewModel.exposureValue.toInt()}",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = viewModel.exposureValue,
                    onValueChange = {
                        viewModel.exposureValue = it
                        cameraControl?.setExposureCompensationIndex(it.toInt())
                    },
                    valueRange = -4f..4f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF00FF9D), activeTrackColor = Color(0xFF00FF9D))
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "${viewModel.trackedTargets.size} TARGETS  •  ${EventRepository.events.size} SAVED  •  CAPTURE ${if (viewModel.isCaptureOn) "ON" else "OFF"}",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.isCaptureOn = !viewModel.isCaptureOn },
                    colors = ButtonDefaults.buttonColors(containerColor = if (viewModel.isCaptureOn) Color(0xFF008544) else Color(0xFF333333)),
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    border = BorderStroke(1.dp, if (viewModel.isCaptureOn) Color(0xFF00FF9D) else Color.Gray)
                ) {
                    Text(if (viewModel.isCaptureOn) "CAPTURE ON" else "CAPTURE OFF", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.isScanning = !viewModel.isScanning },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    border = BorderStroke(1.dp, Color.Gray)
                ) {
                    Text(if (viewModel.isScanning) "PAUSE" else "RESUME", color = Color.White, fontSize = 15.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onLogsClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    border = BorderStroke(1.dp, Color.Gray)
                ) {
                    Text("LOGS (${EventRepository.events.size})", color = Color.White, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
fun IsolatedTelemetryDisplay(
    fpsProvider: () -> Int,
    inferenceProvider: () -> Long,
    modifier: Modifier = Modifier
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            text = "FPS:${fpsProvider()}",
            color = Color(0xFF00FF9D),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "INF:${inferenceProvider()}ms",
            color = Color(0xFF00FF9D),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun ProfileButton(text: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = if (active) Color(0xFF06231A) else Color.Transparent),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (active) Color(0xFF00FF9D) else Color.Gray),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = if (active) Color(0xFF00FF9D) else Color.Gray)
    }
}

@Composable
fun SettingsContent(
    viewModel: MainViewModel,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Text(
            "SYSTEM SENSITIVITY // SCANNER CALIBRATION",
            color = Color(0xFF00E5FF),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SCANNER SENSITIVITY",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(viewModel.sensitivityThreshold * 100).toInt()}%",
                color = Color(0xFF00FF66),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
        Slider(
            value = viewModel.sensitivityThreshold,
            onValueChange = { viewModel.sensitivityThreshold = it },
            valueRange = 0.05f..0.95f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF00FF66),
                activeTrackColor = Color(0xFF00FF66),
                inactiveTrackColor = Color(0xFF00FF66).copy(alpha = 0.2f)
            )
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "RENDER TARGET BOUNDING BOXES",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = viewModel.isYoloBoxesEnabled,
                onCheckedChange = { viewModel.isYoloBoxesEnabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF00FF66),
                    checkedTrackColor = Color(0xFF00FF66).copy(alpha = 0.5f)
                )
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF008544)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("APPLY & CLOSE", color = Color.White, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun LargeTargetViewer(target: MagTrackTarget, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050C14))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, Color(0xFF00FF66))
                .background(Color(0xFF030708))
                .padding(16.dp)
        ) {
            Text(
                "PANOPTICORE // TARGET VIEWER",
                color = Color(0xFF00FF66),
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .border(1.dp, Color(0xFFFFA500).copy(alpha = 0.5f))
            ) {
                target.crop?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            TerminalActionButton(
                text = "CLOSE VIEWER",
                active = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = onClose
            )
        }
    }
}

@Composable
fun DossierOverlay(viewModel: MainViewModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05080E))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        TerminalActionButton(
            text = "INITIALIZE NEW SCAN SEQUENCE",
            active = true,
            modifier = Modifier.fillMaxWidth()
        ) {
            viewModel.showDossier = false
            viewModel.isScanning = true
        }
    }
}

@Composable
fun TacticalHudTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00FF66),
            background = Color(0xFF030708),
            surface = Color(0xFF050C14)
        ),
        content = content
    )
}
