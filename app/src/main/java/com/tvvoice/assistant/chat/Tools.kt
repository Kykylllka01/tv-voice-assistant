package com.tvvoice.assistant.chat

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A function call the model asked for, put back together from its streamed pieces. */
data class ToolCall(val id: String, val name: String, val arguments: String) {
    /** The `query` argument of a web search call, or null when the arguments are not that shape. */
    val query: String? get() = argument("query")

    /** The `fact` argument of a remember or forget call. */
    val fact: String? get() = argument("fact")

    /** The `place` argument of a weather call. */
    val place: String? get() = argument("place")

    /** A string argument, trimmed, or null when it is missing or the arguments are not JSON. */
    fun argument(name: String): String? = try {
        JSONObject(arguments).optString(name).trim().ifEmpty { null }
    } catch (e: JSONException) {
        null
    }
}

/** Collects [Sse.Event.ToolCallPiece]s by index into whole calls, in the order the model made them. */
class ToolCallAssembler {
    private class Partial(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())

    private val calls = sortedMapOf<Int, Partial>()

    fun add(piece: Sse.Event.ToolCallPiece) {
        val call = calls.getOrPut(piece.index) { Partial() }
        piece.id?.let { call.id = it }
        piece.name?.let { call.name = it }
        call.arguments.append(piece.arguments)
    }

    fun calls(): List<ToolCall> =
        calls.values.filter { it.name.isNotEmpty() }.map { ToolCall(it.id, it.name, it.arguments.toString()) }
}

/** The assistant's tools, as `/chat/completions` declares them, and the messages a call adds. */
object Tools {
    const val WEB_SEARCH = "web_search"
    const val REMEMBER = "remember"
    const val FORGET = "forget"

    const val WEATHER = "weather"

    /** Memory and weather always; web search only with a search key. */
    fun definitions(search: Boolean): JSONArray = JSONArray().apply {
        if (search) put(function(WEB_SEARCH, "Search the internet. Use it for anything current or checkable: news, " +
            "prices and exchange rates, sports results, schedules, releases, people and events after your " +
            "knowledge cutoff, or any fact you are not sure about. Not for weather: use the weather tool.",
            JSONObject()
                .put("query", string("A short search query, in the language most likely to find it."))
                .put("topic", string("\"news\" for recent events, announcements, what someone said or did; " +
                    "\"general\" otherwise.").put("enum", JSONArray().put("general").put("news")))
                .put("time_range", string("Only pages from the last day, week, month or year. Set it whenever " +
                    "the question is about something recent.")
                    .put("enum", JSONArray().put("day").put("week").put("month").put("year"))),
            "query"))
        put(function(WEATHER, "Current weather and the forecast for up to 7 days for a place, from a weather " +
            "service. Use it for any question about weather, temperature, rain, snow or wind.",
            JSONObject()
                .put("place", string("The city or town, in the nominative case, e.g. \"Москва\", not \"Москве\"."))
                .put("days", JSONObject().put("type", "integer")
                    .put("description", "How many days from today to include, 1 to 7; 1 when only today or now.")),
            "place"))
        put(function(REMEMBER, "Save a lasting fact about the user that will matter in later conversations: " +
            "their name, city, family, preferences, favourite sources, teams or shows. Not one-off requests.",
            JSONObject().put("fact", string("One short statement in the third person, in the user's language, " +
                "e.g. \"Живёт в Москве\".")),
            "fact"))
        put(function(FORGET, "Delete saved facts about the user that mention the given words, when the user " +
            "asks to forget something or a fact has changed.",
            JSONObject().put("fact", string("A word or phrase from the facts to delete.")),
            "fact"))
    }

    private fun string(description: String) = JSONObject().put("type", "string").put("description", description)

    private fun function(name: String, description: String, properties: JSONObject, required: String) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray().put(required))))

    /** The assistant turn that asked for [calls], as it has to be sent back before their results. */
    fun assistantTurn(text: String, calls: List<ToolCall>): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("content", text)
        .put("tool_calls", JSONArray().apply {
            for (c in calls) put(JSONObject()
                .put("id", c.id)
                .put("type", "function")
                .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments)))
        })

    /** The result of call [id]. */
    fun result(id: String, content: String): JSONObject =
        JSONObject().put("role", "tool").put("tool_call_id", id).put("content", content)
}
