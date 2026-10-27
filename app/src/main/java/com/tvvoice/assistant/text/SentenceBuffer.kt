package com.tvvoice.assistant.text

/**
 * Cuts a streamed answer into whole sentences, so speech can start with the first one while the rest is still
 * arriving. A sentence ends at a line break, or at . ! ? … followed by a space and then anything but a lowercase
 * letter: "3.14" (no space) and "т.е. примерно" (lowercase next) go on. Until the character after the space has
 * arrived the end stays open; [flush] gives what is left when the stream is over.
 */
class SentenceBuffer {
    private val pending = StringBuilder()

    /** Adds a chunk; returns the sentences it completed, cleaned for speech, in order. */
    fun add(chunk: String): List<String> {
        pending.append(chunk)
        val done = mutableListOf<String>()
        var start = 0
        for (i in pending.indices) {
            if (endsAt(i)) {
                Speakable.of(pending.substring(start, i + 1))?.let(done::add)
                start = i + 1
            }
        }
        pending.delete(0, start)
        return done
    }

    private fun endsAt(i: Int): Boolean {
        val c = pending[i]
        if (c == '\n') return true
        if (c !in ENDINGS || i + 1 >= pending.length || !pending[i + 1].isWhitespace()) return false
        var next = i + 1
        while (next < pending.length && pending[next].isWhitespace()) next++
        return next < pending.length && !pending[next].isLowerCase()
    }

    /** What is left once the stream has ended, cleaned for speech; null when nothing is left. */
    fun flush(): String? {
        val rest = Speakable.of(pending.toString())
        pending.clear()
        return rest
    }

    private companion object {
        const val ENDINGS = ".!?…"
    }
}

/**
 * Markdown and symbols a voice should not read out: emphasis marks, headings, code ticks, list bullets, links,
 * and emoji (the prompt asks for none; this is for a model that adds them anyway). Source numbers like [2] are
 * not spoken; on screen they stay, for the card to draw as badges.
 */
object Speakable {
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val marks = Regex(
        """[*_`#>~|]+|[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{FE0F}\x{200D}]""",
    )
    private val sources = Regex("""\[\d+(?:,\s*\d+)*]""")
    private val bullet = Regex("""^\s*(?:[-•]|\d+[.)])\s+""")
    private val spaces = Regex("""\s+""")
    private val gaps = Regex("""[ \t]{2,}""")
    private val beforePunctuation = Regex("""[ \t]+([.,!?;:])""")

    /** The text as it should be spoken, or null when nothing speakable is left. */
    fun of(text: String): String? {
        val spoken = text.lines()
            .joinToString(" ") { line -> bullet.replace(link.replace(line, "$1"), "") }
            .let { sources.replace(marks.replace(it, ""), "") }
            .let { spaces.replace(it, " ") }
            .let { beforePunctuation.replace(it, "$1") }
            .trim()
        return spoken.takeIf { s -> s.any { it.isLetterOrDigit() } }
    }

    /** The text as it should be shown: the same marks removed, line breaks kept. */
    fun display(text: String): String = text.lines().joinToString("\n") { line ->
        marks.replace(link.replace(line, "$1"), "")
            .let { gaps.replace(it, " ") }
            .let { beforePunctuation.replace(it, "$1") }
            .trimEnd()
    }.trim()
}
