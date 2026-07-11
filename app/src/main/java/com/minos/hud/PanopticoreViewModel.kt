package com.minos.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.compose.ui.geometry.Offset
import android.graphics.Bitmap

data class TrackedTarget(
    val id: String,
    val label: String,
    val confidence: Float,
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
    val anchor: Offset,
    val infoTag: String,
    val crop: Bitmap? = null
)

enum class HudPanel {
    GEOLOG, GPS, TOOLS
}

enum class RenderMode {
    CLASSIC, BIG, NORMAL
}

class PanopticoreViewModel : ViewModel() {
    // System State
    var digitalZoom by mutableStateOf(1f)
    var motionSensitivity by mutableStateOf(0.5f)
    var activePanel by mutableStateOf(HudPanel.GEOLOG)
    var renderMode by mutableStateOf(RenderMode.NORMAL)
    
    // Toggles
    var motionArrayEnabled by mutableStateOf(true)
    var autoTargetLock by mutableStateOf(true)
    var radarSweepEnabled by mutableStateOf(true)
    
    // Metrics
    var currentFps by mutableStateOf(0)
    var inferenceTimeMs by mutableStateOf(0L)
    
    // Targets
    val trackedTargets = mutableStateListOf<TrackedTarget>()

    fun updateMetrics(fps: Int, inference: Long) {
        currentFps = fps
        inferenceTimeMs = inference
    }

    fun updateTargets(newTargets: List<TrackedTarget>) {
        trackedTargets.clear()
        trackedTargets.addAll(newTargets)
    }
}
