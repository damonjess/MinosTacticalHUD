package com.minos.hud

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object EventRepository {
    private val _events = mutableStateListOf<DetectedEvent>()
    val events: List<DetectedEvent> get() = _events
    private val mainHandler = Handler(Looper.getMainLooper())

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

        mainHandler.post {
            _events.clear()
            _events.addAll(savedEvents)
        }
    }

    fun saveEvent(
        context: Context,
        label: String,
        category: EventCategory,
        bitmap: Bitmap,
        fullFrame: Bitmap? = null
    ) {
        val eventDir = File(context.filesDir, "events")
        if (!eventDir.exists()) eventDir.mkdirs()

        val id = UUID.randomUUID().toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val thumbFile = File(eventDir, "thumb_$id.jpg")
        val fullFile = File(eventDir, "full_$id.jpg")

        // Safe aspect thumbnail downscaling — higher resolution for sharper previews
        val targetWidth = 480
        val targetHeight = maxOf(1, targetWidth * bitmap.height / maxOf(1, bitmap.width))
        val thumbBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        
        FileOutputStream(thumbFile).use { out ->
            thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        if (thumbBitmap != bitmap) thumbBitmap.recycle()

        // Save full image — use the full camera frame if available for maximum quality,
        // otherwise fall back to the detection crop
        val fullBitmap = fullFrame ?: bitmap
        FileOutputStream(fullFile).use { out ->
            fullBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
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
        
        mainHandler.post {
            _events.add(0, newEvent)
        }
    }

    fun deleteEvent(context: Context, event: DetectedEvent) {
        File(event.thumbnailPath).delete()
        event.fullImagePath?.let { File(it).delete() }
        File(context.filesDir, "events/${event.id}.metadata").delete()
        mainHandler.post {
            _events.remove(event)
        }
    }

    fun deleteAll(context: Context) {
        val eventDir = File(context.filesDir, "events")
        eventDir.deleteRecursively()
        mainHandler.post {
            _events.clear()
        }
    }
}
