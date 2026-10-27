package com.tvvoice.assistant.chat

import org.json.JSONException
import org.json.JSONObject

/**
 * One line of the server-sent event stream an OpenAI-compatible `/chat/completions` endpoint returns with
 * `"stream": true` (DeepSeek, OpenAI, OpenRouter, a local server): `data: {json}` per chunk, `data: [DONE]` at the end.
 */
object Sse {
    sealed interface Event {
        /** The next piece of the answer. */
        data class Delta(val text: String) : Event

        /** The answer is complete. */
        data object Done : Event

        /**
         * A piece of a function call the model asks for (the web search). The first piece of call [index] carries
         * its [id] and [name]; the arguments, a JSON string, arrive in pieces after it.
         */
        data class ToolCallPiece(val index: Int, val id: String?, val name: String?, val arguments: String) : Event

        /** The server reported an error inside the stream. */
        data class Failure(val message: String) : Event
    }

    /** The event a line carries, or null for blank lines, comments, keep-alives and chunks without text or call. */
    fun parse(line: String): Event? {
        if (!line.startsWith("data:")) return null
        val payload = line.substring(5).trim()
        if (payload.isEmpty()) return null
        if (payload == "[DONE]") return Event.Done
        val json = try {
            JSONObject(payload)
        } catch (e: JSONException) {
            return null
        }
        json.optJSONObject("error")?.let { return Event.Failure(it.optString("message", payload)) }
        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        // Reasoning models also stream "reasoning_content"; only the answer itself is shown and spoken.
        val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return null
        delta.optJSONArray("tool_calls")?.optJSONObject(0)?.let { call ->
            val function = call.optJSONObject("function")
            return Event.ToolCallPiece(
                index = call.optInt("index", 0),
                id = call.optString("id").takeUnless { call.isNull("id") || it.isEmpty() },
                name = function?.optString("name")?.takeUnless { function.isNull("name") || it.isEmpty() },
                arguments = function?.takeUnless { it.isNull("arguments") }?.optString("arguments").orEmpty(),
            )
        }
        if (delta.isNull("content")) return null
        val text = delta.optString("content")
        return if (text.isEmpty()) null else Event.Delta(text)
    }
}
