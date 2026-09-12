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
    }

    var magTrackTargets: List<MagTrackTarget> = emptyList()
    var targets: List<YoloTarget> = emptyList()
    var isYoloBoxesEnabled: Boolean = true
    var sensitivityThreshold: Float = 0.25f

    private var camSourceWidth = 720f
    private var camSourceHeight = 1280f

    fun setCameraSourceDimensions(width: Int, height: Int) {
        camSourceWidth = width.toFloat()
        camSourceHeight = height.toFloat()
    }

    fun updateTargets(newTargets: List<YoloTarget>) {
        Log.d("HUDOverlayView", "Received targets=${newTargets.size}")
        targets = newTargets
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val vWidth = width.toFloat()
        val vHeight = height.toFloat()
        if (vWidth == 0f || vHeight == 0f) return

        val scale = max(vWidth / camSourceWidth, vHeight / camSourceHeight)
        val scaledW = camSourceWidth * scale
        val scaledH = camSourceHeight * scale
        val dx = (vWidth - scaledW) / 2f
        val dy = (vHeight - scaledH) / 2f

        if (isYoloBoxesEnabled) {
            for (target in targets) {
                if (target.confidence >= sensitivityThreshold) {
                    val left = target.xMin * scaledW + dx
                    val top = target.yMin * scaledH + dy
                    val right = target.xMax * scaledW + dx
                    val bottom = target.yMax * scaledH + dy

                    val paintToUse = when (target.rawLabel.lowercase()) {
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
                    val labelText = "${target.label} [${(target.confidence * 100).toInt()}%]"
                    textPaint.color = paintToUse.color
                    val textWidth = textPaint.measureText(labelText)
                    val labelTop = max(30f, top - 10f)
                    
                    canvas.drawRect(left, labelTop - 32f, left + textWidth + 16f, labelTop + 6f, textBgPaint)
                    canvas.drawText(labelText, left + 8f, labelTop - 6f, textPaint)
                }
            }
        }

        if (isYoloBoxesEnabled) {
            magTrackTargets.forEach { target ->
                val pixelX = target.relX * scaledW + dx
                val pixelY = target.relY * scaledH + dy

                canvas.drawCircle(pixelX, pixelY, 8f, vehiclePaint)

                val anchor = when (target.id) {
                    "TRACK-01" -> PointF(vWidth * 0.15f, vHeight * 0.15f)
                    "TRACK-02" -> PointF(vWidth * 0.85f, vHeight * 0.15f)
                    "TRACK-03" -> PointF(vWidth * 0.15f, vHeight * 0.85f)
                    else -> PointF(vWidth * 0.85f, vHeight * 0.85f)
                }
                tetherPaint.alpha = 255
                canvas.drawLine(anchor.x, anchor.y, pixelX, pixelY, tetherPaint)
            }
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
