package com.minos.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

object BestFrameSelector {
    
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
    private val captureCooldown = 8000L // 8 seconds cooldown per target

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
        if (capturedIds[id] != null && now - capturedIds[id]!! < captureCooldown) return

        val current = pendingCaptures[id]
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        
        if (current == null) {
            val copy = try { bitmap.copy(config, false) } catch (e: Exception) { null } ?: return
            pendingCaptures[id] = BestFrame(copy, score, 1, now, label, category)
        } else {
            current.frameCount++
            // If the new frame is sharper/better quality, replace the stored one
            if (score > current.score) {
                val copy = try { bitmap.copy(config, false) } catch (e: Exception) { null } ?: return
                current.bitmap.recycle() // Clean up old memory
                pendingCaptures[id] = current.copy(bitmap = copy, score = score, frameCount = current.frameCount)
            }
            
            // Commit capture to storage after observing the target for a brief window to ensure a good shot
            if (current.frameCount >= 5 || now - current.firstSeen > 600) {
                val best = pendingCaptures.remove(id) ?: return
                EventRepository.saveEvent(context, best.label, best.category, best.bitmap)
                capturedIds[id] = now
            }
        }
    }
}
