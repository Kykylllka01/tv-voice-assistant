package com.tvvoice.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.TextView

/**
 * A status line that can shimmer: with [shimmer] on, a band of light runs across the grey text again and again,
 * the way a chat app shows "Thinking". Off, it is a plain TextView.
 */
class ShimmerTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    TextView(context, attrs) {
    private var gradient: LinearGradient? = null
    private val shift = Matrix()

    var shimmer = false
        set(value) {
            field = value
            paint.shader = if (value) gradient else null
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val band = paint.measureText(text.toString()).coerceAtLeast(1f)
        val dim = Color.parseColor("#FF8E8E98")
        gradient = LinearGradient(0f, 0f, band * 2, 0f,
            intArrayOf(dim, dim, Color.parseColor("#FFF8FAFF"), dim, dim),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f), Shader.TileMode.REPEAT)
        if (shimmer) paint.shader = gradient
    }

    override fun onDraw(canvas: Canvas) {
        val g = gradient
        if (shimmer && g != null) {
            val band = paint.measureText(text.toString()).coerceAtLeast(1f) * 2
            val p = (System.currentTimeMillis() % PERIOD_MS) / PERIOD_MS.toFloat()
            shift.setTranslate(band * (1 - 2 * p), 0f)
            g.setLocalMatrix(shift)
        }
        super.onDraw(canvas)
        if (shimmer && isShown) postInvalidateOnAnimation()
    }

    private companion object {
        const val PERIOD_MS = 1500L
    }
}
