package com.minos.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.compose.ui.geometry.Offset

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
