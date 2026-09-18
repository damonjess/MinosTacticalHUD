package com.minos.hud

import android.graphics.Bitmap
import android.graphics.Matrix

object VideoBuffer {
    data class BufferedFrame(
        val bitmap: Bitmap,
        val timestampNs: Long
    )
    
    private val frames = mutableListOf<BufferedFrame>()
    private val maxDurationNs = 3_000_000_000L // 3 seconds
    
    @Synchronized
    fun addFrame(rawBitmap: Bitmap) {
        val now = System.nanoTime()
        // Downscale to save memory
        val scale = 360f / rawBitmap.height.toFloat()
        val matrix = Matrix().apply { postScale(scale, scale) }
        val smallBitmap = try {
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } catch (e: Exception) {
            return
        }
        
        frames.add(BufferedFrame(smallBitmap, now))
        
        // Remove old frames
        while (frames.isNotEmpty() && now - frames.first().timestampNs > maxDurationNs) {
            frames.removeAt(0).bitmap.recycle()
        }
    }
    
    @Synchronized
    fun getRecentFrames(): List<Bitmap> {
        val list = mutableListOf<Bitmap>()
        for (f in frames) {
            try {
                list.add(f.bitmap.copy(f.bitmap.config ?: Bitmap.Config.ARGB_8888, false))
            } catch (e: Exception) {
                // skip recycled or invalid frames
            }
        }
        return list
    }

    @Synchronized
    fun clear() {
        for (f in frames) {
            try {
                if (!f.bitmap.isRecycled) f.bitmap.recycle()
            } catch (e: Exception) {
                // already recycled
            }
        }
        frames.clear()
    }
}
