package com.minos.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.compose.ui.geometry.Offset

data class SecurityTarget(
    val id: String,
    val threatClassification: String, // "CLEAN", "SUSPECTED", "BREACH"
    val macAddress: String,
    val portBinding: String,
    val relativeAnchor: Offset,
    val xMin: Float, val yMin: Float, val xMax: Float, val yMax: Float
)

class CyberSecViewModel : ViewModel() {
    var digitalZoom by mutableStateOf(1f)
    var threatThreshold by mutableStateOf(75f)
    var activeModule by mutableStateOf("PACKET_DECK")

    var currentFps by mutableStateOf(0)
    var inferenceTimeMs by mutableStateOf(0)
    var systemIntegrity = "99.2%"
    
    // Live security target arrays mapped over camera objects
    var trackedTargets by mutableStateOf(
        listOf(
            SecurityTarget("NODE-01", "SUSPECTED", "00:1A:2B:3C:4D:5E", "PORT 443", Offset(0.35f, 0.40f), 0.15f, 0.35f, 0.60f, 0.44f),
            SecurityTarget("NODE-02", "CLEAN", "7C:8B:9A:0F:1E:2D", "PORT 80", Offset(0.78f, 0.45f), 0.58f, 0.38f, 0.98f, 0.52f),
            SecurityTarget("NODE-03", "BREACH", "FF:FF:FF:FF:FF:FF", "PORT 22", Offset(0.12f, 0.48f), 0.02f, 0.42f, 0.22f, 0.56f)
        )
    )
}
