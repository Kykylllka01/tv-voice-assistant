package com.tvvoice.assistant.chat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

/** Why a question got no answer, in terms the overlay can explain. */
class ChatException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NO_KEY, BAD_KEY, NO_BALANCE, RATE_LIMIT, OFFLINE, SERVER }
}

/**
 * Streams an answer from an OpenAI-compatible `/chat/completions` endpoint (DeepSeek by default). Each piece of
 * text goes to [onDelta] as it arrives, on the caller's dispatcher; the whole reply is returned at the end, with
 * the function calls the model asked for instead of (or after) text. Cancelling the coroutine (the overlay closed)
 * disconnects at once.
 */
class ChatClient(private val baseUrl: String, private val apiKey: String) {
    data class Reply(val text: String, val toolCalls: List<ToolCall>)

    suspend fun stream(body: String, onDelta: suspend (String) -> Unit): Reply {
        if (apiKey.isBlank()) throw ChatException(ChatException.Kind.NO_KEY, "no API key")
        val answer = StringBuilder()
        val calls = ToolCallAssembler()
        val connection = withContext(Dispatchers.IO) { open(body) }
        try {
            withContext(Dispatchers.IO) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        coroutineContext.ensureActive()
                        when (val event = Sse.parse(line)) {
                            is Sse.Event.Delta -> {
                                answer.append(event.text)
                                withContext(Dispatchers.Main.immediate) { onDelta(event.text) }
                            }
                            is Sse.Event.ToolCallPiece -> calls.add(event)
                            is Sse.Event.Failure -> throw ChatException(ChatException.Kind.SERVER, event.message)
                            Sse.Event.Done -> break
                            null -> Unit
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw ChatException(ChatException.Kind.OFFLINE, e.message ?: "connection lost")
        } finally {
            connection.disconnect()
        }
        return Reply(answer.toString(), calls.calls())
    }

    private fun open(body: String): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10_000
        c.readTimeout = 60_000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $apiKey")
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("Accept", "text/event-stream")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val detail = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w("TvVoice", "chat HTTP $code: ${messageOf(detail)}")
                c.disconnect()
                throw ChatException(kindOf(code), messageOf(detail).ifBlank { "HTTP $code" })
            }
            return c
        } catch (e: UnknownHostException) {
            throw ChatException(ChatException.Kind.OFFLINE, "no connection")
        } catch (e: SocketTimeoutException) {
            throw ChatException(ChatException.Kind.OFFLINE, "timed out")
        } catch (e: IOException) {
            throw ChatException(ChatException.Kind.OFFLINE, e.message ?: "connection failed")
        }
    }

    companion object {
        /** HTTP status to what went wrong, by DeepSeek's documented codes (OpenAI uses the same ones). */
        fun kindOf(code: Int): ChatException.Kind = when (code) {
            401, 403 -> ChatException.Kind.BAD_KEY
            402 -> ChatException.Kind.NO_BALANCE
            429 -> ChatException.Kind.RATE_LIMIT
            else -> ChatException.Kind.SERVER
        }

        /** The `error.message` of an error body, or the body itself when it is not that shape. */
        fun messageOf(body: String): String = try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: body
        } catch (e: Exception) {
            body
        }.take(200)
    }
}
