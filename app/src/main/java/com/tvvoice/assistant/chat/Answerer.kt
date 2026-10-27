package com.tvvoice.assistant.chat

import android.util.Log
import com.tvvoice.assistant.search.SearchException
import com.tvvoice.assistant.search.Source
import com.tvvoice.assistant.search.WebSearch
import com.tvvoice.assistant.settings.Memory
import com.tvvoice.assistant.weather.Place
import com.tvvoice.assistant.weather.Weather
import com.tvvoice.assistant.weather.WeatherException
import org.json.JSONObject

/**
 * One question to one answer, searching the web, checking the weather or saving a fact about the viewer on the
 * way when the model asks to. Each round streams a reply; a reply that calls a tool gets its result and another round, until the model
 * answers in words. The last round offers no tools, so a model that keeps calling them still has to answer.
 * Without [search] the model is offered only weather and memory (and its system prompt says it has no web search).
 */
class Answerer(
    private val client: ChatClient,
    private val conversation: Conversation,
    private val model: String,
    private val search: WebSearch?,
    private val memory: Memory,
    private val weather: Weather = Weather(),
) {
    /** What the card shows while it happens, called on the caller's dispatcher. */
    interface Events {
        fun onDelta(text: String)
        /** The model went to search for [query]; any text it wrote before that is a preamble, not the answer. */
        fun onSearching(query: String)
        fun onSources(sources: List<Source>)
        fun onSearchFailed(badKey: Boolean)
        /** The model asked for the weather in [place]. */
        fun onWeather(place: String)
        fun onForecast(place: Place)
        fun onWeatherFailed()
        /** A fact about the viewer was saved. */
        fun onRemembered(fact: String)
    }

    /** The answer, also remembered in [conversation] for follow-ups. */
    suspend fun answer(question: String, events: Events): String {
        val extra = mutableListOf<JSONObject>()
        val kept = mutableListOf<JSONObject>()
        for (round in 1..MAX_ROUNDS) {
            val tools = if (round < MAX_ROUNDS) Tools.definitions(search != null) else null
            val reply = client.stream(conversation.requestBody(model, question, tools, extra)) { events.onDelta(it) }
            val calls = reply.toolCalls
            if (calls.isEmpty()) {
                conversation.remember(question, reply.text, kept)
                return reply.text
            }
            extra += Tools.assistantTurn(reply.text, calls)
            val results = calls.map { call -> Tools.result(call.id, run(call, events)) }
            extra += results
            // Memory calls stay in the dialogue (small, and they show how "remember" is done); search results
            // would make every later request kilobytes longer.
            val memory = calls.indices.filter { calls[it].name == Tools.REMEMBER || calls[it].name == Tools.FORGET }
            if (memory.isNotEmpty()) {
                kept += Tools.assistantTurn("", memory.map(calls::get))
                kept += memory.map(results::get)
            }
        }
        error("unreachable: the last round offers no tools")
    }

    private suspend fun run(call: ToolCall, events: Events): String {
        Log.i("TvVoice", "tool ${call.name} ${call.arguments}")
        val fact = call.fact
        when {
            call.name == Tools.REMEMBER && fact != null -> {
                memory.add(fact)
                events.onRemembered(fact)
                return "Saved."
            }
            call.name == Tools.FORGET && fact != null ->
                return if (memory.forget(fact) > 0) "Deleted." else "No saved fact mentions that."
        }
        val place = call.place
        if (call.name == Tools.WEATHER && place != null) {
            events.onWeather(place)
            return try {
                val (found, forecast) = weather.forecast(place, call.argument("days")?.toIntOrNull() ?: 1)
                events.onForecast(found)
                forecast
            } catch (e: WeatherException) {
                events.onWeatherFailed()
                "The weather service failed (${e.message}). Tell the user you could not get the weather."
            }
        }
        val query = call.query
        if (call.name != Tools.WEB_SEARCH || query == null || search == null) return "Unknown tool call."
        events.onSearching(query)
        return try {
            val sources = search.find(query, call.argument("topic"), call.argument("time_range"))
            events.onSources(sources)
            WebSearch.forModel(sources)
        } catch (e: SearchException) {
            events.onSearchFailed(e.badKey)
            "The search failed (${e.message}). Tell the user you could not check it online."
        }
    }

    private companion object {
        /** Up to two rounds of tools, then an answer. */
        const val MAX_ROUNDS = 3
    }
}
