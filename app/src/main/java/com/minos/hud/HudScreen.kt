package com.minos.hud

import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
    cameraInfo: CameraInfo?,
    previewView: PreviewView?,
    onPreviewViewCreated: (PreviewView) -> Unit,
    onHudOverlayCreated: (HUDOverlayView) -> Unit,
    onReleaseTarget: () -> Unit,
    onLogsClick: () -> Unit
) {
    val expState = cameraInfo?.exposureState
    val isExpSupported = expState?.isExposureCompensationSupported == true
    val expRange = expState?.exposureCompensationRange
    val minExpIndex = expRange?.lower ?: -4
    val maxExpIndex = expRange?.upper ?: 4

    LaunchedEffect(cameraControl, viewModel.isTorchEnabled) {
        if (cameraControl != null) {
            try {
                cameraControl.enableTorch(viewModel.isTorchEnabled)
            } catch (e: Exception) {
                Log.e("MainContent", "Error enabling torch", e)
            }
        }
    }

    LaunchedEffect(cameraControl, viewModel.exposureValue) {
        if (cameraControl != null && isExpSupported) {
            try {
                val index = viewModel.exposureValue.toInt().coerceIn(minExpIndex, maxExpIndex)
                cameraControl.setExposureCompensationIndex(index)
            } catch (e: Exception) {
                Log.e("MainContent", "Error setting exposure index", e)
            }
        }
    }

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
                    it.activeProfile = viewModel.currentProfile
                    it.onTargetLocked = { locked -> viewModel.isTargetLocked = locked }
                    it.onTapFocus = { x, y ->
                        previewView?.let { pv ->
                            val factory = pv.meteringPointFactory
                            val point = factory.createPoint(x, y)
                            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
                            cameraControl?.startFocusAndMetering(action)
                        }
                    }
                    onHudOverlayCreated(it)
                }
            },
            update = {
                it.isYoloBoxesEnabled = viewModel.isYoloBoxesEnabled
                it.sensitivityThreshold = viewModel.sensitivityThreshold
                it.activeProfile = viewModel.currentProfile
            },
            modifier = Modifier.fillMaxSize()
        )

        if (viewModel.isTargetLocked) {
            Button(
                onClick = { 
                    viewModel.isTargetLocked = false
                    onReleaseTarget()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xAAFF0033)),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
            ) {
                Text("RELEASE TARGET", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }

        CaptureHUD(
            viewModel = viewModel,
            cameraControl = cameraControl,
            cameraInfo = cameraInfo,
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
    cameraInfo: CameraInfo? = null,
    fpsProvider: () -> Int = { viewModel.fpsValue },
    inferenceProvider: () -> Long = { viewModel.inferenceValue },
    onLogsClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(start = 12.dp, end = 12.dp, top = 40.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MINOS HUD",
                color = Color(0xFF00FF9D),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(end = 12.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IsolatedTelemetryDisplay(
                    fpsProvider = fpsProvider,
                    inferenceProvider = inferenceProvider
                )
                Button(
                    onClick = onLogsClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111111)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF00FF9D)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("LOGS ${EventRepository.events.size}", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                }
                Button(
                    onClick = onSettingsClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111111)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.Gray),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("SET", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                }
            }
        }

        val targetCount = viewModel.trackedTargets.size
        val targetWord = if (targetCount == 1) "TARGET" else "TARGETS"
        val statusText = if (viewModel.modelLoadError != null) {
            "MODEL ERROR: ${viewModel.modelLoadError}"
        } else {
            "LIVE  •  $targetCount $targetWord  •  ${if (viewModel.isScanning) "Scanning" else "Paused"}"
        }
        val statusColor = if (viewModel.modelLoadError != null) Color(0xFFFF3333) else Color(0xFF00A8FF)

        Text(
            text = statusText,
            color = statusColor,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(horizontal = 16.dp, vertical = 2.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Feature 2: Tap-to-Lock Status Banner
        if (viewModel.isTargetLocked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(Color(0xCCFF0033), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TARGET LOCKED: ${viewModel.lockedTrackId ?: "ACTIVE"}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "PLATE & EVENT FILTER ACTIVE",
                    color = Color.Yellow,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Feature 1: Personal Tracking Profile Selector
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PROFILE: ${viewModel.selectedProfile.displayName.uppercase()}",
                    color = Color(0xFF00FF9D),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                val motionStatus = when {
                    viewModel.inferenceValue > 110 -> "WEAK"
                    viewModel.fpsValue in 1..11 -> "LOW FPS"
                    else -> "OPTIMAL"
                }
                Text(text = "SYS: $motionStatus", color = if (motionStatus == "OPTIMAL") Color(0xFF00FF9D) else if (motionStatus == "WEAK") Color(0xFFFFB300) else Color(0xFFFF6B00), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TrackingProfile.values().forEach { profile ->
                    val shortName = when (profile) {
                        TrackingProfile.VEHICLE -> "VEH"
                        TrackingProfile.PEOPLE -> "PEOPLE"
                        TrackingProfile.INDOOR -> "INDOOR"
                        TrackingProfile.OUTDOOR -> "OUTDOOR"
                        TrackingProfile.MOVING -> "MOVE"
                    }
                    ProfileButton(
                        text = shortName,
                        active = viewModel.selectedProfile == profile
                    ) {
                        viewModel.setProfile(profile)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Feature 3: Quality versus Speed Preset Switch
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MODE: ${viewModel.qualityPreset.displayName.uppercase()} (${viewModel.qualityPreset.modelInputSize}x${viewModel.qualityPreset.modelInputSize})",
                    color = Color(0xFF00E5FF),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QualitySpeedPreset.values().forEach { preset ->
                    Button(
                        onClick = { viewModel.setPreset(preset) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (viewModel.qualityPreset == preset) Color(0xFF062332) else Color(0xAA000000)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (viewModel.qualityPreset == preset) Color(0xFF00E5FF) else Color.Gray),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = preset.displayName.uppercase(),
                            color = if (viewModel.qualityPreset == preset) Color(0xFF00E5FF) else Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF050C14))
                .padding(16.dp)
        ) {
            // Feature 1: Torch Suggestion for Indoor / Low Light
            if (viewModel.isTorchSuggested) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x33D4AF37), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "TORCH SUGGESTED FOR INDOOR / LOW LIGHT",
                        color = Color(0xFFD4AF37),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            viewModel.isTorchEnabled = true
                            cameraControl?.enableTorch(true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("ENABLE TORCH", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            val expState = cameraInfo?.exposureState
            val isExpSupported = expState?.isExposureCompensationSupported == true
            val expRange = expState?.exposureCompensationRange
            val minExp = expRange?.lower?.toFloat() ?: -4f
            val maxExp = expRange?.upper?.toFloat() ?: 4f
            val expStep = expState?.exposureCompensationStep?.toFloat() ?: 0.333f

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        viewModel.isTorchEnabled = !viewModel.isTorchEnabled
                        try {
                            cameraControl?.enableTorch(viewModel.isTorchEnabled)
                        } catch (e: Exception) {
                            Log.e("CaptureHUD", "Failed to toggle torch", e)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (viewModel.isTorchEnabled) Color(0xFF00FF9D) else Color(0xAA000000)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, if (viewModel.isTorchEnabled) Color(0xFF00FF9D) else Color.Gray),
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
                
                val currentExpIdx = viewModel.exposureValue.toInt().coerceIn(minExp.toInt(), maxExp.toInt())
                val evVal = currentExpIdx * expStep
                val expText = if (isExpSupported) {
                    if (evVal >= 0f) "+${String.format("%.1f", evVal)} EV" else "${String.format("%.1f", evVal)} EV"
                } else {
                    "EXP ${currentExpIdx}"
                }

                Text(
                    text = expText,
                    color = if (isExpSupported) Color(0xFF00FF9D) else Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = viewModel.exposureValue.coerceIn(minExp, maxExp),
                    onValueChange = {
                        viewModel.exposureValue = it
                        if (isExpSupported) {
                            val targetIndex = it.toInt().coerceIn(minExp.toInt(), maxExp.toInt())
                            try {
                                cameraControl?.setExposureCompensationIndex(targetIndex)
                            } catch (e: Exception) {
                                Log.e("CaptureHUD", "Failed to set exposure index", e)
                            }
                        }
                    },
                    valueRange = if (minExp < maxExp) minExp..maxExp else -4f..4f,
                    enabled = isExpSupported || cameraControl != null,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00FF9D),
                        activeTrackColor = Color(0xFF00FF9D)
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            val savedCount = EventRepository.events.size
            val savedWord = if (savedCount == 1) "SAVED" else "SAVED"
            Text(
                text = "$targetCount $targetWord  •  $savedCount $savedWord  •  BEST-FRAME AUTO CAPTURE",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.isCaptureOn = !viewModel.isCaptureOn },
                    colors = ButtonDefaults.buttonColors(containerColor = if (viewModel.isCaptureOn) Color(0xFF008544) else Color(0xFF333333)),
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, if (viewModel.isCaptureOn) Color(0xFF00FF9D) else Color.Gray),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (viewModel.isCaptureOn) "CAPTURE ON" else "CAPTURE OFF",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Button(
                    onClick = { viewModel.isScanning = !viewModel.isScanning },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.Gray),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (viewModel.isScanning) "PAUSE SCAN" else "RESUME SCAN",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Button(
                    onClick = onLogsClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.Gray),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        "LOGS ${EventRepository.events.size}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        fontFamily = FontFamily.Monospace
                    )
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
        colors = ButtonDefaults.buttonColors(containerColor = if (active) Color(0xFF06231A) else Color(0xAA000000)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (active) Color(0xFF00FF9D) else Color.Gray),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = if (active) Color(0xFF00FF9D) else Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun SettingsContent(
    viewModel: MainViewModel,
    onClose: () -> Unit
) {
    val profile = viewModel.selectedProfile
    val preset = viewModel.qualityPreset

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Text(
            "SYSTEM CONFIGURATION & CALIBRATION",
            color = Color(0xFF00E5FF),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Profile Parameters Display
        Text(
            "ACTIVE PROFILE PARAMETERS (${profile.displayName})",
            color = Color(0xFF00FF9D),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A1926), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Text("• Model Input Size: ${profile.modelInputSize}x${profile.modelInputSize}", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("• Confidence Threshold: ${(profile.confidenceThreshold * 100).toInt()}%", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("• Plate Scan Interval: ${profile.plateScanIntervalMs}ms", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("• Box Smoothing Factor: ${String.format("%.2f", profile.boxSmoothingAlpha)}", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("• Capture Cooldown: ${profile.captureCooldownMs / 1000}s", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("• Crop Padding: ${(profile.cropPaddingFraction * 100).toInt()}%", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("• Plate Detector Enabled: ${if (profile.isPlateDetectorEnabled) "YES" else "NO"}", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Quality / Speed Switch Parameters
        Text(
            "QUALITY VS SPEED PRESET (${preset.displayName})",
            color = Color(0xFF00E5FF),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A1926), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Text("• Main Detector: ${preset.modelInputSize}x${preset.modelInputSize}", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("• Scan Rate: ${preset.plateScanIntervalMs}ms", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("• Min Target Crop: ${preset.minCropWidth}x${preset.minCropHeight}px", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("• Min Sharpness Score: ${String.format("%.2f", preset.minSharpnessScore)}", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("• Required Observations: ${preset.minStableFrames} consecutive frames", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SENSITIVITY OVERRIDE",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(viewModel.sensitivityThreshold * 100).toInt()}%",
                color = Color(0xFF00FF66),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
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

        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "RENDER BOUNDING BOXES",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
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

        Spacer(modifier = Modifier.height(12.dp))
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
