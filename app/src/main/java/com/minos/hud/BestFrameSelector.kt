package com.minos.hud

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.abs

object BestFrameSelector {
    
    fun calculateScore(
        bitmap: Bitmap,
        confidence: Float = 0.5f,
        xMin: Float = 0f,
        yMin: Float = 0f,
        xMax: Float = 1f,
        yMax: Float = 1f
    ): Float {
        val width = bitmap.width
        val height = bitmap.height
        
        val left = (xMin * width).toInt().coerceIn(0, width - 1)
        val top = (yMin * height).toInt().coerceIn(0, height - 1)
        val right = (xMax * width).toInt().coerceIn(left + 1, width)
        val bottom = (yMax * height).toInt().coerceIn(top + 1, height)
        
        val targetWidth = right - left
        val targetHeight = bottom - top
        
        if (targetWidth < 10 || targetHeight < 10) return 0f

        val step = maxOf(1, minOf(targetWidth, targetHeight) / 20)
        var detailSum = 0f
        var count = 0
        
        try {
            val pixels = IntArray(targetWidth * targetHeight)
            bitmap.getPixels(pixels, 0, targetWidth, left, top, targetWidth, targetHeight)

            for (y in 0 until targetHeight step step) {
                var prevLum = -1
                val rowOffset = y * targetWidth
                for (x in 0 until targetWidth step step) {
                    val pixel = pixels[rowOffset + x]
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = pixel and 0xFF
                    val lum = (red * 0.299f + green * 0.587f + blue * 0.114f).toInt()
                    
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
        val sharpnessScore = minOf(1f, avgDetail / 40f)
        val sizeScore = minOf(1f, bitmap.width.toFloat() / 600f)

        return sharpnessScore * 0.60f + confidence * 0.25f + sizeScore * 0.15f
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

    fun getPaddingForLabel(label: String): Float {
        return when (label.lowercase().trim()) {
            "person" -> 0.10f
            "car", "bus", "truck", "motorcycle" -> 0.12f
            "plate", "license_plate", "license plate" -> 0.04f
            else -> 0.08f
        }
    }
}

class CaptureManager(
    private val context: Context,
    private val onTriggerHighResCapture: ((xMin: Float, yMin: Float, xMax: Float, yMax: Float, padding: Float, onCaptured: (Bitmap?) -> Unit) -> Unit)? = null
) {
    private val pendingCaptures = mutableMapOf<String, BestFrame>()
    private val capturedIds = mutableMapOf<String, Long>()
    private val isCapturing = mutableSetOf<String>()
    private val captureCooldown = 8000L // 8 seconds cooldown per target

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
        rawLabel: String = ""
    ) {
        val raw = (if (rawLabel.isNotEmpty()) rawLabel else label).lowercase().trim()
        
        // Reject crops that are too small before saving or processing
        val tooSmall = when (raw) {
            "plate", "license_plate", "license plate" -> bitmap.width < 100 || bitmap.height < 30
            else -> bitmap.width < 160 || bitmap.height < 160
        }
        if (tooSmall) return

        val now = System.currentTimeMillis()
        
        // Cooldown check
        if (capturedIds[id] != null && now - capturedIds[id]!! < captureCooldown) return
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
            // If the new frame is sharper/better quality, replace the stored one
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
            
            // Require stable observations (>=8 frames or >900ms) before committing capture to storage
            if (current.frameCount >= 8 || now - current.firstSeen > 900) {
                val best = pendingCaptures.remove(id) ?: return
                capturedIds[id] = now

                val trigger = onTriggerHighResCapture
                val padding = BestFrameSelector.getPaddingForLabel(best.rawLabel.ifEmpty { best.label })
                
                if (trigger != null) {
                    isCapturing.add(id)
                    trigger(best.xMin, best.yMin, best.xMax, best.yMax, padding) { highResCrop ->
                        isCapturing.remove(id)
                        if (highResCrop != null) {
                            EventRepository.saveEvent(context, best.label, best.category, highResCrop, highResCrop)
                            best.bitmap.recycle()
                            best.fullFrame?.recycle()
                        } else {
                            EventRepository.saveEvent(context, best.label, best.category, best.bitmap, best.fullFrame)
                        }
                    }
                } else {
                    EventRepository.saveEvent(context, best.label, best.category, best.bitmap, best.fullFrame)
                }
            }
        }
    }
}
