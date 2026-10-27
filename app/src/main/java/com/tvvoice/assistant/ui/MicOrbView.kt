package com.tvvoice.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The orb at the left of the answer, the assistant's avatar. Listening, rings ripple out and the orb swells with the voice level;
 * thinking, a bright arc runs round it; speaking, it breathes; after an error it goes grey. Drawn by hand and
 * animated only while on screen.
 */
class MicOrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    var mode = Mode.IDLE
        set(value) {
            if (field != value) modeStarted = now()
            field = value
            invalidate()
        }

    private var target = 0f
    private var level = 0f
    private var modeStarted = now()
    private val ripples = ArrayDeque<Long>()
    private var lastRipple = 0L

    private val orb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bounds = RectF()

    /** The voice level from the recognizer, 0 to 1; smoothed before it is drawn. */
    fun setLevel(value: Float) {
        target = value.coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f
        orb.shader = RadialGradient(cx - r * 0.15f, cy - r * 0.2f, r * 0.62f,
            intArrayOf(Color.parseColor("#FFE3E9FF"), Color.parseColor("#FF8FA4FF"), Color.parseColor("#FF4D6BFE")),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(cx, cy, r,
            intArrayOf(Color.parseColor("#804D6BFE"), Color.parseColor("#2A4D6BFE"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        arc.shader = SweepGradient(cx, cy,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#FFB4C2FF"), Color.parseColor("#FF4D6BFE"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 0.85f, 1f))
        arc.strokeWidth = r * 0.1f
        ring.strokeWidth = r * 0.08f
    }

    override fun onDraw(canvas: Canvas) {
        val t = now()
        level += (target - level) * 0.25f
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val base = r * 0.5f
        val since = (t - modeStarted) / 1000f

        val scale = when (mode) {
            Mode.LISTENING -> 0.94f + level * 0.2f
            Mode.SPEAKING -> 1.01f + 0.055f * sin(since * 2 * PI.toFloat() / 1.5f)
            Mode.THINKING -> 0.9f
            Mode.IDLE -> 1.01f + 0.055f * sin(since * 2 * PI.toFloat() / 3.2f)
            Mode.ERROR -> 1f
        }

        if (mode != Mode.ERROR) canvas.drawCircle(cx, cy, base * (1.25f + level * 0.3f), glow)

        if (mode == Mode.LISTENING) {
            val gap = (650 - level * 300).toLong()
            if (t - lastRipple > gap) {
                ripples.addLast(t)
                lastRipple = t
            }
        }
        while (ripples.isNotEmpty() && t - ripples.first() > RIPPLE_MS) ripples.removeFirst()
        for (born in ripples) {
            val p = (t - born) / RIPPLE_MS.toFloat()
            ring.color = Color.argb((150 * (1 - p)).toInt(), 120, 145, 255)
            canvas.drawCircle(cx, cy, base * (0.95f + 0.75f * p), ring)
        }

        orb.colorFilter = if (mode == Mode.ERROR) GREY else null
        canvas.drawCircle(cx, cy, base * scale, orb)

        if (mode == Mode.THINKING) {
            val a = r - arc.strokeWidth
            bounds.set(cx - a, cy - a, cx + a, cy + a)
            canvas.save()
            canvas.rotate(since * 400f % 360f, cx, cy)
            canvas.drawArc(bounds, 0f, 320f, false, arc)
            canvas.restore()
        }

        // Resting (idle, error) it only breathes slowly: 30 frames a second are plenty and spare the TV's CPU.
        if (isShown) {
            if (mode == Mode.IDLE || mode == Mode.ERROR) postInvalidateDelayed(33) else postInvalidateOnAnimation()
        }
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val RIPPLE_MS = 1300L
        val GREY = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix().apply { setScale(0.55f, 0.55f, 0.55f, 1f) })
        })
    }
}
