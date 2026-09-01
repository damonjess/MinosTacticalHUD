package com.minos.hud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import java.io.File

@Composable
fun EventLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val events = EventRepository.events

    val filteredEvents = when (selectedTab) {
        0 -> events.filter { it.category == EventCategory.PEOPLE_VEHICLES }
        1 -> events.filter { it.category == EventCategory.ANIMALS }
        2 -> events.filter { it.category == EventCategory.PLATES }
        else -> events
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF010408))) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color(0xFF00FF9D),
                modifier = Modifier.size(32.dp).clickable { onBack() }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "EVENT LOG",
                color = Color(0xFF00FF9D),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "•",
                color = Color(0xFF00FF9D),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Text(
                text = "${events.size}",
                color = Color(0xFF00FF9D),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = { EventRepository.deleteAll(context) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF421515)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("DELETE ALL", color = Color.White)
            }
        }

        // Tabs
        Row(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            LogTab("PEOPLE + VEHIC...", selectedTab == 0, Modifier.weight(1f)) { selectedTab = 0 }
            LogTab("ANIMALS (${events.count { it.category == EventCategory.ANIMALS }})", selectedTab == 1, Modifier.weight(1f)) { selectedTab = 1 }
            LogTab("PLATES (${events.count { it.category == EventCategory.PLATES }})", selectedTab == 2, Modifier.weight(1f)) { selectedTab = 2 }
        }

        // List
        LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            items(filteredEvents) { event ->
                EventItem(event) {
                    EventRepository.deleteEvent(context, event)
                }
            }
        }
    }
}

@Composable
fun LogTab(text: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            color = if (active) Color(0xFF00FF9D) else Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        if (active) {
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF00FF9D)))
        }
    }
}

@Composable
fun EventItem(event: DetectedEvent, onDelete: () -> Unit) {
    val bitmap = remember(event.thumbnailPath) {
        try {
            BitmapFactory.decodeFile(event.thumbnailPath)
        } catch (e: Exception) {
            null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF05101A)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF102A3E))
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(100.dp, 80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = event.label, color = Color(0xFF00FF9D), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text = event.timestamp, color = Color.Gray, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = when(event.category) {
                        EventCategory.PEOPLE_VEHICLES -> "VEHICLE"
                        EventCategory.ANIMALS -> "ANIMAL"
                        EventCategory.PLATES -> "PLATE"
                    }, color = Color(0xFF00A8FF), fontSize = 11.sp)
                    Text(text = " • ", color = Color.Gray)
                    Text(text = "TAP THUMBNA...", color = Color(0xFF00A8FF), fontSize = 11.sp)
                }
            }

            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF421515)),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("DELETE", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
