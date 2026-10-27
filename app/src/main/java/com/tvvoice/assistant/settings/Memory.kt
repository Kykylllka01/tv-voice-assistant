package com.tvvoice.assistant.settings

import android.content.Context
import com.tvvoice.assistant.chat.Conversation
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What the assistant keeps between sessions, on the TV only: facts about the viewer (their city, name, where they
 * like to check the weather), which go into every system prompt, and the last dialogue, picked up again when the
 * panel opens within [RECENT_MS] of the last answer. The model adds and drops facts with its `remember` and
 * `forget` tools; the viewer sees and edits them in settings.
 */
class Memory(context: Context) {
    private val prefs = context.getSharedPreferences("memory", Context.MODE_PRIVATE)

    var facts: List<String>
        get() = prefs.getString(FACTS, "").orEmpty().lines().filter { it.isNotBlank() }
        set(value) = prefs.edit().putString(FACTS, normalize(value).joinToString("\n")).apply()

    /** Adds [fact] unless it is already there; false when nothing changed. */
    fun add(fact: String): Boolean {
        val before = facts
        val after = normalize(before + fact)
        if (after == before) return false
        facts = after
        return true
    }

    /** Drops every fact that mentions [what]; how many went. */
    fun forget(what: String): Int {
        val before = facts
        val gone = matching(before, what)
        if (gone.isNotEmpty()) facts = before - gone.toSet()
        return gone.size
    }

    /** The last dialogue, oldest first, or nothing once it is older than [RECENT_MS]. */
    fun recent(now: Long = System.currentTimeMillis()): List<Conversation.Turn> {
        if (now - prefs.getLong(RECENT_AT, 0) > RECENT_MS) return emptyList()
        return try {
            val turns = JSONArray(prefs.getString(RECENT, "[]"))
            (0 until turns.length()).map { i ->
                val turn = turns.getJSONObject(i)
                val steps = turn.optJSONArray("s") ?: JSONArray()
                Conversation.Turn(
                    turn.getString("q"), turn.getString("a"), (0 until steps.length()).map(steps::getJSONObject),
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun saveRecent(turns: List<Conversation.Turn>, now: Long = System.currentTimeMillis()) {
        val json = JSONArray()
        for (turn in turns) {
            json.put(JSONObject().put("q", turn.question).put("a", turn.answer).put("s", JSONArray(turn.steps)))
        }
        prefs.edit().putString(RECENT, json.toString()).putLong(RECENT_AT, now).apply()
    }

    companion object {
        const val MAX_FACTS = 40
        const val MAX_CHARS = 200
        const val RECENT_MS = 30 * 60 * 1000L
        private const val FACTS = "facts"
        private const val RECENT = "recent"
        private const val RECENT_AT = "recent_at"

        /** One line each, trimmed and cut to [MAX_CHARS], no repeats (the later copy stays), newest [MAX_FACTS]. */
        fun normalize(facts: List<String>): List<String> {
            val lines = facts.flatMap { it.lines() }
                .map { it.trim().trimStart('-', '•', ' ').take(MAX_CHARS).trim() }
                .filter { it.isNotEmpty() }
            return lines.filterIndexed { i, line ->
                lines.subList(i + 1, lines.size).none { it.equals(line, ignoreCase = true) }
            }.takeLast(MAX_FACTS)
        }

        /** The facts that mention [what], ignoring case. */
        fun matching(facts: List<String>, what: String): List<String> {
            val needle = what.trim()
            if (needle.isEmpty()) return emptyList()
            return facts.filter { it.contains(needle, ignoreCase = true) }
        }
    }
}
