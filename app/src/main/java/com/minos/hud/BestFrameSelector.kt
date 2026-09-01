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

class CaptureManager(private val context: Context) {
    // Cooldown per track ID
    private val capturedIds = mutableMapOf<String, Long>()
    
    // Spatial grid cooldown: prevents identical stationary parked cars / standing people from duplicate spam
    private val spatialLastCapture = mutableMapOf<String, Long>()
    
    private val trackCooldown = 25000L  // 25s cooldown per tracked target
    private val spatialCooldown = 30000L // 30s cooldown for objects in the same screen quadrant

    fun processDetection(
        trackId: String,
        label: String,
        category: EventCategory,
        bitmap: Bitmap,
        relX: Float,
        relY: Float
    ) {
        val now = System.currentTimeMillis()
        
        // 1. Track-based cooldown check
        val lastTrackTime = capturedIds[trackId]
        if (lastTrackTime != null && now - lastTrackTime < trackCooldown) return

        // 2. Spatial grid cooldown check (Grid size: 10x10 buckets across viewport)
        val gridKey = "${category.name}_${(relX * 10).toInt()}_${(relY * 10).toInt()}"
        val lastSpatialTime = spatialLastCapture[gridKey]
        if (lastSpatialTime != null && now - lastSpatialTime < spatialCooldown) return

        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        val copy = try { bitmap.copy(config, false) } catch (e: Exception) { null } ?: return
        
        capturedIds[trackId] = now
        spatialLastCapture[gridKey] = now
        
        EventRepository.saveEvent(context, label, category, copy)
    }
}
