package com.tvvoice.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/** Five rounded bars beside "Listening": they bounce on their own and grow with the voice level. */
class WaveView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6E8AFF") }
    private val bar = RectF()
    private var level = 0f
    private var target = 0f

    fun setLevel(value: Float) {
        target = value.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        level += (target - level) * 0.2f
        val n = PHASES.size
        val gap = width / (n * 2f - 1f)
        val t = System.currentTimeMillis() / 1000.0
        for (i in 0 until n) {
            val wave = ((sin(2 * PI * t / PERIOD_S + PHASES[i]) + 1) / 2).toFloat()
            val h = height * (0.3f + 0.7f * wave * (0.45f + 0.55f * level))
            val x = i * gap * 2
            bar.set(x, (height - h) / 2, x + gap, (height + h) / 2)
            canvas.drawRoundRect(bar, gap / 2, gap / 2, paint)
        }
        if (isShown) postInvalidateOnAnimation()
    }

    private companion object {
        const val PERIOD_S = 0.9
        val PHASES = doubleArrayOf(0.0, 1.05, 3.14, 2.1, 4.2)
    }
}
