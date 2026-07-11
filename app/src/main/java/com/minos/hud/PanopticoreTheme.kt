package com.minos.hud

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object PanopticoreColors {
    val Background = CyberSecPalette.TerminalBlack
    val Surface = CyberSecPalette.DeepScannerGrid
    val PrimaryGreen = CyberSecPalette.CyberNetGreen
    val CyberCyan = CyberSecPalette.TextMutedCyan
    val TacticalAmber = CyberSecPalette.AlertAmber
    val AlertRed = CyberSecPalette.FirewallRed
}

object PanopticoreTypography {
    val Monospace = FontFamily.Monospace
    
    val TechHeader = TextStyle(
        fontFamily = Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 1.sp
    )
    
    val TechBody = TextStyle(
        fontFamily = Monospace,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp
    )
    
    val MetricValue = TextStyle(
        fontFamily = Monospace,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.sp,
        color = CyberSecPalette.CyberNetGreen
    )
}

@Composable
fun PanopticoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CyberSecPalette.CyberNetGreen,
            secondary = CyberSecPalette.TextMutedCyan,
            background = CyberSecPalette.TerminalBlack,
            surface = CyberSecPalette.DeepScannerGrid,
            onSurface = Color.White
        ),
        content = content
    )
}
