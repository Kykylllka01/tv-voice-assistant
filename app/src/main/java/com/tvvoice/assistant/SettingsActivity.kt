package com.tvvoice.assistant

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.tvvoice.assistant.chat.ChatClient
import com.tvvoice.assistant.chat.ChatException
import com.tvvoice.assistant.chat.Conversation
import com.tvvoice.assistant.search.SearchException
import com.tvvoice.assistant.search.WebSearch
import com.tvvoice.assistant.settings.Memory
import com.tvvoice.assistant.settings.Prefs
import com.tvvoice.assistant.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * What the app tile opens: the API key and the optional web search key (typed with the remote or a phone keyboard;
 * stored encrypted and never shown again), what the assistant knows about the viewer, the model and server, voice
 * on or off and which voice (each one plays as it is picked), and a test question to check the key from the sofa.
 */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var memory: Memory
    private lateinit var facts: EditText
    private lateinit var key: EditText
    private lateinit var keyState: TextView
    private lateinit var searchKey: EditText
    private lateinit var searchKeyState: TextView
    private lateinit var model: EditText
    private lateinit var baseUrl: EditText
    private lateinit var speak: CheckBox
    private lateinit var result: TextView
    private lateinit var voices: RadioGroup
    private var tts: TextToSpeech? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var testing: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        memory = Memory(this)
        facts = findViewById(R.id.memory)
        key = findViewById(R.id.key)
        keyState = findViewById(R.id.keyState)
        searchKey = findViewById(R.id.searchKey)
        searchKeyState = findViewById(R.id.searchKeyState)
        model = findViewById(R.id.model)
        baseUrl = findViewById(R.id.baseUrl)
        speak = findViewById(R.id.speak)
        result = findViewById(R.id.testResult)
        voices = findViewById(R.id.voices)
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) runOnUiThread(::showVoices) }

        facts.setText(memory.facts.joinToString("\n"))
        model.setText(prefs.model)
        baseUrl.setText(prefs.baseUrl)
        speak.isChecked = prefs.speak
        showKeyState()

        findViewById<Button>(R.id.save).setOnClickListener {
            save()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.test).setOnClickListener {
            save()
            test()
        }
        (if (prefs.hasKey) findViewById<Button>(R.id.test) else key).requestFocus()
    }

    private fun save() {
        val typed = key.text.toString()
        if (typed.isNotBlank()) prefs.apiKey = typed
        key.text.clear()
        val typedSearch = searchKey.text.toString()
        if (typedSearch.isNotBlank()) prefs.searchKey = typedSearch
        searchKey.text.clear()
        memory.facts = facts.text.lines()
        prefs.model = model.text.toString()
        prefs.baseUrl = baseUrl.text.toString()
        prefs.speak = speak.isChecked
        showKeyState()
    }

    private fun showKeyState() {
        keyState.text = if (prefs.hasKey) getString(R.string.settings_key_saved) else ""
        searchKeyState.text =
            if (prefs.hasSearchKey) getString(R.string.settings_key_saved) else getString(R.string.settings_search_note)
    }

    /**
     * One short question through the same client the overlay uses, then, with a search key saved, one search:
     * the answer and whether search works, or the reason either failed.
     */
    private fun test() {
        testing?.cancel()
        result.text = getString(R.string.thinking)
        val question = getString(R.string.test_question)
        val body = Conversation(AssistActivity.systemPrompt(this, false, memory.facts)).requestBody(prefs.model, question)
        testing = scope.launch {
            val answer = StringBuilder()
            result.text = try {
                ChatClient(prefs.baseUrl, prefs.apiKey).stream(body) { piece ->
                    answer.append(piece)
                    result.text = answer
                }.text
            } catch (e: ChatException) {
                when (e.kind) {
                    ChatException.Kind.NO_KEY -> getString(R.string.error_no_key)
                    ChatException.Kind.BAD_KEY -> getString(R.string.error_bad_key)
                    ChatException.Kind.NO_BALANCE -> getString(R.string.error_no_balance)
                    ChatException.Kind.RATE_LIMIT -> getString(R.string.error_rate_limit)
                    ChatException.Kind.OFFLINE -> getString(R.string.error_offline)
                    ChatException.Kind.SERVER -> getString(R.string.error_server, e.message)
                }
            }
            if (prefs.hasSearchKey) {
                val searchLine = try {
                    WebSearch(prefs.searchKey).find(getString(R.string.test_search))
                    getString(R.string.settings_search_ok)
                } catch (e: SearchException) {
                    getString(if (e.badKey) R.string.search_bad_key else R.string.search_failed)
                }
                result.text = "${result.text}\n\n$searchLine"
            }
        }
    }

    /** The engine's voices for the TV's language, one per speaker, the saved one checked. */
    private fun showVoices() {
        val engine = tts ?: return
        val language = Locale.getDefault().language
        val names = Speaker.choices(
            engine.voices.orEmpty().filter { it.locale.language == language }
                .map { it.name to it.isNetworkConnectionRequired },
        )
        voices.removeAllViews()
        addVoice("", getString(R.string.settings_voice_default))
        names.forEachIndexed { i, name ->
            val online = if (name.endsWith("-network")) getString(R.string.settings_voice_online) else ""
            addVoice(name, getString(R.string.settings_voice_n, i + 1) + online)
        }
    }

    private fun addVoice(name: String, label: String) {
        val dp = resources.displayMetrics.density
        val button = RadioButton(this).apply {
            id = View.generateViewId()
            text = label
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.field)
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            isChecked = name == prefs.voice
            setOnClickListener { pick(name) }
        }
        voices.addView(button, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()).apply {
            topMargin = (8 * dp).toInt()
        })
    }

    /** Saves the voice and plays it, so the viewer hears what they picked. */
    private fun pick(name: String) {
        prefs.voice = name
        val engine = tts ?: return
        val chosen = engine.voices.orEmpty().firstOrNull { it.name == name }
        if (chosen != null) engine.voice = chosen else engine.language = Locale.getDefault()
        engine.speak(getString(R.string.voice_sample), TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    override fun onDestroy() {
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
