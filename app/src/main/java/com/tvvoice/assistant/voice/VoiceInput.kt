package com.tvvoice.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * The system speech recognizer, one phrase at a time. On a Google TV with the Assistant disabled the remote's
 * microphone reaches it only while the voice button is held (docs/FINDINGS.md), so the overlay says "hold".
 */
class VoiceInput(context: Context, private val listener: Listener) {
    interface Listener {
        fun onReady()
        /** Input level, 0 to 1, about 20 times a second while listening. */
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        /** A [SpeechRecognizer] error code; [isNothingHeard] tells silence from a real failure. */
        fun onError(code: Int)
    }

    private val context = context
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * A new recognizer for every phrase: reusing one right after the last phrase made the service queue the new
     * start behind the old session's teardown (3.4 s late on the test set).
     */
    private fun fresh(): SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also { self ->
        // A late callback from the recognizer a follow-up replaced is not news about the current phrase.
        fun current() = recognizer === self
        self.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (current()) listener.onReady()
            }
            override fun onRmsChanged(rmsdB: Float) {
                if (current()) listener.onLevel(level(rmsdB))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (current()) first(partialResults)?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                if (!current()) return
                val words = first(results)
                if (words.isNullOrBlank()) listener.onError(SpeechRecognizer.ERROR_NO_MATCH) else listener.onFinal(words)
            }
            override fun onError(error: Int) {
                if (current()) listener.onError(error)
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    fun start(language: Locale = Locale.getDefault()) {
        if (!available) return
        recognizer?.destroy()
        recognizer = fresh()
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    fun stop() = recognizer?.cancel()

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        /** Silence, a timeout or "didn't catch that": worth a "try again", not an error screen. */
        fun isNothingHeard(code: Int) = code == SpeechRecognizer.ERROR_NO_MATCH ||
            code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

        /** Google's recognizer reports about -2 dB for silence and 10 dB for loud speech. */
        fun level(rmsdB: Float) = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

        private fun first(bundle: Bundle?) =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
    }
}
