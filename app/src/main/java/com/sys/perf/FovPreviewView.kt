package com.sys.perf

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class FovPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 3f
        color       = Color.rgb(255, 70, 70)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 2f
        color       = Color.WHITE
    }

    private var radiusDp = 160f

    fun setRadiusDp(value: Float) { radiusDp = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx  = width  / 2f
        val cy  = height / 2f
        val density = resources.displayMetrics.density
        val r   = (radiusDp * density).coerceAtMost((minOf(width, height) / 2f) - 14f)

        canvas.drawCircle(cx, cy, r, circlePaint)
        canvas.drawLine(cx - 18f, cy, cx + 18f, cy, crossPaint)
        canvas.drawLine(cx, cy - 18f, cx, cy + 18f, crossPaint)
    }
}
