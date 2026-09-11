package com.minos.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

enum class Screen { HUD, TACTICAL_MAP, EVENT_LOG }

class MainViewModel : ViewModel() {

    var isScanning by mutableStateOf(true)
    var fpsValue by mutableStateOf(0)
    var inferenceValue by mutableStateOf(0L)
    var activePanel by mutableStateOf("GEOLOG")
    var panelVisible by mutableStateOf(false)
    var motionArrayOn by mutableStateOf(true)
    var autoTargetLock by mutableStateOf(true)
    var digitalZoom by mutableStateOf(1.0f)
    var sensitivityThreshold by mutableStateOf(0.15f)
    var showDossier by mutableStateOf(false)
    var isYoloBoxesEnabled by mutableStateOf(true)
    var maxDetections by mutableStateOf(15)
    var autoMag by mutableStateOf(false)
    var selectedTarget by mutableStateOf<MagTrackTarget?>(null)
    var currentScreen by mutableStateOf(Screen.HUD)

    var isCaptureOn by mutableStateOf(true)
    var currentProfile by mutableStateOf("OUTDOOR")
    var exposureValue by mutableFloatStateOf(0f)
    var isTorchEnabled by mutableStateOf(false)

    val trackedTargets = mutableStateListOf<MagTrackTarget>()

    fun updateMetrics(fps: Int, inferenceMs: Long) {
        fpsValue = fps
        inferenceValue = inferenceMs
    }

    fun updateTrackedTargets(newTargets: List<MagTrackTarget>) {
        trackedTargets.clear()
        trackedTargets.addAll(newTargets)
    }
}
