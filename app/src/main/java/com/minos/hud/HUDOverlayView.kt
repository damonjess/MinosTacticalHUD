package com.minos.hud

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class HUDOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#00FF66")
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#00FF66")
        textSize = 42f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    private val tetherPaint = Paint().apply {
        color = Color.parseColor("#FFA500")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    var magTrackTargets: List<MagTrackTarget> = emptyList()
    var targets: List<YoloTarget> = emptyList()
    var isYoloBoxesEnabled: Boolean = true
    var sensitivityThreshold: Float = 0.5f

    private var camSourceWidth = 720f
    private var camSourceHeight = 1280f

    fun setCameraSourceDimensions(width: Int, height: Int) {
        camSourceWidth = width.toFloat()
        camSourceHeight = height.toFloat()
    }

    fun updateTargets(newTargets: List<YoloTarget>) {
        targets = newTargets
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val vWidth = width.toFloat()
        val vHeight = height.toFloat()
        if (vWidth == 0f || vHeight == 0f) return

        // Compute FILL_CENTER transform mapping
        val scale = max(vWidth / camSourceWidth, vHeight / camSourceHeight)
        val scaledW = camSourceWidth * scale
        val scaledH = camSourceHeight * scale
        val dx = (vWidth - scaledW) / 2f
        val dy = (vHeight - scaledH) / 2f

        // Draw YOLO Target Bounding Boxes
        if (isYoloBoxesEnabled) {
            for (target in targets) {
                if (target.confidence >= sensitivityThreshold) {
                    val left = target.xMin * scaledW + dx
                    val top = target.yMin * scaledH + dy
                    val right = target.xMax * scaledW + dx
                    val bottom = target.yMax * scaledH + dy

                    // Render Main Box
                    canvas.drawRect(left, top, right, bottom, boxPaint)

                    // Render Corner Brackets
                    drawTargetBrackets(canvas, left, top, right, bottom)

                    // Render Label
                    canvas.drawText(
                        "${target.label} [${(target.confidence * 100).toInt()}%]",
                        (left + right) / 2f,
                        top - 15f,
                        textPaint
                    )
                }
            }
        }

        // Draw Mag-Track Tethers
        if (isYoloBoxesEnabled) {
            magTrackTargets.forEach { target ->
                val pixelX = target.relX * scaledW + dx
                val pixelY = target.relY * scaledH + dy

                canvas.drawCircle(pixelX, pixelY, 8f, boxPaint)

                val anchor = when (target.id) {
                    "TRACK-01" -> PointF(vWidth * 0.15f, vHeight * 0.15f)
                    "TRACK-02" -> PointF(vWidth * 0.85f, vHeight * 0.15f)
                    "TRACK-03" -> PointF(vWidth * 0.15f, vHeight * 0.85f)
                    else -> PointF(vWidth * 0.85f, vHeight * 0.85f)
                }
                tetherPaint.alpha = 120
                canvas.drawLine(anchor.x, anchor.y, pixelX, pixelY, tetherPaint)
            }
        }
    }

    private fun drawTargetBrackets(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val bracket = 24f
        val bracketPaint = Paint(boxPaint).apply {
            color = Color.parseColor("#FF3366")
            strokeWidth = 6f
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
