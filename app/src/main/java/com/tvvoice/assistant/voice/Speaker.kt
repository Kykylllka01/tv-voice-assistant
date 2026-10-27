package com.tvvoice.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Reads the answer aloud sentence by sentence as it streams in, in the [voice] chosen in settings (the engine's
 * default when empty or gone). [onSpeaking] reports true when the first sentence starts and false when the queue
 * runs dry, on the main thread, so the orb can breathe while it talks.
 *
 * Every call into the engine goes through one background thread, in order. Google's engine shares its process
 * with the speech recognizer, and while that listens a call can wait seconds for an answer; on the main thread
 * that froze the panel and every remote key pressed meanwhile.
 */
class Speaker(context: Context, private val voice: String, private val onSpeaking: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val waiting = mutableListOf<String>()
    private var queued = 0
    private var next = 0
    private var talking = false
    /** Closed: late callbacks from the engine and late calls from the panel do nothing. */
    @Volatile private var released = false
    /** Sentences numbered below this were stopped; their late callbacks are ignored. */
    private var quietFrom = 0
    private val engine = Executors.newSingleThreadExecutor()

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (released) return@post
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    engine.execute {
                        val chosen = tts.voices?.firstOrNull { it.name == voice }
                        if (chosen != null) tts.voice = chosen else tts.language = Locale.getDefault()
                    }
                    val pending = waiting.toList()
                    waiting.clear()
                    pending.forEach(::say)
                }
                waiting.clear()
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post {
                    if (released || number(utteranceId) < quietFrom) return@post
                    talking = true
                    onSpeaking(true)
                }
            }

            override fun onDone(utteranceId: String?) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "speech error $errorCode in voice ${voice.ifEmpty { "default" }}")
                finished(utteranceId)
            }
        })
    }

    private fun finished(id: String?) {
        main.post {
            if (!released && id == "u${queued - 1}" && talking) {
                talking = false
                onSpeaking(false)
            }
        }
    }

    /** Queues one sentence behind those already speaking. */
    fun say(sentence: String) {
        if (released) return
        if (!ready) {
            waiting += sentence
            return
        }
        val id = "u${next++}"
        queued = next
        engine.execute { tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, id) }
    }

    /** Silence now. Nothing to stop costs nothing: the engine is not called while it may be busy listening. */
    fun stop() {
        if (released) return
        waiting.clear()
        if (next == quietFrom && !talking) return
        quietFrom = next
        engine.execute { tts.stop() }
        if (talking) {
            talking = false
            onSpeaking(false)
        }
    }

    private fun number(id: String?) = id?.removePrefix("u")?.toIntOrNull() ?: -1

    companion object {
        private const val TAG = "TvVoice"

        /**
         * The voices worth offering, from the engine's voice names: one per speaker, the network version where
         * there is one (Google's are synthesized on its servers and sound much more natural than the copies on
         * the TV), in a stable order. "ru-ru-x-rud-network" and "ru-ru-x-rud-local" are one speaker.
         */
        fun choices(voices: Collection<Pair<String, Boolean>>): List<String> =
            voices.filter { (name, _) -> "-x-" in name }
                .groupBy { (name, _) -> name.removeSuffix("-network").removeSuffix("-local") }
                .toSortedMap()
                .values
                .map { same -> (same.firstOrNull { (_, network) -> network } ?: same.first()).first }
    }

    fun release() {
        if (released) return
        released = true
        main.removeCallbacksAndMessages(null)
        engine.execute {
            tts.stop()
            tts.shutdown()
        }
        engine.shutdown()
    }
}
