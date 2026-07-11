package com.minos.hud

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import ai.onnxruntime.OrtSession

data class YoloTarget(
    val id: String,
    val label: String,
    val confidence: Float,
    // Normalized coordinates (0.0f to 1.0f) relative to screen space
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float
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

    private var scanProgress = 0.1f
    private var scanDirection = 1
    private val scanStep = 0.005f

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

        // Dynamic Scanning Line
        drawScanningLine(canvas, width, height)

        // Target Acquisition Corner Box Brackets
        drawCornerBrackets(canvas, width, height)

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
                    canvas.drawRect(left, top, right, bottom, paint)

                    // Target Corner Brackets (Crosshair feel)
                    drawTargetBrackets(canvas, left, top, right, bottom)

                    // Label
                    canvas.drawText(
                        "${target.label} [${(target.confidence * 100).toInt()}%]",
                        (left + right) / 2,
                        top - 10,
                        textPaint
                    )
                }
            }
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

    private fun drawScanningLine(canvas: Canvas, width: Float, height: Float) {
        val y = height * scanProgress
        canvas.drawLine(0f, y, width, y, scanPaint)

        scanProgress += scanStep * scanDirection
        if (scanProgress >= 0.9f || scanProgress <= 0.1f) {
            scanDirection *= -1
        }
        postInvalidateDelayed(16) // Aim for ~60fps animation
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
