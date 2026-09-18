package com.minos.hud

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

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

    private val lockedPaint = Paint().apply {
        color = Color.parseColor("#FF0033")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val bracketPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#00FF66")
        textSize = 32f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    private val textBgPaint = Paint().apply {
        color = Color.argb(180, 3, 9, 15) // Semi-transparent dark background
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val tetherPaint = Paint().apply {
        color = Color.parseColor("#FFA500")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
        alpha = 40 // Reduced tether clutter
    }

    var magTrackTargets: List<MagTrackTarget> = emptyList()
    var targets: List<YoloTarget> = emptyList()
    var isYoloBoxesEnabled: Boolean = true
    var sensitivityThreshold: Float = 0.30f
    var activeProfile: String = "OUTDOOR"

    var lockedTrackId: String? = null
    var onTargetLocked: ((Boolean) -> Unit)? = null
    var onTargetLockedId: ((String?) -> Unit)? = null
    var onTapFocus: ((Float, Float) -> Unit)? = null

    private var camSourceWidth = 720f
    private var camSourceHeight = 1280f

    private var lastUpdateTimeMs: Long = System.currentTimeMillis()
    private var isAnimating = false
    private var trackCounter = 0

    private data class SmoothedTrack(
        var id: String,
        var xMin: Float, var yMin: Float, var xMax: Float, var yMax: Float,
        var vx: Float, var vy: Float,
        var label: String, var rawLabel: String,
        var confidence: Float,
        var lastSeenMs: Long,
        var missedCount: Int = 0,
        var framesTracked: Int = 0,
        var sizePct: Float = 0f
    )

    private val smoothedTracks = mutableListOf<SmoothedTrack>()
    private val maxTracks = 15

    private val maxPredictionTime = 0.10f    // Cap prediction at 100ms to prevent drift
    private val maxVelocity = 1.5f           // Max normalized velocity per second
    private val velocityDecay = 0.85f        // Velocity decay without detections
    private val maxMissedFrames = 2          // Expire tracks after 2 missed frames
    private val maxLockedMissedFrames = 10   // Keep locked target predicting longer
    private val matchDistanceThreshold = 0.12f

    fun setCameraSourceDimensions(width: Int, height: Int) {
        camSourceWidth = width.toFloat()
        camSourceHeight = height.toFloat()
    }

    fun releaseTarget() {
        lockedTrackId = null
        onTargetLocked?.invoke(false)
        onTargetLockedId?.invoke(null)
        postInvalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            performClick()
            val vWidth = width.toFloat()
            val vHeight = height.toFloat()
            if (vWidth == 0f || vHeight == 0f) return false

            val scale = max(vWidth / camSourceWidth, vHeight / camSourceHeight)
            val scaledW = camSourceWidth * scale
            val scaledH = camSourceHeight * scale
            val dx = (vWidth - scaledW) / 2f
            val dy = (vHeight - scaledH) / 2f

            val tapX = event.x
            val tapY = event.y

            // Find closest track that contains the tap point
            var bestTrackId: String? = null
            var bestDist = Float.MAX_VALUE

            for (track in smoothedTracks) {
                val left = track.xMin * scaledW + dx
                val top = track.yMin * scaledH + dy
                val right = track.xMax * scaledW + dx
                val bottom = track.yMax * scaledH + dy

                // Expand touch area slightly
                val padding = 40f
                if (tapX in (left - padding)..(right + padding) && tapY in (top - padding)..(bottom + padding)) {
                    val cx = (left + right) / 2f
                    val cy = (top + bottom) / 2f
                    val dist = (tapX - cx) * (tapX - cx) + (tapY - cy) * (tapY - cy)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestTrackId = track.id
                    }
                }
            }

            if (bestTrackId != null) {
                lockedTrackId = bestTrackId
                onTargetLocked?.invoke(true)
                onTargetLockedId?.invoke(bestTrackId)
                postInvalidate()
                return true
            } else {
                releaseTarget()
                onTapFocus?.invoke(tapX, tapY)
            }
        }
        return super.onTouchEvent(event)
    }

    fun updateTargets(newTargets: List<YoloTarget>) {
        val now = System.currentTimeMillis()
        val dt = maxOf(0.001f, (now - lastUpdateTimeMs) / 1000.0f)
        val profileEnum = try { TrackingProfile.valueOf(activeProfile.uppercase()) } catch (e: Exception) { TrackingProfile.OUTDOOR }
        val smoothingAlpha = profileEnum.boxSmoothingAlpha

        val matched = BooleanArray(newTargets.size) { false }
        val updatedTracks = mutableListOf<SmoothedTrack>()

        for (existing in smoothedTracks) {
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

                val tcx = (t.xMin + t.xMax) / 2f
                val tcy = (t.yMin + t.yMax) / 2f
                val ecx = (existing.xMin + existing.xMax) / 2f
                val ecy = (existing.yMin + existing.yMax) / 2f
                val newVx = ((tcx - ecx) / dt).coerceIn(-maxVelocity, maxVelocity)
                val newVy = ((tcy - ecy) / dt).coerceIn(-maxVelocity, maxVelocity)

                existing.xMin = existing.xMin * (1 - smoothingAlpha) + t.xMin * smoothingAlpha
                existing.yMin = existing.yMin * (1 - smoothingAlpha) + t.yMin * smoothingAlpha
                existing.xMax = existing.xMax * (1 - smoothingAlpha) + t.xMax * smoothingAlpha
                existing.yMax = existing.yMax * (1 - smoothingAlpha) + t.yMax * smoothingAlpha
                existing.vx = existing.vx * 0.30f + newVx * 0.70f
                existing.vy = existing.vy * 0.30f + newVy * 0.70f
                existing.label = t.label
                existing.rawLabel = t.rawLabel
                existing.confidence = t.confidence
                existing.lastSeenMs = now
                existing.missedCount = 0
                existing.framesTracked++
                existing.sizePct = ((existing.xMax - existing.xMin) * (existing.yMax - existing.yMin)) * 100f

                updatedTracks.add(existing)
            } else {
                existing.missedCount++
                val limit = if (existing.id == lockedTrackId) maxLockedMissedFrames else maxMissedFrames
                if (existing.missedCount <= limit) {
                    updatedTracks.add(existing)
                } else if (existing.id == lockedTrackId) {
                    // Lock lost completely
                    releaseTarget()
                }
            }
        }

        // Add new targets
        for (i in newTargets.indices) {
            if (!matched[i] && updatedTracks.size < maxTracks) {
                val t = newTargets[i]
                val id = "TRK-${++trackCounter}"
                updatedTracks.add(SmoothedTrack(
                    id = id,
                    xMin = t.xMin, yMin = t.yMin, xMax = t.xMax, yMax = t.yMax,
                    vx = 0f, vy = 0f,
                    label = t.label, rawLabel = t.rawLabel, confidence = t.confidence,
                    lastSeenMs = now, missedCount = 0, framesTracked = 1,
                    sizePct = ((t.xMax - t.xMin) * (t.yMax - t.yMin)) * 100f
                ))
            }
        }

        smoothedTracks.clear()
        
        // If there's a locked target, we can optionally filter out others to reduce clutter
        if (lockedTrackId != null) {
            val lockedTrack = updatedTracks.find { it.id == lockedTrackId }
            if (lockedTrack != null) {
                smoothedTracks.add(lockedTrack)
                // We keep only the locked track in the smoothed list if we want to "Track only that object"
                // But the background might still process them. 
                // Alternatively we add all, but draw only locked. We will just draw only locked in onDraw.
            }
            smoothedTracks.addAll(updatedTracks.filter { it.id != lockedTrackId })
        } else {
            smoothedTracks.addAll(updatedTracks)
        }
        
        targets = newTargets
        lastUpdateTimeMs = now

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
        val rawElapsed = (now - lastUpdateTimeMs) / 1000.0f
        val elapsed = min(rawElapsed, maxPredictionTime)

        if (rawElapsed > 0.03f) {
            for (track in smoothedTracks) {
                track.vx *= velocityDecay
                track.vy *= velocityDecay
            }
        }

        val vWidth = width.toFloat()
        val vHeight = height.toFloat()
        if (vWidth == 0f || vHeight == 0f) return

        val scale = max(vWidth / camSourceWidth, vHeight / camSourceHeight)
        val scaledW = camSourceWidth * scale
        val scaledH = camSourceHeight * scale
        val dx = (vWidth - scaledW) / 2f
        val dy = (vHeight - scaledH) / 2f

        val safeTop = 260f // Safe area below top controls bar

        if (isYoloBoxesEnabled) {
            for (track in smoothedTracks) {
                // If we are locked onto a track, only draw that track
                if (lockedTrackId != null && track.id != lockedTrackId) {
                    continue
                }

                if (track.confidence >= sensitivityThreshold || track.id == lockedTrackId) {
                    val pX1 = (track.xMin + track.vx * elapsed).coerceIn(0f, 1f)
                    val pY1 = (track.yMin + track.vy * elapsed).coerceIn(0f, 1f)
                    val pX2 = (track.xMax + track.vx * elapsed).coerceIn(0f, 1f)
                    val pY2 = (track.yMax + track.vy * elapsed).coerceIn(0f, 1f)

                    val predXMin = minOf(pX1, pX2)
                    val predXMax = maxOf(pX1, pX2)
                    val predYMin = minOf(pY1, pY2)
                    val predYMax = maxOf(pY1, pY2)

                    val left = predXMin * scaledW + dx
                    val top = predYMin * scaledH + dy
                    val right = predXMax * scaledW + dx
                    val bottom = predYMax * scaledH + dy

                    val isLocked = track.id == lockedTrackId
                    val paintToUse = if (isLocked) lockedPaint else when (track.rawLabel.lowercase()) {
                        "person" -> personPaint
                        "plate" -> platePaint
                        in ANIMAL_CLASSES -> animalPaint
                        else -> vehiclePaint
                    }

                    // Render Main Box Outline
                    canvas.drawRect(left, top, right, bottom, paintToUse)

                    // Render Corner Brackets
                    drawTargetBrackets(canvas, left, top, right, bottom, paintToUse.color, isLocked)

                    val status = if (isLocked) {
                        if (track.missedCount > 0) "PREDICTING" else "LOCKED"
                    } else {
                        if (track.missedCount > 0) "PREDICTING" else "TRACKING"
                    }

                    // Label Formatting
                    val shortName = track.rawLabel.uppercase()
                    val labelText = if (isLocked) {
                        val sizeStr = if (track.sizePct > 20f) "LGE" else if (track.sizePct > 5f) "MED" else "SML"
                        val motionStr = if (track.vy > 0.1f) "DWN" else if (track.vy < -0.1f) "UP" else "STABLE"
                        "$shortName $status | ${(track.confidence * 100).toInt()}% | S:$sizeStr M:$motionStr"
                    } else {
                        "$shortName $status | ${(track.confidence * 100).toInt()}%"
                    }

                    textPaint.color = paintToUse.color
                    val textWidth = textPaint.measureText(labelText)
                    val minMarginX = 12f
                    val labelLeft = left.coerceIn(minMarginX, max(minMarginX, vWidth - textWidth - 16f))
                    
                    // Position label safely inside or below top controls bar
                    val rawLabelTop = top - 8f
                    val labelTop = if (rawLabelTop < safeTop) {
                        minOf(top + 32f, bottom - 8f)
                    } else {
                        rawLabelTop
                    }

                    canvas.drawRect(labelLeft - 4f, labelTop - 28f, labelLeft + textWidth + 10f, labelTop + 6f, textBgPaint)
                    canvas.drawText(labelText, labelLeft + 4f, labelTop - 6f, textPaint)
                }
            }
        }

        // Draw mag track targets (circles + subtle tethers)
        if (isYoloBoxesEnabled) {
            magTrackTargets.forEach { target ->
                val predX = (target.relX + target.vx * elapsed).coerceIn(0f, 1f)
                val predY = (target.relY + target.vy * elapsed).coerceIn(0f, 1f)

                val pixelX = predX * scaledW + dx
                val pixelY = predY * scaledH + dy

                canvas.drawCircle(pixelX, pixelY, 8f, vehiclePaint)

                val anchor = when (target.id) {
                    "TRACK-01" -> PointF(vWidth * 0.15f, safeTop + 20f)
                    "TRACK-02" -> PointF(vWidth * 0.85f, safeTop + 20f)
                    "TRACK-03" -> PointF(vWidth * 0.15f, vHeight * 0.85f)
                    else -> PointF(vWidth * 0.85f, vHeight * 0.85f)
                }
                canvas.drawLine(anchor.x, anchor.y, pixelX, pixelY, tetherPaint)
            }
        }

        if (isAnimating && smoothedTracks.isNotEmpty()) {
            postInvalidateOnAnimation()
        } else {
            isAnimating = false
        }
    }

    private fun drawTargetBrackets(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, colorInt: Int, isLocked: Boolean = false) {
        val bracket = if (isLocked) 40f else 22f
        bracketPaint.color = colorInt
        bracketPaint.strokeWidth = if (isLocked) 8f else 5f
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
