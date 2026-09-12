package com.minos.hud

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.max

class HUDOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val vehiclePaint = Paint().apply {
        color = Color.parseColor("#00FF66")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val animalPaint = Paint().apply {
        color = Color.parseColor("#FFA500")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val personPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val platePaint = Paint().apply {
        color = Color.parseColor("#FFFF00")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#00FF66")
        textSize = 34f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    private val textBgPaint = Paint().apply {
        color = Color.parseColor("#FF03090F")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val tetherPaint = Paint().apply {
        color = Color.parseColor("#FFA500")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
        alpha = 75
    }

    var magTrackTargets: List<MagTrackTarget> = emptyList()
    var targets: List<YoloTarget> = emptyList()
    var isYoloBoxesEnabled: Boolean = true
    var sensitivityThreshold: Float = 0.45f

    private var camSourceWidth = 720f
    private var camSourceHeight = 1280f

    // --- Velocity prediction & temporal smoothing state ---
    private var lastUpdateTimeMs: Long = System.currentTimeMillis()
    private var isAnimating = false

    private data class SmoothedTrack(
        var xMin: Float, var yMin: Float, var xMax: Float, var yMax: Float,
        var vx: Float, var vy: Float,
        var label: String, var rawLabel: String,
        var confidence: Float,
        var lastSeenMs: Long,
        var missedCount: Int = 0
    )

    private val smoothedTracks = mutableListOf<SmoothedTrack>()
    private val maxTracks = 15
    private val smoothingAlpha = 0.35f   // EMA position factor (higher = more responsive to new detection)
    private val velocityAlpha = 0.2f    // EMA velocity factor (lower = smoother velocity)
    private val maxMissedFrames = 3     // Keep predicting a track for this many frames after it disappears
    private val matchDistanceThreshold = 0.15f  // Max squared center distance to match targets (normalized)

    fun setCameraSourceDimensions(width: Int, height: Int) {
        camSourceWidth = width.toFloat()
        camSourceHeight = height.toFloat()
    }

    fun updateTargets(newTargets: List<YoloTarget>) {
        val now = System.currentTimeMillis()
        val dt = maxOf(1f, (now - lastUpdateTimeMs) / 1000.0f) // seconds since last update

        // Match new detections to existing smoothed tracks by proximity (using predicted centers)
        val matched = BooleanArray(newTargets.size) { false }
        val updatedTracks = mutableListOf<SmoothedTrack>()

        for (existing in smoothedTracks) {
            // Predict where this track should be now based on velocity
            val predCx = (existing.xMin + existing.xMax) / 2f + existing.vx * dt
            val predCy = (existing.yMin + existing.yMax) / 2f + existing.vy * dt

            var bestIdx = -1
            var bestDist = Float.MAX_VALUE

            for (i in newTargets.indices) {
                if (matched[i]) continue
                val t = newTargets[i]
                val tcx = (t.xMin + t.xMax) / 2f
                val tcy = (t.yMin + t.yMax) / 2f
                val dist = (tcx - predCx) * (tcx - predCx) + (tcy - predCy) * (tcy - predCy)
                if (dist < bestDist && dist < matchDistanceThreshold) {
                    bestDist = dist
                    bestIdx = i
                }
            }

            if (bestIdx >= 0) {
                matched[bestIdx] = true
                val t = newTargets[bestIdx]

                // Compute instantaneous velocity from position delta
                val tcx = (t.xMin + t.xMax) / 2f
                val tcy = (t.yMin + t.yMax) / 2f
                val ecx = (existing.xMin + existing.xMax) / 2f
                val ecy = (existing.yMin + existing.yMax) / 2f
                val newVx = (tcx - ecx) / dt
                val newVy = (tcy - ecy) / dt

                // EMA smoothing — blends old smoothed position with new detection
                existing.xMin = existing.xMin * (1 - smoothingAlpha) + t.xMin * smoothingAlpha
                existing.yMin = existing.yMin * (1 - smoothingAlpha) + t.yMin * smoothingAlpha
                existing.xMax = existing.xMax * (1 - smoothingAlpha) + t.xMax * smoothingAlpha
                existing.yMax = existing.yMax * (1 - smoothingAlpha) + t.yMax * smoothingAlpha
                existing.vx = existing.vx * (1 - velocityAlpha) + newVx * velocityAlpha
                existing.vy = existing.vy * (1 - velocityAlpha) + newVy * velocityAlpha
                existing.label = t.label
                existing.rawLabel = t.rawLabel
                existing.confidence = t.confidence
                existing.lastSeenMs = now
                existing.missedCount = 0

                updatedTracks.add(existing)
            } else {
                // Track not matched this frame — keep predicting with velocity
                existing.missedCount++
                if (existing.missedCount <= maxMissedFrames) {
                    updatedTracks.add(existing)
                }
            }
        }

        // Add new unmatched targets as fresh tracks
        for (i in newTargets.indices) {
            if (!matched[i] && updatedTracks.size < maxTracks) {
                val t = newTargets[i]
                updatedTracks.add(SmoothedTrack(
                    t.xMin, t.yMin, t.xMax, t.yMax,
                    0f, 0f, t.label, t.rawLabel, t.confidence, now, 0
                ))
            }
        }

        smoothedTracks.clear()
        smoothedTracks.addAll(updatedTracks)
        targets = newTargets
        lastUpdateTimeMs = now

        // Start or stop continuous animation
        if (smoothedTracks.isNotEmpty() && !isAnimating) {
            isAnimating = true
            postInvalidateOnAnimation()
        } else if (smoothedTracks.isEmpty()) {
            isAnimating = false
            postInvalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.currentTimeMillis()
        val elapsed = (now - lastUpdateTimeMs) / 1000.0f // seconds since last detection

        val vWidth = width.toFloat()
        val vHeight = height.toFloat()
        if (vWidth == 0f || vHeight == 0f) return

        val scale = max(vWidth / camSourceWidth, vHeight / camSourceHeight)
        val scaledW = camSourceWidth * scale
        val scaledH = camSourceHeight * scale
        val dx = (vWidth - scaledW) / 2f
        val dy = (vHeight - scaledH) / 2f

        if (isYoloBoxesEnabled) {
            for (track in smoothedTracks) {
                if (track.confidence >= sensitivityThreshold) {
                    // Predict current position using velocity and elapsed time since last detection
                    val predXMin = (track.xMin + track.vx * elapsed).coerceIn(0f, 1f)
                    val predYMin = (track.yMin + track.vy * elapsed).coerceIn(0f, 1f)
                    val predXMax = (track.xMax + track.vx * elapsed).coerceIn(0f, 1f)
                    val predYMax = (track.yMax + track.vy * elapsed).coerceIn(0f, 1f)

                    val left = predXMin * scaledW + dx
                    val top = predYMin * scaledH + dy
                    val right = predXMax * scaledW + dx
                    val bottom = predYMax * scaledH + dy

                    val paintToUse = when (track.rawLabel.lowercase()) {
                        "person" -> personPaint
                        "plate" -> platePaint
                        "dog", "cat", "bird", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe" -> animalPaint
                        else -> vehiclePaint
                    }

                    // Render Main Box Outline
                    canvas.drawRect(left, top, right, bottom, paintToUse)

                    // Render Corner Brackets
                    drawTargetBrackets(canvas, left, top, right, bottom, paintToUse.color)

                    // Render Label Backdrop & Text
                    val labelText = "${track.label} [${(track.confidence * 100).toInt()}%]"
                    textPaint.color = paintToUse.color
                    val textWidth = textPaint.measureText(labelText)
                    val minMarginX = 12f
                    val labelLeft = left.coerceIn(minMarginX, max(minMarginX, vWidth - textWidth - 16f))
                    val labelTop = max(36f, top - 10f)

                    canvas.drawRect(labelLeft - 4f, labelTop - 32f, labelLeft + textWidth + 12f, labelTop + 6f, textBgPaint)
                    canvas.drawText(labelText, labelLeft + 4f, labelTop - 6f, textPaint)
                }
            }
        }

        // Draw mag track targets (circles + tethers) with velocity prediction
        if (isYoloBoxesEnabled) {
            magTrackTargets.forEach { target ->
                val predX = (target.relX + target.vx * elapsed).coerceIn(0f, 1f)
                val predY = (target.relY + target.vy * elapsed).coerceIn(0f, 1f)

                val pixelX = predX * scaledW + dx
                val pixelY = predY * scaledH + dy

                canvas.drawCircle(pixelX, pixelY, 8f, vehiclePaint)

                val anchor = when (target.id) {
                    "TRACK-01" -> PointF(vWidth * 0.15f, vHeight * 0.15f)
                    "TRACK-02" -> PointF(vWidth * 0.85f, vHeight * 0.15f)
                    "TRACK-03" -> PointF(vWidth * 0.15f, vHeight * 0.85f)
                    else -> PointF(vWidth * 0.85f, vHeight * 0.85f)
                }
                tetherPaint.alpha = 75
                canvas.drawLine(anchor.x, anchor.y, pixelX, pixelY, tetherPaint)
            }
        }

        // Continue animating for smooth prediction between detection frames
        if (isAnimating && smoothedTracks.isNotEmpty()) {
            postInvalidateOnAnimation()
        } else {
            isAnimating = false
        }
    }

    private fun drawTargetBrackets(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, colorInt: Int) {
        val bracket = 24f
        val bracketPaint = Paint().apply {
            color = colorInt
            strokeWidth = 6f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        // Top-Left
        canvas.drawLine(l, t, l + bracket, t, bracketPaint)
        canvas.drawLine(l, t, l, t + bracket, bracketPaint)
        // Top-Right
        canvas.drawLine(r - bracket, t, r, t, bracketPaint)
        canvas.drawLine(r, t, r, t + bracket, bracketPaint)
        // Bottom-Left
        canvas.drawLine(l, b - bracket, l, b, bracketPaint)
        canvas.drawLine(l + bracket, b, l, b, bracketPaint)
        // Bottom-Right
        canvas.drawLine(r - bracket, b, r, b, bracketPaint)
        canvas.drawLine(r, b - bracket, r, b, bracketPaint)
    }
}
