package com.minos.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun IsolatedHeaderTerminalBlock(
    currentFps: Int,
    inferenceTimeMs: Int,
    onViewMapClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    // A completely opaque, solid block that forces any background noise or camera feeds to stay behind the text
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF010408)) // Dark void color to absorb background artifacts
            .statusBarsPadding() // Seamlessly fills the status bar area
            .padding(
                start = 20.dp, 
                top = 16.dp, // Reduced top padding since statusBarsPadding handles the bar
                end = 20.dp, 
                bottom = 16.dp
            )
    ) {
        // Line 1: Primary System Monospace Title + Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PANOPTICORE // SENSOR MATRIX",
                color = Color(0xFF00FF66),
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Row {
                IconButton(onClick = onViewMapClick) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = "Tactical Map",
                        tint = Color(0xFF00E5FF)
                    )
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFF00FF66)
                    )
                }
            }
        }
        
        // Strict physical spacer to force vertical clearance
        Spacer(modifier = Modifier.height(2.dp))
        
        // Line 2: Status Mode
        Text(
            text = "SYS.STATUS // ACTIVE INTERCEPT",
            color = Color(0xFF00E5FF),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Line 3: System Performance Metrics Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            // Hardbound constraint width guarantees the values never float near each other
            Box(modifier = Modifier.width(120.dp)) {
                Text(
                    text = "FPS: $currentFps",
                    color = Color(0xFF00FF66).copy(alpha = 0.9f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Box {
                Text(
                    text = "INF: ${inferenceTimeMs}ms",
                    color = Color(0xFF00FF66).copy(alpha = 0.9f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
