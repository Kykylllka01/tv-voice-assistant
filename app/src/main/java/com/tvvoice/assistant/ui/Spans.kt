package com.tvvoice.assistant.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import kotlin.math.PI
import kotlin.math.sin

/** A source number in the answer, "[2]" drawn as a small grey pill raised a little above the line. */
class CiteSpan(private val density: Float) : ReplacementSpan() {
    // One set of paints for every badge: the answer is rebuilt as it types, badges and all.
    private companion object {
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB9B9C2.toInt()
            textAlign = Paint.Align.CENTER
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3B40.toInt() }
        val box = RectF()
    }

    private fun text(source: CharSequence, start: Int, end: Int) =
        source.subSequence(start, end).toString().trim().removePrefix("[").removeSuffix("]")

    private fun pillWidth(paint: Paint, s: String): Float {
        label.textSize = paint.textSize * 0.54f
        label.isFakeBoldText = true
        return maxOf(13 * density, label.measureText(s) + 7 * density)
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        (pillWidth(paint, text(text, start, end)) + 4 * density).toInt()

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val s = text(text, start, end)
        val w = pillWidth(paint, s)
        val h = 13 * density
        val cy = y + paint.fontMetrics.ascent / 2 + density
        box.set(x + 3 * density, cy - h / 2, x + 3 * density + w, cy + h / 2)
        canvas.drawRoundRect(box, h / 2, h / 2, fill)
        canvas.drawText(s, box.centerX(), cy - (label.descent() + label.ascent()) / 2, label)
    }
}

/** Three bars that bounce at the end of the answer while it is read aloud; redrawn by its TextView each frame. */
class EqualizerSpan(private val density: Float) : ReplacementSpan() {
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF8FA4FF") }
    private val rect = RectF()

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        (14 * density).toInt()

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val w = 1.5f * density
        val full = 8 * density
        val t = System.currentTimeMillis() / 1000.0
        for (i in 0 until 3) {
            val p = ((sin(2 * PI * t / 0.8 - i * 2.2) + 1) / 2).toFloat()
            val h = full * (0.35f + 0.65f * p)
            val left = x + 6 * density + i * w * 2
            rect.set(left, y - h, left + w, y.toFloat())
            canvas.drawRoundRect(rect, w / 2, w / 2, bar)
        }
    }
}
