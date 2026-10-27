package com.tvvoice.assistant.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.widget.ScrollView
import android.widget.TextView
import com.tvvoice.assistant.text.Speakable

/**
 * Reveals a streamed answer at an even pace however the network delivers it: about 45 characters a second, faster
 * when a backlog builds up, so a burst never dumps a paragraph at once and the end never lags far behind. Source
 * numbers show as badges; while [speaking], a small equalizer bounces after the last word.
 */
class Typewriter(private val view: TextView, private val scroller: ScrollView?) {
    private val target = StringBuilder()
    private var shown = 0.0
    private var lastFrame = 0L
    private var running = false
    private val density = view.resources.displayMetrics.density

    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 0.016 else (now - lastFrame) / 1e9
            lastFrame = now
            val backlog = target.length - shown
            val before = shown.toInt()
            shown = (shown + dt * pace(backlog)).coerceAtMost(target.length.toDouble())
            // Most frames add no whole character: nothing to rebuild.
            if (shown.toInt() != before) render()
            if (shown < target.length) view.postOnAnimation(this) else running = false
        }
    }

    private var bouncing = false

    /** The equalizer at the end of the answer, about 30 frames a second, one loop at a time. */
    private val bounce = object : Runnable {
        override fun run() {
            if (!speaking) {
                bouncing = false
                return
            }
            view.invalidate()
            view.postDelayed(this, 33)
        }
    }

    var speaking = false
        set(value) {
            if (field == value) return
            field = value
            render()
            if (value && !bouncing) {
                bouncing = true
                view.postOnAnimation(bounce)
            }
        }

    fun append(text: String) {
        target.append(text)
        if (!running) {
            running = true
            lastFrame = 0L
            view.postOnAnimation(frame)
        }
    }

    /** Shows everything at once (the answer is over and the viewer is waiting). */
    fun finish() {
        shown = target.length.toDouble()
        render()
    }

    /**
     * Keeps the newest line in view while the answer types. Off once the viewer scrolls with the remote, so the
     * text they went back to read stays put; a new answer turns it on again.
     */
    var follow = true

    fun clear() {
        follow = true
        target.clear()
        shown = 0.0
        view.text = ""
    }

    val text: String get() = target.toString()

    private fun render() {
        // A source number still being typed ("[2") would flash as raw text before it turns into a badge.
        val typed = target.substring(0, shown.toInt()).replace(UNFINISHED, "")
        val out = SpannableStringBuilder(Speakable.display(typed))
        // Right to left, so earlier offsets stay valid as each number and the space before it collapse.
        // A word joiner in front keeps the badge on its word's line: on its own, a replaced run is an object a
        // line may break before, and a lone badge started the next line.
        for (m in SOURCE.findAll(out).toList().asReversed()) {
            out.replace(m.range.first, m.range.last + 1, JOINER + m.groupValues[1])
            val start = m.range.first + JOINER.length
            out.setSpan(CiteSpan(density), start, start + m.groupValues[1].length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (speaking && out.isNotEmpty()) {
            out.append(OBJECT)
            out.setSpan(EqualizerSpan(density), out.length - 1, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = out
        if (follow) scroller?.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private companion object {
        val SOURCE = Regex("""\s*\[(\d+(?:,\s*\d+)*)]""")
        val UNFINISHED = Regex("""\s*\[[\d,\s]*$""")
        const val OBJECT = "￼"
        const val JOINER = "⁠"

        /** Characters a second for a backlog: an even 45 when keeping up, catching up within about a second. */
        fun pace(backlog: Double) = maxOf(45.0, backlog * 1.2)
    }
}
