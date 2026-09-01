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
    val coordinateLabel: String,
    // Relative coordinates (0f to 1f)
    val relX: Float,
    val relY: Float,
    val crop: Bitmap? = null
)

data class YoloTarget(
    val id: String,
    val label: String,
    val confidence: Float,
    // Normalized coordinates (0.0f to 1.0f) relative to screen space
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
    var motionSensitivity by mutableStateOf(36f)
    var activePanel by mutableStateOf("GEOLOG")
    
    // Performance Specs 
    var currentFps by mutableStateOf(6)
    var inferenceTimeMs by mutableStateOf(176)

    var detectedObjects by mutableStateOf(listOf<DynamicYoloBox>())
}
