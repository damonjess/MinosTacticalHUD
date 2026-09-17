package com.minos.hud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun EventLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    val events = EventRepository.events
    var viewingEvent by remember { mutableStateOf<DetectedEvent?>(null) }

    val filteredEvents = events.filter { event ->
        val matchTab = when (selectedTab) {
            0 -> event.category == EventCategory.PEOPLE_VEHICLES
            1 -> event.category == EventCategory.ANIMALS
            2 -> event.category == EventCategory.PLATES
            else -> true
        }
        val matchSearch = searchQuery.isBlank() || event.label.contains(searchQuery, ignoreCase = true)
        matchTab && matchSearch
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF010408))) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF00FF9D),
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { onBack() }
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "EVENT LOG",
                        color = Color(0xFF00FF9D),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    Text(
                        text = "${events.size} RECORDS",
                        color = Color(0xFF00A8FF),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                TextButton(
                    onClick = { EventRepository.deleteAll(context) }
                ) {
                    Text(
                        text = "CLEAR",
                        color = Color(0xFFFF7777),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Tabs
            Row(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                LogTab("PEOPLE (${events.count { it.category == EventCategory.PEOPLE_VEHICLES }})", selectedTab == 0, Modifier.weight(1f)) { selectedTab = 0 }
                LogTab("ANIMALS (${events.count { it.category == EventCategory.ANIMALS }})", selectedTab == 1, Modifier.weight(1f)) { selectedTab = 1 }
                LogTab("PLATES (${events.count { it.category == EventCategory.PLATES }})", selectedTab == 2, Modifier.weight(1f)) { selectedTab = 2 }
                LogTab("ALL (${events.size})", selectedTab == 3, Modifier.weight(1f)) { selectedTab = 3 }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search by label...", color = Color.Gray, fontFamily = FontFamily.Monospace) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = FontFamily.Monospace),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF05101A),
                    unfocusedContainerColor = Color(0xFF05101A),
                    focusedIndicatorColor = Color(0xFF00FF9D),
                    unfocusedIndicatorColor = Color(0xFF102A3E)
                ),
                singleLine = true
            )

            // List
            if (filteredEvents.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "NO DETECTIONS LOGGED YET",
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
                    items(filteredEvents, key = { it.id }) { event ->
                        EventItem(
                            event = event,
                            onClick = { viewingEvent = event },
                            onDelete = { EventRepository.deleteEvent(context, event) }
                        )
                    }
                }
            }
        }

        // Full Screen Tactical Image Viewer Modal
        viewingEvent?.let { event ->
            FullImageDossierDialog(
                event = event,
                onDismiss = { viewingEvent = null },
                onDelete = {
                    EventRepository.deleteEvent(context, event)
                    viewingEvent = null
                }
            )
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
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = if (active) Color(0xFF00FF9D) else Color.Gray,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (active) Color(0xFF00FF9D) else Color.Transparent)
        )
    }
}

@Composable
fun EventItem(event: DetectedEvent, onClick: () -> Unit, onDelete: () -> Unit) {
    val bitmap = remember(event.thumbnailPath) {
        try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(event.thumbnailPath, opts)
        } catch (e: Exception) {
            null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF05101A)
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF102A3E))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp, 76.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black)
                        .border(
                            1.dp,
                            Color(0xFF00FF9D).copy(alpha = 0.45f),
                            RoundedCornerShape(6.dp)
                        )
                ) {
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Event thumbnail",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = event.label,
                        color = Color(0xFF00FF9D),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = event.timestamp,
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Text(
                        text = when (event.category) {
                            EventCategory.PEOPLE_VEHICLES -> "VEHICLE / PERSON"
                            EventCategory.ANIMALS -> "ANIMAL"
                            EventCategory.PLATES -> "LICENSE PLATE"
                        },
                        color = Color(0xFF00A8FF),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VIEW FULL",
                    color = Color(0xFF00FF9D),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF421515)
                    ),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = 5.dp
                    ),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = "DELETE",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun FullImageDossierDialog(event: DetectedEvent, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val fullBitmap = remember(event.id) {
        // Prefer fullImagePath (full_<id>.jpg) for the high-res crop viewer.
        // Fall back to thumbnailPath only if fullImagePath cannot be decoded.
        val path = event.fullImagePath ?: event.thumbnailPath
        try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, opts) ?: BitmapFactory.decodeFile(event.thumbnailPath, opts)
        } catch (e: Exception) {
            null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF002060B))
                .clickable { onDismiss() }
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {} // Prevent click-through dismissal
                    .border(1.dp, Color(0xFF00FF9D), RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF05101A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Title Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = event.label,
                                color = Color(0xFF00FF9D),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = event.timestamp,
                                color = Color.LightGray,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Metadata
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Text("CONFIDENCE: ${(event.confidence * 100).toInt()}%", color = Color(0xFF00FF9D), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text("TRACKED: ${event.framesTracked} frames", color = Color(0xFF00A8FF), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text("IMAGE QUALITY: ${event.sharpnessScore}", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text("MODE: ${event.detectionMode.name}", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        if (event.videoClipPath != null) {
                            Text("CLIP: Available", color = Color(0xFF00FF9D), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }

                    // Image Display Frame
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                            .border(1.dp, Color(0xFF102A3E), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (fullBitmap != null) {
                            Image(
                                bitmap = fullBitmap.asImageBitmap(),
                                contentDescription = "High-Res Detection Capture",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text("UNABLE TO LOAD FULL IMAGE", color = Color.Red, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action Controls
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(
                            onClick = onDelete,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A1C1C)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("DELETE", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { 
                                val file = EventRepository.exportSelectedToZip(context, listOf(event))
                                if (file != null) {
                                    Toast.makeText(context, "Exported to ${file.name}", Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055AA)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("EXPORT", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }

                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF008544)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("CLOSE", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
