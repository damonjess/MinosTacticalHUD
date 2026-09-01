package com.minos.hud

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

object BestFrameSelector {
    
    /**
     * Calculates a "Quality Score" based on sharpness and detail within the target area.
     * Higher is better.
     */
    fun calculateScore(bitmap: Bitmap, xMin: Float, yMin: Float, xMax: Float, yMax: Float): Float {
        val width = bitmap.width
        val height = bitmap.height
        
        val left = (xMin * width).toInt().coerceIn(0, width - 1)
        val top = (yMin * height).toInt().coerceIn(0, height - 1)
        val right = (xMax * width).toInt().coerceIn(left + 1, width)
        val bottom = (yMax * height).toInt().coerceIn(top + 1, height)
        
        val targetWidth = right - left
        val targetHeight = bottom - top
        
        if (targetWidth < 10 || targetHeight < 10) return 0f

        // Sample pixels to estimate detail/sharpness
        val step = maxOf(1, minOf(targetWidth, targetHeight) / 20)
        var detailSum = 0f
        var count = 0
        
        try {
            for (y in top until bottom step step) {
                var prevLum = -1
                for (x in left until right step step) {
                    val pixel = bitmap.getPixel(x, y)
                    val lum = (Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f).toInt()
                    
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
        val sizeFactor = (targetWidth.toFloat() * targetHeight) / (width * height)
        
        return avgDetail + (sizeFactor * 50f)
    }
}

class CaptureManager(private val context: android.content.Context) {
    private val pendingCaptures = mutableMapOf<String, BestFrame>()
    private val capturedIds = mutableMapOf<String, Long>()
    private val captureCooldown = 10000L // 10 seconds per unique target ID
    private val frameWindow = 1 // Instant capture for verification
    
    data class BestFrame(
        val bitmap: Bitmap,
        val score: Float,
        var frameCount: Int,
        val firstSeen: Long,
        val label: String,
        val category: EventCategory
    )

    fun processDetection(id: String, label: String, category: EventCategory, bitmap: Bitmap, score: Float) {
        val now = System.currentTimeMillis()
        
        // Cooldown check
        val lastCaptured = capturedIds[id]
        if (lastCaptured != null && now - lastCaptured < captureCooldown) return

        val current = pendingCaptures[id]
        if (current == null) {
            val config = bitmap.config ?: Bitmap.Config.ARGB_8888
            val copy = try { bitmap.copy(config, false) } catch (e: Exception) { null }
            if (copy != null) {
                pendingCaptures[id] = BestFrame(copy, score, 1, now, label, category)
            }
        } else {
            current.frameCount++
            if (score > current.score) {
                // Better frame found
                val config = bitmap.config ?: Bitmap.Config.ARGB_8888
                val copy = try { bitmap.copy(config, false) } catch (e: Exception) { null }
                if (copy != null) {
                    current.bitmap.recycle()
                    pendingCaptures[id] = current.copy(bitmap = copy, score = score, frameCount = current.frameCount)
                }
            }
            
            // If we've seen enough frames or enough time has passed, commit it
            if (current.frameCount >= frameWindow || now - current.firstSeen > 500) {
                commitCapture(id)
                capturedIds[id] = now
            }
        }
    }

    private fun commitCapture(id: String) {
        val best = pendingCaptures.remove(id) ?: return
        EventRepository.saveEvent(context, best.label, best.category, best.bitmap)
        // Clean up bitmap
        best.bitmap.recycle()
    }
}
