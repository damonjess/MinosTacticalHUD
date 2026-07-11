package com.minos.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import ai.onnxruntime.OrtSession

class HUDOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    var detections: List<Detection> = emptyList()

    fun setSession(session: OrtSession?) {
        // Keep reference if needed
    }

    fun updateDetections(newDetections: List<Detection>) {
        detections = newDetections
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw central reticle
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, 30f, paint)

        // Draw detections
        for (det in detections) {
            canvas.drawRect(det.x, det.y, det.x + det.width, det.y + det.height, paint)
        }
    }
}

data class Detection(
    val x: Float, val y: Float,
    val width: Float, val height: Float,
    val label: String,
    val confidence: Float
)
