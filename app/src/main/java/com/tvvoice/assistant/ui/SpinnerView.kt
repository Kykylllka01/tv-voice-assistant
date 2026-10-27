package com.tvvoice.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** The small ring that turns in the search pill while the search runs. */
class SpinnerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#408FA4FF")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FF8FA4FF")
    }
    private val bounds = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val stroke = minOf(w, h) * 0.14f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        bounds.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawOval(bounds, track)
        val start = (System.currentTimeMillis() % PERIOD_MS) * 360f / PERIOD_MS
        canvas.drawArc(bounds, start - 90f, 90f, false, arc)
        if (isShown) postInvalidateOnAnimation()
    }

    private companion object {
        const val PERIOD_MS = 800L
    }
}
