package com.minos.hud

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import ai.onnxruntime.OrtSession

data class MagTrackTarget(
    val id: String,
    val trackLabel: String,
    val coordinateLabel: String,
    // Relative coordinates (0f to 1f)
    val relX: Float,
    val relY: Float,
    val crop: Bitmap? = null
)

data class YoloTarget(
    val id: String,
    val label: String,
    val confidence: Float,
    // Normalized coordinates (0.0f to 1.0f) relative to screen space
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
    val crop: Bitmap? = null
)

class HUDOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.parseColor("#00FF66")  // panoptic_neon_green
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#00FF66")
        textSize = 48f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val centerPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")  // panoptic_neon_cyan
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val scanPaint = Paint().apply {
        color = Color.parseColor("#00FF66").let { c ->
            Color.argb(102, Color.red(c), Color.green(c), Color.blue(c))
        }
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#00FF66")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
        alpha = 25 // very faint
    }

    private val scanlinePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 2f
        style = Paint.Style.STROKE
        alpha = 40
    }

    private val telemetryPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        textSize = 32f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    private val radarPaint = Paint().apply {
        color = Color.parseColor("#00FF66")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val tetherPaint = Paint().apply {
        color = Color.parseColor("#FFA500")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private var scanProgress = 0.1f
    private var scanDirection = 1
    private val scanStep = 0.005f

    private var radarAngle = 0f
    private val radarStep = 2f
    private var frameTicker = 0

    var magTrackTargets: List<MagTrackTarget> = emptyList()
    var targets: List<YoloTarget> = emptyList()
    var currentReticleStyle: ReticleStyle = ReticleStyle.CROSSHAIR
    var isYoloBoxesEnabled: Boolean = true
    var sensitivityThreshold: Float = 0.5f

    enum class ReticleStyle {
        CROSSHAIR, CIRCLE, MILITARY, FIGHTER_JET
    }

    fun setSession(session: OrtSession?) { /* keep for future */ }

    fun updateTargets(newTargets: List<YoloTarget>) {
        targets = newTargets
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val tacticalAmber = Color.parseColor("#FFA500")

        frameTicker++

        // 0. Background Effects
        drawGrid(canvas, width, height)
        drawScanlines(canvas, width, height)
        drawVignette(canvas, width, height)

        // 1. Central Targeting Reticle (Tactical Amber)
        drawCentralReticle(canvas, width, height, tacticalAmber)

        // 2. Sonar Radar Element (Positioned Right Middle)
        drawSonarRadar(canvas, width, height)

        // 3. Dynamic Scanning Line
        drawScanningLine(canvas, width, height)

        // 4. Target Acquisition Corner Box Brackets
        drawCornerBrackets(canvas, width, height)

        // 5. Side Telemetry Bars
        drawSideTelemetry(canvas, width, height)

        // 6. Top Compass (DISABLED - REPLACED BY COMPOSE HEADER)
        // drawCompass(canvas, width, height)

        // 7. Status Text (DISABLED - REPLACED BY COMPOSE PANEL)
        // drawStatusText(canvas, width, height)

        when (currentReticleStyle) {
            ReticleStyle.CROSSHAIR -> drawCrosshair(canvas, cx, cy)
            ReticleStyle.CIRCLE -> drawCircleReticle(canvas, cx, cy)
            ReticleStyle.MILITARY -> drawMilitaryReticle(canvas, cx, cy)
            ReticleStyle.FIGHTER_JET -> drawFighterJetHUD(canvas, cx, cy)
        }

        // Draw targets using normalized coordinates
        if (isYoloBoxesEnabled) {
            for (target in targets) {
                if (target.confidence >= sensitivityThreshold) {
                    val left = target.xMin * width
                    val top = target.yMin * height
                    val right = target.xMax * width
                    val bottom = target.yMax * height

                    // Primary Target Box Bounding Rect
                    paint.alpha = if (frameTicker % 10 < 5) 150 else 255 // Subtle flicker
                    canvas.drawRect(left, top, right, bottom, paint)

                    // Target Corner Brackets (Crosshair feel)
                    drawTargetBrackets(canvas, left, top, right, bottom)

                    // Label
                    textPaint.color = Color.parseColor("#00FF66")
                    canvas.drawText(
                        "${target.label} [${(target.confidence * 100).toInt()}%]",
                        (left + right) / 2,
                        top - 15,
                        textPaint
                    )
                    
                    // Add some extra tech info below the box
                    val infoText = "DIST: ${(10..99).random()}m | AZ: ${(0..359).random()}°"
                    textPaint.textSize = 30f
                    canvas.drawText(infoText, (left + right) / 2, bottom + 35, textPaint)
                    textPaint.textSize = 48f
                }
            }
        }

        // 6. Draw Mag-Track Targets and Tethers
        drawMagTrackTethers(canvas, width, height)
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val gridSize = 100f
        var x = 0f
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += gridSize
        }
        var y = 0f
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += gridSize
        }
    }

    private fun drawScanlines(canvas: Canvas, w: Float, h: Float) {
        var y = 0f
        while (y < h) {
            canvas.drawLine(0f, y, w, y, scanlinePaint)
            y += 6f
        }
    }

    private fun drawSideTelemetry(canvas: Canvas, w: Float, h: Float) {
        val barWidth = 40f
        val barHeight = h * 0.4f
        val margin = 60f
        
        // Left altitude bar
        val leftBarX = margin
        val barTop = (h - barHeight) / 2
        canvas.drawRect(leftBarX, barTop, leftBarX + barWidth, barTop + barHeight, telemetryPaint.apply { style = Paint.Style.STROKE; alpha = 100 })
        
        // Right pitch bar
        val rightBarX = w - margin - barWidth
        canvas.drawRect(rightBarX, barTop, rightBarX + barWidth, barTop + barHeight, telemetryPaint.apply { style = Paint.Style.STROKE; alpha = 100 })
        
        // Scale marks
        telemetryPaint.style = Paint.Style.FILL
        telemetryPaint.alpha = 255
        for (i in 0..10) {
            val markY = barTop + (i * barHeight / 10)
            canvas.drawLine(leftBarX, markY, leftBarX + 15, markY, telemetryPaint)
            canvas.drawLine(rightBarX + barWidth - 15, markY, rightBarX + barWidth, markY, telemetryPaint)
            
            if (i % 2 == 0) {
                canvas.drawText("${100 - i * 10}", leftBarX + barWidth + 5, markY + 10, telemetryPaint.apply { textSize = 20f })
            }
        }
        telemetryPaint.textSize = 32f
    }

    private fun drawCentralReticle(canvas: Canvas, w: Float, h: Float, color: Int) {
        val paint = Paint().apply {
            this.color = color
            this.strokeWidth = 2f
            this.style = Paint.Style.STROKE
            this.isAntiAlias = true
        }
        val cx = w / 2f
        val cy = h / 2f

        paint.alpha = 76 // 0.3f
        canvas.drawCircle(cx, cy, w * 0.3f, paint)
        
        paint.alpha = 255
        canvas.drawCircle(cx, cy, 16f, paint)
        canvas.drawLine(cx - 30, cy, cx + 30, cy, paint)
        canvas.drawLine(cx, cy - 30, cx, cy + 30, paint)
    }

    private fun drawSonarRadar(canvas: Canvas, w: Float, h: Float) {
        val radarCenter = PointF(w * 0.9f, h * 0.5f)
        
        radarPaint.alpha = 127 // 0.5f
        canvas.drawCircle(radarCenter.x, radarCenter.y, 50f, radarPaint)
        
        radarPaint.alpha = 51 // 0.2f
        canvas.drawCircle(radarCenter.x, radarCenter.y, 25f, radarPaint)

        // Sweep line
        val rad = Math.toRadians(radarAngle.toDouble())
        val sweepEnd = PointF(
            (radarCenter.x + 50 * Math.cos(rad)).toFloat(),
            (radarCenter.y + 50 * Math.sin(rad)).toFloat()
        )
        radarPaint.alpha = 255
        canvas.drawLine(radarCenter.x, radarCenter.y, sweepEnd.x, sweepEnd.y, radarPaint)

        radarAngle = (radarAngle + radarStep) % 360
    }

    private fun drawMagTrackTethers(canvas: Canvas, w: Float, h: Float) {
        val greenMatrix = Color.parseColor("#00FF66")
        val paint = Paint().apply {
            this.color = greenMatrix
            this.strokeWidth = 2f
            this.style = Paint.Style.STROKE
            this.isAntiAlias = true
        }

        magTrackTargets.forEach { target ->
            val targetPixelX = target.relX * w
            val targetPixelY = target.relY * h

            // Target box crosshairs
            canvas.drawCircle(targetPixelX, targetPixelY, 8f, paint)
            
            // Draw tether connections to sub windows
            val windowAnchor = when(target.id) {
                "TRACK-01" -> PointF(w * 0.15f, h * 0.15f)
                "TRACK-02" -> PointF(w * 0.85f, h * 0.15f)
                "TRACK-03" -> PointF(w * 0.15f, h * 0.85f)
                else -> PointF(w * 0.85f, h * 0.85f)
            }
            
            tetherPaint.alpha = 102 // 0.4f
            canvas.drawLine(windowAnchor.x, windowAnchor.y, targetPixelX, targetPixelY, tetherPaint)
        }
    }

    private fun drawTargetBrackets(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val bracket = 20f
        val alertRed = Color.parseColor("#FF3366")
        val bracketPaint = Paint(paint).apply {
            color = alertRed
            strokeWidth = 5f
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

    private fun drawVignette(canvas: Canvas, w: Float, h: Float) {
        val radius = Math.sqrt((w * w + h * h).toDouble()).toFloat() / 2f
        val vignettePaint = Paint().apply {
            isAntiAlias = true
            shader = RadialGradient(w / 2, h / 2, radius, 
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.parseColor("#80000000")),
                floatArrayOf(0f, 0.7f, 1f),
                Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, vignettePaint)
    }

    private fun drawScanningLine(canvas: Canvas, width: Float, height: Float) {
        val y = height * scanProgress
        
        val sweepPaint = Paint(scanPaint).apply {
            shader = LinearGradient(0f, y - 50f, 0f, y, 
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#3300FF66"), Color.parseColor("#00FF66")),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, y - 50f, width, y, sweepPaint)
        canvas.drawLine(0f, y, width, y, scanPaint)

        scanProgress += scanStep * scanDirection
        if (scanProgress >= 0.95f || scanProgress <= 0.05f) {
            scanDirection *= -1
        }
        postInvalidateDelayed(16) // Aim for ~60fps animation
    }

    private fun drawCompass(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val topY = 60f
        val compassWidth = w * 0.6f
        val startX = cx - compassWidth / 2
        
        telemetryPaint.textAlign = Paint.Align.CENTER
        canvas.drawLine(startX, topY, startX + compassWidth, topY, telemetryPaint)
        
        for (i in -45..45 step 5) {
            val offset = (i * (compassWidth / 90f))
            val x = cx + offset
            val markHeight = if (i % 15 == 0) 25f else 12f
            canvas.drawLine(x, topY, x, topY + markHeight, telemetryPaint)
            
            if (i % 15 == 0) {
                val label = when(i) {
                    0 -> "N"
                    else -> "${(360 + i) % 360}"
                }
                telemetryPaint.textSize = 24f
                canvas.drawText(label, x, topY + 55f, telemetryPaint)
            }
        }
        telemetryPaint.textSize = 32f
    }

    private fun drawStatusText(canvas: Canvas, w: Float, h: Float) {
        val margin = 60f
        val bottomY = h - 150f
        
        val status = if (frameTicker % 60 < 30) "SYSTEM ACTIVE // LINK STABLE" else "SCANNING... // TRACING"
        telemetryPaint.textAlign = Paint.Align.LEFT
        telemetryPaint.color = Color.parseColor("#00FF66")
        canvas.drawText(status, margin, bottomY, telemetryPaint)
        
        val targetCount = "TARGETS: ${targets.size}"
        telemetryPaint.textAlign = Paint.Align.RIGHT
        telemetryPaint.color = Color.parseColor("#00E5FF")
        canvas.drawText(targetCount, w - margin, bottomY, telemetryPaint)
    }

    private fun drawCornerBrackets(canvas: Canvas, width: Float, height: Float) {
        val boxW = width * 0.7f
        val boxH = height * 0.45f
        val left = (width - boxW) / 2
        val top = (height - boxH) / 2
        val right = left + boxW
        val bottom = top + boxH
        val len = 40f

        val path = Path()
        // Top Left
        path.moveTo(left, top + len)
        path.lineTo(left, top)
        path.lineTo(left + len, top)
        // Top Right
        path.moveTo(right - len, top)
        path.lineTo(right, top)
        path.lineTo(right, top + len)
        // Bottom Left
        path.moveTo(left, bottom - len)
        path.lineTo(left, bottom)
        path.lineTo(left + len, bottom)
        // Bottom Right
        path.moveTo(right - len, bottom)
        path.lineTo(right, bottom)
        path.lineTo(right, bottom + len)

        canvas.drawPath(path, centerPaint)
    }

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float) {
        val size = 60f
        canvas.drawLine(cx - size, cy, cx + size, cy, centerPaint)
        canvas.drawLine(cx, cy - size, cx, cy + size, centerPaint)
        canvas.drawCircle(cx, cy, 25f, centerPaint)
    }

    private fun drawCircleReticle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, 45f, centerPaint)
        canvas.drawCircle(cx, cy, 55f, centerPaint)
        drawCrosshair(canvas, cx, cy)
    }

    private fun drawMilitaryReticle(canvas: Canvas, cx: Float, cy: Float) {
        val size = 70f
        canvas.drawLine(cx - size, cy, cx + size, cy, centerPaint)
        canvas.drawLine(cx, cy - size, cx, cy + size, centerPaint)
        canvas.drawRect(cx - 40, cy - 40, cx + 40, cy + 40, centerPaint)
    }

    private fun drawFighterJetHUD(canvas: Canvas, cx: Float, cy: Float) {
        // Classic fighter jet style
        val size = 80f
        canvas.drawLine(cx - size, cy, cx + size, cy, centerPaint)
        canvas.drawLine(cx, cy - size * 0.6f, cx, cy + size * 0.6f, centerPaint)

        // Corner ticks
        for (i in 0..3) {
            val angle = i * 90f
            val rad = Math.toRadians(angle.toDouble())
            val x = (cx + 60 * Math.cos(rad)).toFloat()
            val y = (cy + 60 * Math.sin(rad)).toFloat()
            canvas.drawLine(x - 15, y, x + 15, y, centerPaint)
        }

        canvas.drawCircle(cx, cy, 35f, centerPaint)
    }
}

data class Detection(
    val x: Float, val y: Float,
    val width: Float, val height: Float,
    val label: String,
    val confidence: Float
)
