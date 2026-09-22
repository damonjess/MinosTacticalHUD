package com.minos.hud

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
                        val categoryStr = lines[3]
                        val parsedCategory = try {
                            EventCategory.valueOf(categoryStr)
                        } catch (e: Exception) {
                            if (lines[1].contains("PERSON", ignoreCase = true)) EventCategory.PEOPLE else EventCategory.VEHICLES
                        }
                        DetectedEvent(
                            id = lines[0],
                            label = lines[1],
                            timestamp = lines[2],
                            category = parsedCategory,
                            thumbnailPath = lines[4],
                            fullImagePath = if (lines.size > 5) lines[5] else null,
                            confidence = if (lines.size > 6) lines[6].toFloatOrNull() ?: 0f else 0f,
                            detectionMode = if (lines.size > 7) DetectionMode.valueOf(lines[7]) else DetectionMode.ALL,
                            imageDimensions = if (lines.size > 8) lines[8] else "",
                            sharpnessScore = if (lines.size > 9) lines[9] else "Unknown",
                            framesTracked = if (lines.size > 10) lines[10].toIntOrNull() ?: 0 else 0,
                            trackedDurationSec = if (lines.size > 11) lines[11].toFloatOrNull() ?: 0f else 0f,
                            screenPosition = if (lines.size > 12) lines[12] else "",
                            lowLightActive = if (lines.size > 13) lines[13].toBoolean() else false,
                            gpsCoordinates = if (lines.size > 14 && lines[14] != "null") lines[14] else null,
                            videoClipPath = if (lines.size > 15 && lines[15] != "null") lines[15] else null
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
        fullFrame: Bitmap? = null,
        confidence: Float = 0f,
        detectionMode: DetectionMode = DetectionMode.ALL,
        imageDimensions: String = "",
        sharpnessScore: String = "Unknown",
        framesTracked: Int = 0,
        trackedDurationSec: Float = 0f,
        screenPosition: String = "",
        lowLightActive: Boolean = false,
        gpsCoordinates: String? = null,
        videoClipPath: String? = null
    ) {
        val eventDir = File(context.filesDir, "events")
        if (!eventDir.exists()) eventDir.mkdirs()

        val id = UUID.randomUUID().toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val thumbFile = File(eventDir, "thumb_$id.jpg")
        val fullFile = File(eventDir, "full_$id.jpg")

        // Safe aspect thumbnail downscaling — do not upscale small crops
        val targetWidth = minOf(480, bitmap.width)
        val targetHeight = maxOf(1, targetWidth * bitmap.height / maxOf(1, bitmap.width))
        val thumbBitmap = if (targetWidth < bitmap.width) {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } else {
            bitmap
        }
        
        FileOutputStream(thumbFile).use { out ->
            thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        if (thumbBitmap != bitmap) thumbBitmap.recycle()

        // Save the expanded event image as the original resolution crop so VIEW FULL
        // stays zoomed in on the detected subject with full original quality.
        // Or if fullFrame is passed from high-res capture, it will be the full HQ photo.
        val imageToSave = fullFrame ?: bitmap
        FileOutputStream(fullFile).use { out ->
            imageToSave.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        val metadataFile = File(eventDir, "$id.metadata")
        val lines = listOf(
            id,
            label,
            timestamp,
            category.name,
            thumbFile.absolutePath,
            fullFile.absolutePath,
            confidence.toString(),
            detectionMode.name,
            imageDimensions,
            sharpnessScore,
            framesTracked.toString(),
            trackedDurationSec.toString(),
            screenPosition,
            lowLightActive.toString(),
            gpsCoordinates ?: "null",
            videoClipPath ?: "null"
        )
        metadataFile.writeText(lines.joinToString("\n"))

        val newEvent = DetectedEvent(
            id = id,
            label = label.uppercase(),
            confidence = confidence,
            timestamp = timestamp,
            category = category,
            detectionMode = detectionMode,
            imageDimensions = imageDimensions,
            sharpnessScore = sharpnessScore,
            framesTracked = framesTracked,
            trackedDurationSec = trackedDurationSec,
            screenPosition = screenPosition,
            lowLightActive = lowLightActive,
            gpsCoordinates = gpsCoordinates,
            thumbnailPath = thumbFile.absolutePath,
            fullImagePath = fullFile.absolutePath,
            videoClipPath = videoClipPath
        )
        
        mainHandler.post {
            _events.add(0, newEvent)
        }
        
        enforceStorageLimit(context)
    }

    private fun enforceStorageLimit(context: Context) {
        val eventDir = File(context.filesDir, "events")
        if (!eventDir.exists()) return
        
        // Example: limit to 200MB or keep top N events.
        // For simplicity, let's limit by count: keep only the newest 500 events.
        val maxEvents = 500
        val allMetadata = eventDir.listFiles { file -> file.name.endsWith(".metadata") } ?: return
        if (allMetadata.size > maxEvents) {
            val toDelete = allMetadata.sortedByDescending { it.lastModified() }.drop(maxEvents)
            toDelete.forEach { metaFile ->
                val id = metaFile.name.substringBefore(".metadata")
                File(eventDir, "thumb_$id.jpg").delete()
                File(eventDir, "full_$id.jpg").delete()
                // Clips are stored as directories named clip_<UUID>; read the path from metadata
                try {
                    val lines = metaFile.readLines()
                    if (lines.size > 15 && lines[15] != "null") {
                        File(lines[15]).deleteRecursively()
                    }
                } catch (e: Exception) {
                    // Best-effort cleanup; metadata may be corrupted
                }
                metaFile.delete()
            }
            // Update live list if we are on main thread or post to it
            mainHandler.post {
                _events.removeAll { event -> toDelete.any { it.name.startsWith(event.id) } }
            }
        }
    }

    fun deleteEvent(context: Context, event: DetectedEvent) {
        File(event.thumbnailPath).delete()
        event.fullImagePath?.let { File(it).delete() }
        event.videoClipPath?.let { File(it).deleteRecursively() }
        File(context.filesDir, "events/${event.id}.metadata").delete()
        mainHandler.post {
            _events.remove(event)
        }
    }

    fun deleteMultipleEvents(context: Context, eventsToDelete: List<DetectedEvent>) {
        eventsToDelete.forEach { event ->
            File(event.thumbnailPath).delete()
            event.fullImagePath?.let { File(it).delete() }
            event.videoClipPath?.let { File(it).deleteRecursively() }
            File(context.filesDir, "events/${event.id}.metadata").delete()
        }
        mainHandler.post {
            _events.removeAll(eventsToDelete)
        }
    }

    fun deleteAll(context: Context) {
        val eventDir = File(context.filesDir, "events")
        eventDir.deleteRecursively()
        mainHandler.post {
            _events.clear()
        }
    }

    fun exportSelectedToZip(context: Context, selectedEvents: List<DetectedEvent>): File? {
        val exportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "MinosExports"
        )
        if (!exportDir.exists()) exportDir.mkdirs()
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val zipFile = File(exportDir, "export_$timestamp.zip")
        
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                selectedEvents.forEach { event ->
                    // Add thumbnail
                    addFileToZip(File(event.thumbnailPath), "${event.id}/thumb.jpg", zos)
                    // Add full image
                    event.fullImagePath?.let {
                        addFileToZip(File(it), "${event.id}/full.jpg", zos)
                    }
                    // Add video clip
                    event.videoClipPath?.let {
                        addFileToZip(File(it), "${event.id}/clip.${File(it).extension}", zos)
                    }
                    // Add metadata as JSON-like
                    val metaEntry = ZipEntry("${event.id}/metadata.txt")
                    zos.putNextEntry(metaEntry)
                    val metadataString = """
                        ID: ${event.id}
                        Label: ${event.label}
                        Confidence: ${event.confidence}
                        Timestamp: ${event.timestamp}
                        Category: ${event.category.name}
                        Detection Mode: ${event.detectionMode.name}
                        Image Dimensions: ${event.imageDimensions}
                        Sharpness Score: ${event.sharpnessScore}
                        Frames Tracked: ${event.framesTracked}
                        Tracked Duration: ${event.trackedDurationSec}s
                        Screen Position: ${event.screenPosition}
                        Low Light Active: ${event.lowLightActive}
                        GPS Coordinates: ${event.gpsCoordinates ?: "N/A"}
                    """.trimIndent()
                    zos.write(metadataString.toByteArray())
                    zos.closeEntry()
                }
            }
            return zipFile
        } catch (e: Exception) {
            Log.e("EventRepository", "Failed to export events", e)
            return null
        }
    }

    private fun addFileToZip(file: File, entryName: String, zos: ZipOutputStream) {
        if (!file.exists()) return
        FileInputStream(file).use { fis ->
            val entry = ZipEntry(entryName)
            zos.putNextEntry(entry)
            fis.copyTo(zos)
            zos.closeEntry()
        }
    }
}
