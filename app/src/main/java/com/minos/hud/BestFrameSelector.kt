package com.minos.hud

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs

object BestFrameSelector {
    
    /**
     * Checks if the crop exposure is within acceptable bounds.
     * Rejects overexposed (blown out white) or underexposed (pitch black) frames.
     */
    fun checkExposure(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 5 || height < 5) return false

        val step = maxOf(1, minOf(width, height) / 25)
        var totalLum = 0L
        var sampleCount = 0
        var darkCount = 0
        var blownCount = 0

        try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            for (y in 0 until height step step) {
                val rowOffset = y * width
                for (x in 0 until width step step) {
                    val pixel = pixels[rowOffset + x]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val lum = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()

                    totalLum += lum
                    sampleCount++
                    if (lum < 15) darkCount++
                    if (lum > 240) blownCount++
                }
            }
        } catch (e: Exception) {
            return false
        }

        if (sampleCount == 0) return false

        val avgLum = totalLum.toFloat() / sampleCount
        val darkRatio = darkCount.toFloat() / sampleCount
        val blownRatio = blownCount.toFloat() / sampleCount

        // Reject severe underexposure (<20) or overexposure (>235), or extreme clipping
        return avgLum in 20.0f..235.0f && darkRatio < 0.40f && blownRatio < 0.40f
    }

    /**
     * Calculates relative edge detail / sharpness score (0.0 to 1.0).
     */
    fun calculateSharpnessScore(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 10 || height < 10) return 0f

        val step = maxOf(1, minOf(width, height) / 30)
        var detailSum = 0f
        var count = 0

        try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            for (y in 0 until height step step) {
                var prevLum = -1
                val rowOffset = y * width
                for (x in 0 until width step step) {
                    val pixel = pixels[rowOffset + x]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val lum = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()

                    if (prevLum != -1) {
                        detailSum += abs(lum - prevLum)
                    }
                    prevLum = lum
                    count++
                }
            }
        } catch (e: Exception) {
            return 0f
        }

        if (count == 0) return 0f
        val avgDetail = detailSum / count
        return (avgDetail / 40f).coerceIn(0f, 1f)
    }

    fun calculateScore(
        bitmap: Bitmap,
        confidence: Float = 0.5f,
        xMin: Float = 0f,
        yMin: Float = 0f,
        xMax: Float = 1f,
        yMax: Float = 1f
    ): Float {
        val sharpnessScore = calculateSharpnessScore(bitmap)
        val isExposureGood = if (checkExposure(bitmap)) 1.0f else 0.2f
        val sizeScore = minOf(1f, bitmap.width.toFloat() / 600f)

        return (sharpnessScore * 0.50f + confidence * 0.25f + sizeScore * 0.15f + isExposureGood * 0.10f).coerceIn(0f, 1f)
    }

    fun cropDetection(
        bitmap: Bitmap,
        xMin: Float,
        yMin: Float,
        xMax: Float,
        yMax: Float,
        padding: Float = 0.08f
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val boxWidth = (xMax - xMin) * width
        val boxHeight = (yMax - yMin) * height

        val left = ((xMin * width) - boxWidth * padding)
            .toInt()
            .coerceIn(0, width - 1)

        val top = ((yMin * height) - boxHeight * padding)
            .toInt()
            .coerceIn(0, height - 1)

        val right = ((xMax * width) + boxWidth * padding)
            .toInt()
            .coerceIn(left + 1, width)

        val bottom = ((yMax * height) + boxHeight * padding)
            .toInt()
            .coerceIn(top + 1, height)

        return Bitmap.createBitmap(
            bitmap,
            left,
            top,
            right - left,
            bottom - top
        )
    }

    fun getPaddingForLabel(@Suppress("UNUSED_PARAMETER") label: String): Float {
        return 0.40f
    }
}

class CaptureManager(
    private val context: Context,
    private val onTriggerHighResCapture: ((xMin: Float, yMin: Float, xMax: Float, yMax: Float, padding: Float, onCaptured: (Bitmap?) -> Unit) -> Unit)? = null
) {
    private val pendingCaptures = mutableMapOf<String, BestFrame>()
    private val capturedIds = mutableMapOf<String, Long>()
    private val isCapturing = mutableSetOf<String>()

    data class BestFrame(
        val bitmap: Bitmap,
        val score: Float,
        var frameCount: Int,
        val firstSeen: Long,
        val label: String,
        val category: EventCategory,
        val fullFrame: Bitmap? = null,
        val xMin: Float = 0f,
        val yMin: Float = 0f,
        val xMax: Float = 1f,
        val yMax: Float = 1f,
        val rawLabel: String = "",
        var stableFrames: Int = 0
    )

    fun processDetection(
        id: String,
        label: String,
        category: EventCategory,
        bitmap: Bitmap,
        score: Float,
        fullFrame: Bitmap? = null,
        xMin: Float = 0f,
        yMin: Float = 0f,
        xMax: Float = 1f,
        yMax: Float = 1f,
        rawLabel: String = "",
        lockedTrackId: String? = null,
        minStableFrames: Int = 5,
        minSharpnessThreshold: Float = 0.30f,
        cooldownMs: Long = 8000L,
        minCropWidth: Int = 160,
        minCropHeight: Int = 160,
        minPlateCropWidth: Int = 100,
        minPlateCropHeight: Int = 30
    ) {
        // Feature 2: Tap-to-lock filtering - if a target is locked, ignore captures for other targets
        if (lockedTrackId != null && !id.startsWith(lockedTrackId) && !lockedTrackId.startsWith(id)) {
            return
        }

        val raw = (if (rawLabel.isNotEmpty()) rawLabel else label).lowercase().trim()
        val isPlate = raw in listOf("plate", "license_plate", "license plate")

        // Feature 4 Rule 3: Minimum crop size check
        val tooSmall = if (isPlate) {
            bitmap.width < minPlateCropWidth || bitmap.height < minPlateCropHeight
        } else {
            bitmap.width < minCropWidth || bitmap.height < minCropHeight
        }
        if (tooSmall) return

        // Feature 4 Rule 5: Exposure problem check
        if (!BestFrameSelector.checkExposure(bitmap)) {
            return
        }

        // Feature 4 Rule 4: Sharpness threshold check
        val sharpnessScore = BestFrameSelector.calculateSharpnessScore(bitmap)
        if (sharpnessScore < minSharpnessThreshold) {
            return
        }

        val now = System.currentTimeMillis()
        
        // Feature 4 Rule 6: Target cooldown check
        if (capturedIds[id] != null && now - capturedIds[id]!! < cooldownMs) return
        if (isCapturing.contains(id)) return

        val current = pendingCaptures[id]
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        
        if (current == null) {
            val copy = try { bitmap.copy(config, false) } catch (e: Exception) { null } ?: return
            val fullCopy = try { fullFrame?.copy(config, false) } catch (e: Exception) { null }
            pendingCaptures[id] = BestFrame(copy, score, 1, now, label, category, fullCopy, xMin, yMin, xMax, yMax, raw, stableFrames = 1)
        } else {
            current.frameCount++
            current.stableFrames++
            // If the new frame is sharper/better quality, replace the stored one (Best-frame selection)
            if (score > current.score) {
                val copy = try { bitmap.copy(config, false) } catch (e: Exception) { null } ?: return
                val fullCopy = try { fullFrame?.copy(config, false) } catch (e: Exception) { null }
                current.fullFrame?.recycle()
                current.bitmap.recycle()
                pendingCaptures[id] = current.copy(
                    bitmap = copy,
                    score = score,
                    frameCount = current.frameCount,
                    stableFrames = current.stableFrames,
                    fullFrame = fullCopy,
                    xMin = xMin,
                    yMin = yMin,
                    xMax = xMax,
                    yMax = yMax,
                    rawLabel = if (raw.isNotEmpty()) raw else current.rawLabel
                )
            } else {
                pendingCaptures[id] = current.copy(
                    frameCount = current.frameCount,
                    stableFrames = current.stableFrames,
                    xMin = xMin,
                    yMin = yMin,
                    xMax = xMax,
                    yMax = yMax,
                    rawLabel = if (raw.isNotEmpty()) raw else current.rawLabel
                )
            }
            
            // Feature 4 Rule 1: Require same target detected for at least N frames (minStableFrames, default 5)
            if (current.frameCount >= minStableFrames || now - current.firstSeen > 900) {
                val best = pendingCaptures.remove(id) ?: return
                capturedIds[id] = now

                // Grab recent frames for clip
                val framesForClip = VideoBuffer.getRecentFrames()

                val trigger = onTriggerHighResCapture
                val padding = BestFrameSelector.getPaddingForLabel(best.rawLabel.ifEmpty { best.label })
                
                if (trigger != null) {
                    isCapturing.add(id)
                    trigger(best.xMin, best.yMin, best.xMax, best.yMax, padding) { highResCrop ->
                        isCapturing.remove(id)
                        saveEventWithClip(context, best.label, best.category, highResCrop ?: best.bitmap, highResCrop ?: best.fullFrame, framesForClip, best.score, best.frameCount)
                        best.bitmap.recycle()
                        best.fullFrame?.recycle()
                    }
                } else {
                    saveEventWithClip(context, best.label, best.category, best.bitmap, best.fullFrame, framesForClip, best.score, best.frameCount)
                    best.bitmap.recycle()
                    best.fullFrame?.recycle()
                }
            }
        }
    }
    
    private fun saveEventWithClip(
        context: Context, label: String, category: EventCategory, 
        crop: Bitmap, fullFrame: Bitmap?, framesForClip: List<Bitmap>,
        score: Float, frameCount: Int
    ) {
        val eventDir = File(context.filesDir, "events")
        if (!eventDir.exists()) eventDir.mkdirs()
        
        var clipPath: String? = null
        if (framesForClip.isNotEmpty()) {
            val clipDir = File(eventDir, "clip_${UUID.randomUUID()}")
            clipDir.mkdirs()
            clipPath = clipDir.absolutePath
            for ((idx, bmp) in framesForClip.withIndex()) {
                val f = File(clipDir, String.format("frame_%03d.jpg", idx))
                FileOutputStream(f).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                }
                bmp.recycle()
            }
        }

        EventRepository.saveEvent(
            context = context,
            label = label,
            category = category,
            bitmap = crop,
            fullFrame = fullFrame,
            confidence = score,
            sharpnessScore = "Good",
            framesTracked = frameCount,
            videoClipPath = clipPath
        )
    }
}
