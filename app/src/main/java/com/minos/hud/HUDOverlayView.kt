package com.minos.hud

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import ai.onnxruntime.OrtSession

class HUDOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.parseColor("#00FF00")  // Bright tactical green
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#00FF00")
        textSize = 48f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val centerPaint = Paint().apply {
        color = Color.parseColor("#00FF41")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    var detections: List<Detection> = emptyList()
    var currentReticleStyle: ReticleStyle = ReticleStyle.CROSSHAIR

    enum class ReticleStyle {
        CROSSHAIR, CIRCLE, MILITARY, FIGHTER_JET
    }

    fun setSession(session: OrtSession?) { /* keep for future */ }

    fun updateDetections(newDetections: List<Detection>) {
        detections = newDetections
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        when (currentReticleStyle) {
            ReticleStyle.CROSSHAIR -> drawCrosshair(canvas, cx, cy)
            ReticleStyle.CIRCLE -> drawCircleReticle(canvas, cx, cy)
            ReticleStyle.MILITARY -> drawMilitaryReticle(canvas, cx, cy)
            ReticleStyle.FIGHTER_JET -> drawFighterJetHUD(canvas, cx, cy)
        }

        // Draw detections
        for (det in detections) {
            canvas.drawRect(det.x, det.y, det.x + det.width, det.y + det.height, paint)

            // Label
            canvas.drawText(
                "${det.label} ${(det.confidence * 100).toInt()}%",
                det.x + det.width / 2,
                det.y - 10,
                textPaint
            )
        }
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
