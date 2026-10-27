package com.tvvoice.assistant.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * One page the search found: what the card shows as a source and what the model reads. [published] is the date
 * news results carry ("Mon, 20 Oct 2026 14:05:00 GMT"), empty for the rest.
 */
data class Source(val title: String, val url: String, val snippet: String, val published: String = "") {
    /** "ria.ru" for https://www.ria.ru/..., the label of the source chip. */
    val site: String
        get() = try {
            URI(url).host.orEmpty().removePrefix("www.").ifEmpty { url }
        } catch (e: Exception) {
            url
        }
}

/** Why a search returned nothing; [badKey] when the search key was rejected rather than the network. */
class SearchException(message: String, val badKey: Boolean = false) : Exception(message)

/**
 * Web search for the model's `web_search` calls, through Tavily (https://tavily.com): a search API made for
 * assistants that returns the relevant passage of each page, so the model can answer from it without fetching
 * pages itself. The key is the viewer's own, typed in settings.
 */
class WebSearch(private val apiKey: String, private val endpoint: String = ENDPOINT) {

    /**
     * Pages for [query]. [topic] "news" searches news sites only; [timeRange] ("day", "week", "month", "year")
     * keeps only recent pages, which a question about fresh events needs. Other values are left out.
     */
    suspend fun find(query: String, topic: String? = null, timeRange: String? = null): List<Source> =
        withContext(Dispatchers.IO) {
            // The key goes both ways Tavily has taken it: the Bearer header of its current API and the body field
            // of its first one, so an older account or endpoint accepts it too.
            val body = JSONObject()
                .put("api_key", apiKey)
                .put("query", query)
                .put("max_results", MAX_RESULTS)
                .put("search_depth", "basic")
                .put("include_answer", false)
                .apply { if (topic in TOPICS) put("topic", topic) }
                // News without a range is news from any year; a month is what "recent" means to most questions.
                .apply {
                    if (timeRange in TIME_RANGES) put("time_range", timeRange)
                    else if (topic == "news") put("time_range", "month")
                }
                .toString()
            Log.i(TAG, "search \"$query\" topic=${topic ?: "-"} time_range=${timeRange ?: "-"}")
            val c = URL(endpoint).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 8_000
                c.readTimeout = 15_000
                c.doOutput = true
                c.setRequestProperty("Authorization", "Bearer $apiKey")
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code !in 200..299) {
                    val detail = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
                    Log.w(TAG, "search HTTP $code: $detail")
                    // Tavily itself answers a bad key in JSON; a bare HTML 403 comes from the gateway in front of it,
                    // which turns away keyed requests from some networks whatever the key, so that is not the key.
                    if (code == 401 || (code == 403 && detail.trimStart().startsWith("{"))) {
                        throw SearchException("search key rejected", badKey = true)
                    }
                    throw SearchException("search HTTP $code")
                }
                parse(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).also { found ->
                    found.forEach { Log.i(TAG, "  found ${it.site} ${it.published.ifEmpty { "(no date)" }}") }
                }
            } catch (e: IOException) {
                throw SearchException(e.message ?: "no connection")
            } finally {
                c.disconnect()
            }
        }

    companion object {
        const val ENDPOINT = "https://api.tavily.com/search"
        private const val TAG = "TvVoice"
        private const val MAX_RESULTS = 5
        private const val SNIPPET_CHARS = 700
        private val TOPICS = setOf("general", "news")
        private val TIME_RANGES = setOf("day", "week", "month", "year")

        /** The sources in a Tavily `/search` response, best first; pages without a link are dropped. */
        fun parse(json: String): List<Source> {
            val results = try {
                JSONObject(json).optJSONArray("results")
            } catch (e: JSONException) {
                throw SearchException("unreadable search response")
            } ?: return emptyList()
            return (0 until results.length()).mapNotNull { i ->
                val r = results.optJSONObject(i) ?: return@mapNotNull null
                val url = r.optString("url").trim()
                if (url.isEmpty()) return@mapNotNull null
                Source(
                    r.optString("title").trim(), url, r.optString("content").trim().take(SNIPPET_CHARS),
                    // A missing date comes as JSON null, which optString turns into "null".
                    if (r.isNull("published_date")) "" else r.optString("published_date").trim(),
                )
            }
        }

        /** The results as the model reads them in the tool message: numbered, title, link, passage. */
        fun forModel(sources: List<Source>): String =
            if (sources.isEmpty()) "No results."
            else sources.mapIndexed { i, s ->
                val date = if (s.published.isEmpty()) "" else " (published ${s.published})"
                "[${i + 1}] ${s.title}$date\n${s.url}\n${s.snippet}"
            }.joinToString("\n\n")
    }
}
