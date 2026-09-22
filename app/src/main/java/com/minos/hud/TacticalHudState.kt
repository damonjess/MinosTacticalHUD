package com.minos.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.compose.ui.geometry.Offset
import android.graphics.Bitmap

data class MagTrackTarget(
    val id: String,
    val trackLabel: String,
    val rawLabel: String = "",
    val coordinateLabel: String,
    val relX: Float,
    val relY: Float,
    val crop: Bitmap? = null,
    val xMin: Float = relX - 0.05f,
    val yMin: Float = relY - 0.05f,
    val xMax: Float = relX + 0.05f,
    val yMax: Float = relY + 0.05f,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val lastUpdateNs: Long = System.nanoTime(),
    val missedCount: Int = 0,
    val hitCount: Int = 1
)

data class YoloTarget(
    val id: String,
    val label: String,
    val rawLabel: String = "",
    val confidence: Float,
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
    val crop: Bitmap? = null
)

data class DynamicYoloBox(
    val label: String,
    val confidence: Float,
    val relativeAnchor: Offset,
    val xMin: Float, val yMin: Float, val xMax: Float, val yMax: Float,
    val infoTag: String
)

class TacticalHudViewModel : ViewModel() {
    var digitalZoom by mutableStateOf(8f)
    var motionSensitivity by mutableStateOf(0.45f)
    var activePanel by mutableStateOf("GEOLOG")
    var currentFps by mutableStateOf(0)
    var inferenceTimeMs by mutableStateOf(0)
    var detectedObjects by mutableStateOf(listOf<DynamicYoloBox>())
}
