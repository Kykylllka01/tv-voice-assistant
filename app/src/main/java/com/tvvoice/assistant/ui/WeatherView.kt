package com.tvvoice.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.tvvoice.assistant.R
import com.tvvoice.assistant.weather.Forecast
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The weather as a card in the panel: the temperature now with its sky, then a column a day (weekday, sky,
 * high and low), so the spoken answer can stay short. Icons are drawn, not emoji: sun, cloud, rain, snow, storm, fog.
 */
class WeatherView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT }
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    var forecast: Forecast? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (HEIGHT_DP * dp).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val f = forecast ?: return
        val h = height.toFloat()
        // Now: a large sky and the temperature.
        val now = f.now
        var x = 0f
        if (now != null) {
            drawSky(canvas, now.code, 22 * dp, h / 2, 17 * dp)
            text.textSize = 26 * sp
            text.typeface = Typeface.DEFAULT
            text.color = TEXT
            val label = degrees(now.temperature)
            canvas.drawText(label, 46 * dp, h / 2 + 9 * sp, text)
            x = 46 * dp + text.measureText(label) + 18 * dp
            text.textSize = 10 * sp
            text.color = MUTED
            canvas.drawText(f.place.name, 46 * dp, h / 2 + 24 * sp, text)
        }
        // A column a day, as many as fit.
        val column = 46 * dp
        val today = LocalDate.now()
        for (day in f.days) {
            if (x + column > width) break
            val cx = x + column / 2
            text.textAlign = Paint.Align.CENTER
            text.textSize = 10 * sp
            text.color = MUTED
            canvas.drawText(weekday(day.date, today), cx, 13 * sp, text)
            drawSky(canvas, day.code, cx, h / 2 - 4 * dp, 8 * dp)
            text.textSize = 12 * sp
            text.color = TEXT
            canvas.drawText(degrees(day.max), cx, h - 15 * sp, text)
            text.textSize = 10 * sp
            text.color = MUTED
            canvas.drawText(degrees(day.min), cx, h - 3 * sp, text)
            text.textAlign = Paint.Align.LEFT
            x += column
        }
    }

    private fun weekday(date: String, today: LocalDate): String {
        val d = try {
            LocalDate.parse(date)
        } catch (e: Exception) {
            return date
        }
        if (d == today) return context.getString(R.string.weather_today)
        return d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun degrees(c: Double): String {
        val n = c.roundToInt()
        return (if (n > 0) "+" else "") + n + "°"
    }

    // ---- icons, centred on (cx, cy) with radius r ---------------------------------------------------------------

    private fun drawSky(canvas: Canvas, code: Int, cx: Float, cy: Float, r: Float) {
        when (code) {
            0, 1 -> sun(canvas, cx, cy, r)
            2 -> {
                sun(canvas, cx - r * 0.3f, cy - r * 0.3f, r * 0.75f)
                cloud(canvas, cx + r * 0.15f, cy + r * 0.2f, r * 0.8f)
            }
            45, 48 -> fog(canvas, cx, cy, r)
            in 51..67, in 80..82 -> {
                cloud(canvas, cx, cy - r * 0.25f, r * 0.85f)
                drops(canvas, cx, cy + r * 0.5f, r, RAIN, snow = false)
            }
            in 71..77, 85, 86 -> {
                cloud(canvas, cx, cy - r * 0.25f, r * 0.85f)
                drops(canvas, cx, cy + r * 0.5f, r, SNOW, snow = true)
            }
            in 95..99 -> {
                cloud(canvas, cx, cy - r * 0.25f, r * 0.85f)
                bolt(canvas, cx, cy + r * 0.45f, r)
            }
            else -> cloud(canvas, cx, cy, r)
        }
    }

    private fun sun(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        line.color = SUN
        line.strokeWidth = r * 0.14f
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            canvas.drawLine(
                cx + (cos(a) * r * 0.68f).toFloat(), cy + (sin(a) * r * 0.68f).toFloat(),
                cx + (cos(a) * r * 0.95f).toFloat(), cy + (sin(a) * r * 0.95f).toFloat(), line,
            )
        }
        shape.color = SUN
        canvas.drawCircle(cx, cy, r * 0.46f, shape)
    }

    private fun cloud(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        shape.color = CLOUD
        canvas.drawCircle(cx - r * 0.42f, cy + r * 0.12f, r * 0.36f, shape)
        canvas.drawCircle(cx + r * 0.02f, cy - r * 0.12f, r * 0.5f, shape)
        canvas.drawCircle(cx + r * 0.45f, cy + r * 0.14f, r * 0.34f, shape)
        canvas.drawRoundRect(RectF(cx - r * 0.78f, cy + r * 0.05f, cx + r * 0.79f, cy + r * 0.48f), r * 0.22f, r * 0.22f, shape)
    }

    private fun drops(canvas: Canvas, cx: Float, top: Float, r: Float, color: Int, snow: Boolean) {
        line.color = color
        shape.color = color
        line.strokeWidth = r * 0.13f
        for (i in -1..1) {
            val x = cx + i * r * 0.42f
            if (snow) canvas.drawCircle(x, top + r * 0.2f, r * 0.12f, shape)
            else canvas.drawLine(x + r * 0.08f, top, x - r * 0.08f, top + r * 0.3f, line)
        }
    }

    private fun bolt(canvas: Canvas, cx: Float, top: Float, r: Float) {
        shape.color = SUN
        val p = Path().apply {
            moveTo(cx + r * 0.05f, top - r * 0.05f)
            lineTo(cx - r * 0.25f, top + r * 0.3f)
            lineTo(cx, top + r * 0.3f)
            lineTo(cx - r * 0.12f, top + r * 0.62f)
            lineTo(cx + r * 0.28f, top + r * 0.18f)
            lineTo(cx + r * 0.03f, top + r * 0.18f)
            close()
        }
        canvas.drawPath(p, shape)
    }

    private fun fog(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        line.color = CLOUD
        line.strokeWidth = r * 0.15f
        for (i in -1..1) canvas.drawLine(cx - r * 0.75f + i * r * 0.1f, cy + i * r * 0.38f, cx + r * 0.75f + i * r * 0.1f, cy + i * r * 0.38f, line)
    }

    private companion object {
        const val HEIGHT_DP = 64
        const val TEXT = 0xFFF8FAFF.toInt()
        const val MUTED = 0xFFA3A3AD.toInt()
        const val SUN = 0xFFFFC94D.toInt()
        const val CLOUD = 0xFFC9CEDB.toInt()
        const val RAIN = 0xFF6FA8FF.toInt()
        const val SNOW = 0xFFE8F0FF.toInt()
    }
}
