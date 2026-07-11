package com.minos.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun SubCropTargetWindow(target: MagTrackTarget, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(140.dp)
            .border(1.dp, Color(0xFF00FF66).copy(alpha = 0.6f))
            .background(Color(0xCC050C14))
    ) {
        // Window Subheader Header
        Text(
            text = target.trackLabel,
            color = Color(0xFF00FF66),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        
        // Cropped Target Matrix Box
        Box(modifier = Modifier.fillMaxWidth().height(75.dp).border(1.dp, Color(0xFFFFA500).copy(alpha = 0.4f))) {
            AsyncImage(
                model = target.crop, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            // Miniature sub-targeting pointer crosshair overlay
            Box(modifier = Modifier.size(10.dp).border(1.dp, Color(0xFF00FF66)).align(Alignment.Center))
        }

        // Target coordinates footer text 
        Text(
            text = target.coordinateLabel,
            color = Color(0xFFFFA500),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
    }
}

@Composable
fun PanelTabButton(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (active) Color(0xFF0D1622) else Color.Transparent)
            .clickable { onClick() }
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) Color(0xFF00FF66) else Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun TerminalActionButton(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Box(
        modifier = modifier
            .height(50.dp)
            .background(if (active) Color(0xFF06231A) else Color(0xFF333333).copy(alpha = 0.4f), CutCornerShape(2.dp))
            .border(1.dp, if (active) Color(0xFF00FF66) else Color.Gray.copy(alpha = 0.4f), CutCornerShape(2.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) Color(0xFF00FF66) else Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            lineHeight = 13.sp
        )
    }
}
