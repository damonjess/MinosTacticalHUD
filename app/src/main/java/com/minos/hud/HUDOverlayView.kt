package com.minos.hud
 
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
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
 
    private var isAnimating = false
    private var trackCounter = 0
    private var lastDrawMs = 0L
 
    /**
     * One tracked object.
     *
     * The measurement fields (cx, cy, w, h, frameTimeMs) always hold the *raw* detector output and
     * the time the camera frame was captured. The d* fields are what is actually drawn: they are
     * eased every display frame toward "measurement + velocity * age", so the box keeps moving
     * smoothly at 60 Hz even though detections only arrive at inference rate.
     */
    private class Track(
        val id: String,
        var label: String,
        var rawLabel: String,
        var confidence: Float,
        var cx: Float, var cy: Float, var w: Float, var h: Float,
        var frameTimeMs: Long,
        var vx: Float = 0f, var vy: Float = 0f,          // centre velocity, normalised units / second
        var dCx: Float = cx, var dCy: Float = cy,         // displayed centre
        var dW: Float = w, var dH: Float = h,             // displayed size
        var missedCount: Int = 0,
        var framesTracked: Int = 1
    ) {
        val sizePct: Float get() = w * h * 100f
    }
 
    private var tracks = mutableListOf<Track>()
    private val maxTracks = 15
 
    // Applies an exponential decay to the velocity prediction window to prevent "braking overshoot".
    // A linear ageSec would project targets off into space if the tracker stutters for 1.5s while the object stops.
    // Damped age acts linearly for small time steps, but asymptotes to a max prediction distance (tau).
    private fun getDampedAge(ageSec: Float, tau: Float = 0.5f): Float {
        return tau * (1f - exp(-ageSec / tau))
    }
    private val maxVelocity = 2.5f            // normalised units / second (250% of screen per second)
    private val velocityDeadzone = 0.03f      // ignore drift below 3% of screen / second (parked vehicles)
    private val velocityBlend = 0.85f         // weight of the newest velocity measurement
    private val maxMissedFrames = 3
    private val maxLockedMissedFrames = 12
 
    private val tmpRect = RectF()
 
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
 
            for (track in tracks) {
                boxToScreen(track, scaledW, scaledH, dx, dy, tmpRect)
 
                // Expand touch area slightly
                val padding = 40f
                if (tapX in (tmpRect.left - padding)..(tmpRect.right + padding) &&
                    tapY in (tmpRect.top - padding)..(tmpRect.bottom + padding)
                ) {
                    val cx = tmpRect.centerX()
                    val cy = tmpRect.centerY()
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
 
    /** car / truck / bus flip between each other frame to frame on the same object, so treat them as one class. */
    private fun classGroup(raw: String): String {
        val l = raw.lowercase().trim()
        return if (l == "car" || l == "truck" || l == "bus") "vehicle" else l
    }
 
    /**
     * @param frameTimeMs when the camera frame these detections came from was captured, in
     *  SystemClock.elapsedRealtime() milliseconds. This is what lets the overlay compensate for
     *  the time the detector took: the box is drawn where the object is *now*, not where it was
     *  when the frame was grabbed.
     */
    fun updateTargets(newTargets: List<YoloTarget>, frameTimeMs: Long = SystemClock.elapsedRealtime()) {
        // ---- 1. Build every plausible (track, detection) pair, nearest first ----
        data class Match(val dist: Float, val trackIdx: Int, val detIdx: Int)
        val pairs = ArrayList<Match>()
 
        for ((ti, tr) in tracks.withIndex()) {
            val rawAgeSec = max(0f, (frameTimeMs - tr.frameTimeMs) / 1000f)
            val dampedAge = getDampedAge(rawAgeSec)
            val predCx = tr.cx + tr.vx * dampedAge
            val predCy = tr.cy + tr.vy * dampedAge
            val group = classGroup(tr.rawLabel)
            // Big boxes may legitimately move further between frames than small ones.
            val gate = max(0.15f, 1.2f * max(tr.w, tr.h)).coerceAtMost(0.60f)
 
            for ((di, d) in newTargets.withIndex()) {
                if (classGroup(d.rawLabel) != group) continue
                val dist = hypot((d.xMin + d.xMax) / 2f - predCx, (d.yMin + d.yMax) / 2f - predCy)
                if (dist < gate) pairs.add(Match(dist, ti, di))
            }
        }
        pairs.sortBy { it.dist }
 
        val trackUsed = BooleanArray(tracks.size)
        val detUsed = BooleanArray(newTargets.size)
 
        // ---- 2. Greedy assignment, update matched tracks ----
        for (p in pairs) {
            if (trackUsed[p.trackIdx] || detUsed[p.detIdx]) continue
            trackUsed[p.trackIdx] = true
            detUsed[p.detIdx] = true
 
            val tr = tracks[p.trackIdx]
            val d = newTargets[p.detIdx]
            val dCx = (d.xMin + d.xMax) / 2f
            val dCy = (d.yMin + d.yMax) / 2f
 
            // Velocity comes from consecutive RAW measurements over the time between the frames
            // themselves (not main-thread arrival time, not the eased box).
            val dt = (frameTimeMs - tr.frameTimeMs) / 1000f
            if (dt > 0.005f && dt < 0.5f) {
                var mvx = ((dCx - tr.cx) / dt).coerceIn(-maxVelocity, maxVelocity)
                var mvy = ((dCy - tr.cy) / dt).coerceIn(-maxVelocity, maxVelocity)
                if (abs(mvx) < velocityDeadzone) mvx = 0f
                if (abs(mvy) < velocityDeadzone) mvy = 0f
                tr.vx = tr.vx * (1f - velocityBlend) + mvx * velocityBlend
                tr.vy = tr.vy * (1f - velocityBlend) + mvy * velocityBlend
            } else if (dt >= 0.5f) {
                tr.vx = 0f
                tr.vy = 0f
            }
 
            tr.cx = dCx
            tr.cy = dCy
            tr.w = tr.w * 0.5f + (d.xMax - d.xMin) * 0.5f   // detector box size is noisy, smooth it lightly
            tr.h = tr.h * 0.5f + (d.yMax - d.yMin) * 0.5f
            tr.frameTimeMs = frameTimeMs
            tr.label = d.label
            tr.rawLabel = d.rawLabel
            tr.confidence = d.confidence
            tr.missedCount = 0
            tr.framesTracked++
        }
 
        // ---- 3. Age out unmatched tracks ----
        val kept = mutableListOf<Track>()
        var lockedLost = false
        for ((ti, tr) in tracks.withIndex()) {
            if (trackUsed[ti]) {
                kept.add(tr)
                continue
            }
            tr.missedCount++
            val limit = if (tr.id == lockedTrackId) maxLockedMissedFrames else maxMissedFrames
            if (tr.missedCount <= limit) {
                kept.add(tr)
            } else if (tr.id == lockedTrackId) {
                lockedLost = true
            }
        }
 
        // ---- 4. Spawn tracks for unmatched detections ----
        for ((di, d) in newTargets.withIndex()) {
            if (detUsed[di] || kept.size >= maxTracks) continue
            kept.add(
                Track(
                    id = if (d.id.startsWith("TRK-")) d.id else "TRK-${++trackCounter}",
                    label = d.label,
                    rawLabel = d.rawLabel,
                    confidence = d.confidence,
                    cx = (d.xMin + d.xMax) / 2f,
                    cy = (d.yMin + d.yMax) / 2f,
                    w = d.xMax - d.xMin,
                    h = d.yMax - d.yMin,
                    frameTimeMs = frameTimeMs
                )
            )
        }
 
        tracks = kept
        targets = newTargets
        if (lockedLost) releaseTarget()
 
        if (tracks.isNotEmpty() && !isAnimating) {
            isAnimating = true
            postInvalidateOnAnimation()
        } else if (tracks.isEmpty()) {
            isAnimating = false
            postInvalidate()
        }
    }
 
    /** Maps a track's *displayed* box to screen pixels. */
    private fun boxToScreen(t: Track, scaledW: Float, scaledH: Float, dx: Float, dy: Float, out: RectF) {
        val x1 = (t.dCx - t.dW / 2f).coerceIn(0f, 1f)
        val x2 = (t.dCx + t.dW / 2f).coerceIn(0f, 1f)
        val y1 = (t.dCy - t.dH / 2f).coerceIn(0f, 1f)
        val y2 = (t.dCy + t.dH / 2f).coerceIn(0f, 1f)
        out.set(x1 * scaledW + dx, y1 * scaledH + dy, x2 * scaledW + dx, y2 * scaledH + dy)
    }
 
    /** Moves every displayed box toward where its object should be right now. */
    private fun stepTracks(nowMs: Long) {
        val dtDraw = if (lastDrawMs == 0L) 0.016f else ((nowMs - lastDrawMs).coerceIn(1L, 100L)) / 1000f
        lastDrawMs = nowMs
 
        val profile = try { TrackingProfile.valueOf(activeProfile.uppercase()) } catch (e: Exception) { TrackingProfile.OUTDOOR }
        // Profile "smoothing alpha" (higher = snappier) -> easing time constant in seconds.
        // 0.80 -> 30 ms, 0.85 -> 22 ms. Frame-rate independent.
        val tau = ((1f - profile.boxSmoothingAlpha) * 0.15f).coerceAtLeast(0.01f)
        val ease = 1f - exp(-dtDraw / tau)
 
        for (t in tracks) {
            // Age of the measurement (capture -> now), plus a lead of one time-constant so the
            // easing filter does not add a steady lag while the object is moving.
            val rawAgeSec = max(0f, ((nowMs - t.frameTimeMs) / 1000f) + tau)
            // Locked targets use a looser dampening to keep tracking through longer occlusion/lag
            val dampTau = if (t.id == lockedTrackId) 0.75f else 0.5f
            val dampedAge = getDampedAge(rawAgeSec, dampTau)
 
            val targetCx = t.cx + t.vx * dampedAge
            val targetCy = t.cy + t.vy * dampedAge
 
            t.dCx += (targetCx - t.dCx) * ease
            t.dCy += (targetCy - t.dCy) * ease
            t.dW += (t.w - t.dW) * ease
            t.dH += (t.h - t.dH) * ease
        }
    }
 
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
 
        val now = SystemClock.elapsedRealtime()
        stepTracks(now)
 
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
            for (track in tracks) {
                // If we are locked onto a track, only draw that track
                if (lockedTrackId != null && track.id != lockedTrackId) {
                    continue
                }
 
                if (track.confidence >= sensitivityThreshold || track.id == lockedTrackId) {
                    boxToScreen(track, scaledW, scaledH, dx, dy, tmpRect)
                    val left = tmpRect.left
                    val top = tmpRect.top
                    val right = tmpRect.right
                    val bottom = tmpRect.bottom
 
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
 
                    // Label Formatting — keep it compact to avoid overlap
                    val shortName = track.rawLabel.uppercase()
                    val labelText = if (isLocked) {
                        val sizeStr = if (track.sizePct > 20f) "L" else if (track.sizePct > 5f) "M" else "S"
                        "$shortName $status ${(track.confidence * 100).toInt()}% S:$sizeStr"
                    } else {
                        "$shortName $status ${(track.confidence * 100).toInt()}%"
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
            val nowNs = System.nanoTime()
            magTrackTargets.forEach { target ->
                val rawAgeSec = max(0f, (nowNs - target.lastUpdateNs) / 1_000_000_000f)
                val dampedAge = getDampedAge(rawAgeSec)
                val predX = (target.relX + target.vx * dampedAge).coerceIn(0f, 1f)
                val predY = (target.relY + target.vy * dampedAge).coerceIn(0f, 1f)
 
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
 
        if (isAnimating && tracks.isNotEmpty()) {
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
