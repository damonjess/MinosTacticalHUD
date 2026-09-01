package com.minos.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object EventRepository {
    private val _events = mutableStateListOf<DetectedEvent>()
    val events: List<DetectedEvent> get() = _events

    fun loadEvents(context: Context) {
        val eventDir = File(context.filesDir, "events")
        if (!eventDir.exists()) eventDir.mkdirs()

        val savedEvents = eventDir.listFiles { file -> file.name.endsWith(".metadata") }
            ?.mapNotNull { file ->
                try {
                    val lines = file.readLines()
                    if (lines.size >= 5) {
                        DetectedEvent(
                            id = lines[0],
                            label = lines[1],
                            timestamp = lines[2],
                            category = EventCategory.valueOf(lines[3]),
                            thumbnailPath = lines[4],
                            fullImagePath = if (lines.size > 5) lines[5] else null
                        )
                    } else null
                } catch (e: Exception) {
                    null
                }
            }?.sortedByDescending { it.timestamp } ?: emptyList()

        _events.clear()
        _events.addAll(savedEvents)
    }

    fun saveEvent(
        context: Context,
        label: String,
        category: EventCategory,
        bitmap: Bitmap
    ) {
        val eventDir = File(context.filesDir, "events")
        if (!eventDir.exists()) eventDir.mkdirs()

        val id = UUID.randomUUID().toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val thumbFile = File(eventDir, "thumb_$id.jpg")
        val fullFile = File(eventDir, "full_$id.jpg")

        // Save thumbnail (downscaled)
        val thumbBitmap = Bitmap.createScaledBitmap(bitmap, 200, 200 * bitmap.height / bitmap.width, true)
        FileOutputStream(thumbFile).use { out ->
            thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }

        // Save full image
        FileOutputStream(fullFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        val metadataFile = File(eventDir, "$id.metadata")
        metadataFile.writeText("$id\n$label\n$timestamp\n${category.name}\n${thumbFile.absolutePath}\n${fullFile.absolutePath}")

        val newEvent = DetectedEvent(
            id = id,
            label = label.uppercase(),
            timestamp = timestamp,
            category = category,
            thumbnailPath = thumbFile.absolutePath,
            fullImagePath = fullFile.absolutePath
        )
        
        _events.add(0, newEvent)
    }

    fun deleteEvent(context: Context, event: DetectedEvent) {
        File(event.thumbnailPath).delete()
        event.fullImagePath?.let { File(it).delete() }
        File(context.filesDir, "events/${event.id}.metadata").delete()
        _events.remove(event)
    }

    fun deleteAll(context: Context) {
        val eventDir = File(context.filesDir, "events")
        eventDir.deleteRecursively()
        _events.clear()
    }
}
