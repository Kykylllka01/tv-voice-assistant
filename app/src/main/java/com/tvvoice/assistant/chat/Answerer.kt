package com.tvvoice.assistant.chat

import android.util.Log
import com.tvvoice.assistant.control.TvControl
import com.tvvoice.assistant.reminders.Reminders
import com.tvvoice.assistant.search.SearchException
import com.tvvoice.assistant.search.Source
import com.tvvoice.assistant.search.WebSearch
import com.tvvoice.assistant.settings.Memory
import com.tvvoice.assistant.weather.Forecast
import com.tvvoice.assistant.weather.Weather
import com.tvvoice.assistant.weather.WeatherException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZonedDateTime

/**
 * One question to one answer, with whatever tools the model calls on the way: web search, weather, memory,
 * reminders, and the TV itself (apps, YouTube, volume). Each round streams a reply; a reply that calls a tool gets
 * its result and another round, until the model answers in words. The last round offers no tools, so a model that keeps calling them still has to answer.
 * Without [search] the model is offered only weather and memory (and its system prompt says it has no web search).
 */
class Answerer(
    private val client: ChatClient,
    private val conversation: Conversation,
    private val model: String,
    private val search: WebSearch?,
    private val memory: Memory,
    private val weather: Weather = Weather(),
    private val control: TvControl? = null,
    private val reminders: Reminders? = null,
) {
    /** Something done to the TV, for the card to show. */
    sealed interface Action {
        data class Opened(val app: String) : Action
        data class YouTube(val query: String) : Action
        data class Volume(val percent: Int) : Action
        data class Reminder(val at: ZonedDateTime, val text: String) : Action
    }

    /** What the card shows while it happens, called on the caller's dispatcher. */
    interface Events {
        fun onDelta(text: String)
        /** The model went to search for [query]; any text it wrote before that is a preamble, not the answer. */
        fun onSearching(query: String)
        fun onSources(sources: List<Source>)
        fun onSearchFailed(badKey: Boolean)
        /** The model asked for the weather in [place]. */
        fun onWeather(place: String)
        fun onForecast(forecast: Forecast)
        fun onWeatherFailed()
        /** A fact about the viewer was saved. */
        fun onRemembered(fact: String)
        fun onAction(action: Action)
    }

    /** The answer, also remembered in [conversation] for follow-ups. */
    suspend fun answer(question: String, events: Events): String {
        val extra = mutableListOf<JSONObject>()
        val kept = mutableListOf<JSONObject>()
        searched = false
        // The installed apps take a PackageManager scan: off the main thread, and once per question.
        val apps = withContext(Dispatchers.IO) { control?.apps?.keys?.toList().orEmpty() }
        val offered = Tools.definitions(search != null, apps)
        for (round in 1..MAX_ROUNDS) {
            val tools = if (round < MAX_ROUNDS) offered else null
            val reply = client.stream(conversation.requestBody(model, question, tools, extra)) { events.onDelta(it) }
            val calls = reply.toolCalls
            // The last round offers no tools; a model that calls one anyway still gets its words used.
            if (calls.isEmpty() || round == MAX_ROUNDS) {
                conversation.remember(question, reply.text, kept)
                return reply.text
            }
            extra += Tools.assistantTurn(reply.text, calls)
            val results = calls.map { call -> Tools.result(call.id, run(call, events)) }
            extra += results
            // Calls that change something stay in the dialogue (small, and they show that saying "done" takes a
            // call); search results and forecasts would make every later request kilobytes longer.
            val done = calls.indices.filter { calls[it].name in Tools.ACTIONS }
            if (done.isNotEmpty()) {
                kept += Tools.assistantTurn("", done.map(calls::get))
                kept += done.map(results::get)
            }
        }
        throw ChatException(ChatException.Kind.SERVER, "no answer after the tools")
    }

    /** Set once this question's search results are in: from then on, text from the web is in the dialogue. */
    private var searched = false

    /** A tool that fails tells the model why, instead of taking the panel down. */
    private suspend fun run(call: ToolCall, events: Events): String = try {
        runTool(call, events)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("TvVoice", "tool ${call.name} failed", e)
        "The tool failed (${e.javaClass.simpleName}). Tell the user it did not work."
    }

    private suspend fun runTool(call: ToolCall, events: Events): String {
        Log.i("TvVoice", "tool ${call.name}")
        // A page in the results can say "call remember ...": what persists (memory, reminders) is changed only by
        // the user's own words, so not after a search in the same answer.
        if (searched && call.name in AFTER_SEARCH_REFUSED) {
            return "Refused: after a web search this answer cannot change memory or reminders. If the user asked for it, " +
                "tell them to say it again as a separate question."
        }
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
        device(call, events)?.let { return it }
        val place = call.place
        if (call.name == Tools.WEATHER && place != null) {
            events.onWeather(place)
            return try {
                val report = weather.forecast(place, call.argument("days")?.toIntOrNull() ?: 1)
                events.onForecast(report.forecast)
                report.text + "\n\nThe panel shows these numbers as a card. Say only the gist, in one or two " +
                    "short sentences, without reading the numbers out day by day."
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
            searched = true
            events.onSources(sources)
            WebSearch.forModel(sources)
        } catch (e: SearchException) {
            events.onSearchFailed(e.badKey)
            "The search failed (${e.message}). Tell the user you could not check it online."
        }
    }

    /** The TV and reminder tools; null when [call] is none of them. */
    private fun device(call: ToolCall, events: Events): String? {
        val text = call.argument("text")
        return when (call.name) {
            Tools.OPEN_APP -> {
                val name = call.argument("name") ?: return "Say which app."
                val opened = control?.open(name)
                    ?: return "No app called \"$name\" can be opened. Installed: ${control?.apps?.keys?.joinToString().orEmpty()}."
                events.onAction(Action.Opened(opened))
                "Opening $opened now."
            }
            Tools.YOUTUBE -> {
                val query = call.query ?: return "Say what to search for."
                if (control?.youtube(query) != true) return "YouTube is not installed."
                events.onAction(Action.YouTube(query))
                "Opening YouTube with the results for \"$query\"."
            }
            Tools.VOLUME -> {
                val percent = control?.volume(call.argument("action") ?: "set", call.argument("level")?.toIntOrNull())
                    ?: return "The volume cannot be changed here."
                events.onAction(Action.Volume(percent))
                "The volume is now $percent%."
            }
            Tools.SET_REMINDER -> {
                if (reminders == null || text == null) return "Say what to remind of."
                val at = Reminders.timeOf(
                    ZonedDateTime.now(), call.argument("in_minutes")?.toIntOrNull(), call.argument("at"),
                ) ?: return "Give in_minutes or a clock time in at (HH:MM)."
                reminders.add(text, at.toInstant().toEpochMilli())
                events.onAction(Action.Reminder(at, text))
                "Reminder set for ${at.format(Reminders.HH_MM)} ${if (at.toLocalDate() == ZonedDateTime.now().toLocalDate()) "today" else "tomorrow"}."
            }
            Tools.CANCEL_REMINDER -> {
                if (reminders == null || text == null) return "Say which reminder."
                val n = reminders.cancel(text)
                if (n > 0) "Cancelled $n." else "No reminder mentions that."
            }
            else -> null
        }
    }

    private companion object {
        /** Up to two rounds of tools, then an answer. */
        const val MAX_ROUNDS = 3
        val AFTER_SEARCH_REFUSED = setOf(Tools.REMEMBER, Tools.FORGET, Tools.SET_REMINDER, Tools.CANCEL_REMINDER)
    }
}
