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
    var sensitivityThreshold by mutableStateOf(0.30f)
    var showDossier by mutableStateOf(false)
    var isYoloBoxesEnabled by mutableStateOf(true)
    var maxDetections by mutableStateOf(15)
    var autoMag by mutableStateOf(false)
    var selectedTarget by mutableStateOf<MagTrackTarget?>(null)
    var currentScreen by mutableStateOf(Screen.HUD)

    var isCaptureOn by mutableStateOf(true)
    var isCleanView by mutableStateOf(false)
    var selectedProfile by mutableStateOf(TrackingProfile.OUTDOOR)
    var currentProfile by mutableStateOf("OUTDOOR")
    var qualityPreset by mutableStateOf(QualitySpeedPreset.BALANCED)
    var detectionMode by mutableStateOf(DetectionMode.ALL)
    var exposureValue by mutableFloatStateOf(0f)
    var isTargetLocked by mutableStateOf(false)
    var lockedTrackId by mutableStateOf<String?>(null)
    var isTorchEnabled by mutableStateOf(false)
    var modelLoadError by mutableStateOf<String?>(null)

    val isTorchSuggested: Boolean
        get() = selectedProfile.suggestTorch && !isTorchEnabled

    val trackedTargets = mutableStateListOf<MagTrackTarget>()

    fun setProfile(profile: TrackingProfile) {
        selectedProfile = profile
        currentProfile = profile.name
        sensitivityThreshold = profile.confidenceThreshold
        detectionMode = profile.detectionMode
    }

    fun setPreset(preset: QualitySpeedPreset) {
        qualityPreset = preset
    }

    fun lockTarget(trackId: String?) {
        lockedTrackId = trackId
        isTargetLocked = trackId != null
    }

    fun releaseLock() {
        lockedTrackId = null
        isTargetLocked = false
    }

    fun updateMetrics(fps: Int, inferenceMs: Long) {
        fpsValue = fps
        inferenceValue = inferenceMs
    }

    fun updateTrackedTargets(newTargets: List<MagTrackTarget>) {
        trackedTargets.clear()
        trackedTargets.addAll(newTargets)
    }
}
