package com.tvvoice.assistant.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * The dialogue sent with each question: a system prompt for short spoken answers, then the last [maxTurns]
 * question/answer pairs, so a follow-up ("а завтра?") keeps its context without the request growing forever.
 */
class Conversation(
    private val systemPrompt: String,
    private val maxTurns: Int = 6,
) {
    data class Message(val role: String, val content: String)

    /**
     * One answered question. [steps] are the memory tool calls it made with their results, sent back as they
     * happened: a past "remembered" with no call in front of it taught the model that saying so was enough.
     */
    data class Turn(val question: String, val answer: String, val steps: List<JSONObject> = emptyList())

    private val turns = ArrayDeque<Turn>()

    val size: Int get() = turns.size

    fun clear() = turns.clear()

    /** The remembered turns, oldest first, to pick the dialogue up again next time. */
    val history: List<Turn> get() = turns.toList()

    fun restore(history: List<Turn>) {
        turns.clear()
        history.takeLast(maxTurns).forEach(turns::addLast)
    }

    /** Remembers an answered question; the oldest turn goes once there are more than [maxTurns]. */
    fun remember(question: String, answer: String, steps: List<JSONObject> = emptyList()) {
        if (answer.isBlank()) return
        turns.addLast(Turn(question, answer, steps))
        while (turns.size > maxTurns) turns.removeFirst()
    }

    /** The text messages for a new [question]: system prompt, remembered turns, then the question. */
    fun messagesFor(question: String): List<Message> = buildList {
        add(Message("system", systemPrompt))
        for (turn in turns) {
            add(Message("user", turn.question))
            add(Message("assistant", turn.answer))
        }
        add(Message("user", question))
    }

    /**
     * The request body for [question] as the `/chat/completions` API takes it, streamed. [tools] are offered to
     * the model; [extra] follows the question (a search the model asked for and its results).
     */
    fun requestBody(
        model: String,
        question: String,
        tools: JSONArray? = null,
        extra: List<JSONObject> = emptyList(),
    ): String {
        fun text(role: String, content: String) = JSONObject().put("role", role).put("content", content)
        val messages = JSONArray().put(text("system", systemPrompt))
        for (turn in turns) {
            messages.put(text("user", turn.question))
            turn.steps.forEach(messages::put)
            messages.put(text("assistant", turn.answer))
        }
        messages.put(text("user", question))
        extra.forEach(messages::put)
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .apply { if (tools != null) put("tools", tools) }
            .put("stream", true)
            .put("temperature", 0.7)
            .toString()
    }
}
