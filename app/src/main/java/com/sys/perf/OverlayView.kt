package com.sys.perf

import android.content.Context
import android.graphics.*
import android.view.View
import android.view.WindowManager

class OverlayView(context: Context) : View(context) {

    private val fovPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f
        color       = Color.argb(90, 255, 255, 255)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 255, 55, 55)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 1.2f
        color       = Color.argb(160, 255, 255, 255)
    }

    var locked    = false
    var fovRadius = 220f

    override fun onDraw(canvas: Canvas) {
        val cx = width  / 2f
        val cy = height / 2f

        fovPaint.color = if (locked)
            Color.argb(150, 255, 50, 50)
        else
            Color.argb(80,  255, 255, 255)

        canvas.drawCircle(cx, cy, fovRadius, fovPaint)
        // Crosshair
        canvas.drawLine(cx - 12f, cy, cx + 12f, cy, crossPaint)
        canvas.drawLine(cx, cy - 12f, cx, cy + 12f, crossPaint)
        // Lock dot
        if (locked) canvas.drawCircle(cx, cy, 4f, dotPaint)
    }

    companion object {
        fun attach(ctx: Context, wm: WindowManager): OverlayView {
            val v = OverlayView(ctx)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, p)
            return v
        }
    }
}
