package com.tvvoice.assistant

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.text.format.DateFormat
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.ViewGroup
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tvvoice.assistant.chat.Answerer
import com.tvvoice.assistant.chat.ChatClient
import com.tvvoice.assistant.chat.ChatException
import com.tvvoice.assistant.chat.Conversation
import com.tvvoice.assistant.search.Source
import com.tvvoice.assistant.search.WebSearch
import com.tvvoice.assistant.settings.Memory
import com.tvvoice.assistant.settings.Prefs
import com.tvvoice.assistant.text.SentenceBuffer
import com.tvvoice.assistant.ui.MicOrbView
import com.tvvoice.assistant.ui.ShimmerTextView
import com.tvvoice.assistant.ui.Typewriter
import com.tvvoice.assistant.ui.WaveView
import com.tvvoice.assistant.voice.Speaker
import com.tvvoice.assistant.voice.VoiceInput
import com.tvvoice.assistant.weather.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

/**
 * The overlay the remote voice button opens over whatever is on screen, HDMI included: this app holds the
 * assistant role, so every press of the button starts it with ACTION_ASSIST (docs/FINDINGS.md). It listens while
 * the button is held, searches the web when the model asks to, streams the answer into the panel and reads it
 * aloud, and keeps the dialogue until it closes. The look, the states and the hints follow the design canvas:
 * each [Stage] shows only what it needs and only the remote keys that work right then.
 */
class AssistActivity : Activity(), VoiceInput.Listener {
    private enum class Stage { LISTENING, NOT_HEARD, THINKING, SEARCHING, ANSWERING, DONE, READY, ERROR }
    private enum class Key { MIC, ARROWS, OK, BACK, THEN }
    private enum class Pill { NONE, SEARCHING, FOUND, FAILED }
    private data class Hint(val keys: List<Key>, val label: Int)

    private lateinit var prefs: Prefs
    private lateinit var memory: Memory
    private lateinit var voice: VoiceInput
    private lateinit var speaker: Speaker
    private lateinit var typewriter: Typewriter
    private lateinit var conversation: Conversation
    private var search: WebSearch? = null

    private lateinit var card: View
    private lateinit var scrim: View
    private lateinit var orb: MicOrbView
    private lateinit var question: TextView
    private lateinit var status: View
    private lateinit var wave: WaveView
    private lateinit var statusText: ShimmerTextView
    private lateinit var pill: View
    private lateinit var spinner: View
    private lateinit var favs: LinearLayout
    private lateinit var pillText: TextView
    private lateinit var pillQuery: TextView
    private lateinit var answer: TextView
    private lateinit var answerScroll: View
    private lateinit var hints: LinearLayout

    private var stage = Stage.LISTENING
    private var speaking = false
    private var pillState = Pill.NONE
    private var shownHints: List<Hint> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var asking: Job? = null
    private var closing = false
    private val autoClose = Runnable { close() }
    private val dp by lazy { resources.displayMetrics.density }
    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        setContentView(R.layout.activity_assist)
        card = findViewById(R.id.card)
        scrim = findViewById(R.id.scrim)
        orb = findViewById(R.id.orb)
        question = findViewById(R.id.question)
        status = findViewById(R.id.status)
        wave = findViewById(R.id.wave)
        statusText = findViewById(R.id.statusText)
        pill = findViewById(R.id.pill)
        spinner = findViewById(R.id.spinner)
        favs = findViewById(R.id.favs)
        pillText = findViewById(R.id.pillText)
        pillQuery = findViewById(R.id.pillQuery)
        answer = findViewById(R.id.answer)
        hints = findViewById(R.id.hints)
        answerScroll = findViewById(R.id.answerScroll)
        typewriter = Typewriter(answer, answerScroll as ScrollView)
        findViewById<View>(R.id.root).setOnClickListener { close() }

        prefs = Prefs(this)
        search = prefs.searchKey.takeIf { it.isNotBlank() }?.let(::WebSearch)
        memory = Memory(this)
        conversation = Conversation(systemPrompt(this, search != null, memory.facts))
        // Opened again soon after the last answer: "а завтра?" still knows what it follows.
        conversation.restore(memory.recent())
        voice = VoiceInput(this, this)
        speaker = Speaker(this, prefs.voice) { talking ->
            speaking = talking
            if (talking) card.removeCallbacks(autoClose) else if (asking?.isActive != true) scheduleClose()
            render()
        }

        enter()
        typed(intent)?.let(::onFinal) ?: listen()
    }

    /**
     * A question passed as text instead of spoken, in debug builds only: `am start -n <pkg>/.AssistActivity
     * --es question "..."` shows every stage on the TV without anyone at the remote (screenshots, checks).
     */
    private fun typed(intent: Intent): String? =
        intent.getStringExtra(EXTRA_QUESTION)?.takeIf { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    /** The voice button again while the panel is up: a follow-up question in the same dialogue. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (closing) return
        typed(intent)?.let(::onFinal) ?: listen()
    }

    private fun listen() {
        card.removeCallbacks(autoClose)
        asking?.cancel()
        speaker.stop()
        if (!voice.available) return fail(getString(R.string.no_recognizer))
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return fail(getString(R.string.no_mic_permission))
        }
        question.visibility = View.GONE
        pillState = Pill.NONE
        typewriter.clear()
        orb.setLevel(0f)
        wave.setLevel(0f)
        show(Stage.LISTENING)
        voice.start()
    }

    // ---- VoiceInput.Listener ---------------------------------------------------------------------------------

    override fun onReady() = Unit

    override fun onLevel(level: Float) {
        orb.setLevel(level)
        wave.setLevel(level)
    }

    override fun onPartial(text: String) {
        if (text.isNotBlank()) showQuestion(text, live = true)
    }

    override fun onFinal(text: String) {
        orb.setLevel(0f)
        showQuestion(text, live = false)
        ask(text)
    }

    override fun onError(code: Int) {
        orb.setLevel(0f)
        when {
            VoiceInput.isNothingHeard(code) -> {
                question.visibility = View.GONE
                say(getString(R.string.not_heard))
                show(Stage.NOT_HEARD)
                scheduleClose()
            }
            code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail(getString(R.string.no_mic_permission))
            code == SpeechRecognizer.ERROR_CLIENT -> Unit   // our own cancel before a follow-up
            else -> fail(getString(R.string.recognizer_error, code))
        }
    }

    /** The question as the viewer's own message, a bubble on the right; it rises in the first time. */
    private fun showQuestion(text: String, live: Boolean) {
        question.text = text
        question.setTextColor(if (live) LIVE else TEXT)
        if (question.visibility != View.VISIBLE) rise(question)
    }

    // ---- the answer ------------------------------------------------------------------------------------------

    private fun ask(text: String) {
        val key = prefs.apiKey
        if (key.isBlank()) return fail(getString(R.string.error_no_key))
        show(Stage.THINKING)
        var sentences = SentenceBuffer()
        val speak = prefs.speak
        val answerer = Answerer(ChatClient(prefs.baseUrl, key), conversation, prefs.model, search, memory)
        asking = scope.launch {
            try {
                answerer.answer(text, object : Answerer.Events {
                    override fun onDelta(text: String) {
                        if (stage != Stage.ANSWERING) show(Stage.ANSWERING)
                        typewriter.append(text)
                        if (speak) sentences.add(text).forEach(speaker::say)
                    }

                    override fun onSearching(query: String) {
                        // A "let me check" before the search is not the answer; the answer follows the results.
                        typewriter.clear()
                        sentences = SentenceBuffer()
                        favs.removeAllViews()
                        pillText.text = getString(R.string.searching)
                        pillQuery.text = query
                        setPill(Pill.SEARCHING)
                        show(Stage.SEARCHING)
                    }

                    override fun onSources(sources: List<Source>) {
                        pillText.text = if (sources.isEmpty()) getString(R.string.search_nothing)
                        else resources.getQuantityString(R.plurals.search_found, sources.size, sources.size)
                        showFavicons(sources)
                        setPill(Pill.FOUND)
                    }

                    override fun onSearchFailed(badKey: Boolean) {
                        pillText.text = getString(if (badKey) R.string.search_bad_key else R.string.search_failed)
                        favs.removeAllViews()
                        setPill(Pill.FAILED)
                    }

                    override fun onWeather(place: String) {
                        // As with a search: whatever came before the forecast is a preamble.
                        typewriter.clear()
                        sentences = SentenceBuffer()
                        favs.removeAllViews()
                        pillText.text = getString(R.string.weather_loading)
                        pillQuery.text = place
                        setPill(Pill.SEARCHING)
                        show(Stage.SEARCHING)
                    }

                    override fun onForecast(place: Place) {
                        pillText.text = getString(R.string.weather_for, place.name)
                        favs.removeAllViews()
                        setPill(Pill.FOUND)
                    }

                    override fun onWeatherFailed() {
                        pillText.text = getString(R.string.weather_failed)
                        favs.removeAllViews()
                        setPill(Pill.FAILED)
                    }

                    override fun onRemembered(fact: String) {
                        pillText.text = getString(R.string.remembered)
                        pillQuery.text = fact
                        favs.removeAllViews()
                        setPill(Pill.FOUND)
                        pillQuery.visibility = View.VISIBLE
                    }
                })
                memory.saveRecent(conversation.history)
                if (speak) sentences.flush()?.let(speaker::say)
                show(Stage.DONE)
                // Nothing left to say aloud (voice off, or an answer of symbols only): count down now.
                if (!speaking) scheduleClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatException) {
                fail(message(e))
            }
        }
    }

    private fun message(e: ChatException) = when (e.kind) {
        ChatException.Kind.NO_KEY -> getString(R.string.error_no_key)
        ChatException.Kind.BAD_KEY -> getString(R.string.error_bad_key)
        ChatException.Kind.NO_BALANCE -> getString(R.string.error_no_balance)
        ChatException.Kind.RATE_LIMIT -> getString(R.string.error_rate_limit)
        ChatException.Kind.OFFLINE -> getString(R.string.error_offline)
        ChatException.Kind.SERVER -> getString(R.string.error_server, e.message)
    }

    private fun fail(text: String) {
        say(text)
        show(Stage.ERROR)
        scheduleClose()
    }

    /** A line of our own in the answer place, shown at once. */
    private fun say(text: String) {
        typewriter.clear()
        typewriter.append(text)
        typewriter.finish()
    }

    /** Nothing more to say: the panel goes away by itself after a while, as a system overlay would. */
    private fun scheduleClose() {
        card.removeCallbacks(autoClose)
        // Never while it listens or works on an answer: a long question used to run into the countdown.
        if (stage in BUSY_STAGES) return
        card.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    // ---- what each stage shows -------------------------------------------------------------------------------

    private fun show(next: Stage) {
        if (next != stage) morph()
        stage = next
        if (stage in BUSY_STAGES) card.removeCallbacks(autoClose)
        render()
    }

    /**
     * The next layout change animates: what leaves fades out, what comes fades in, and the panel glides to its
     * new height, so a stage change (an arrow for the next question, a search, the answer) is never a jump.
     */
    private fun morph() {
        TransitionManager.beginDelayedTransition(card as ViewGroup, TransitionSet()
            .addTransition(Fade(Fade.OUT))
            .addTransition(ChangeBounds())
            .addTransition(Fade(Fade.IN))
            .setOrdering(TransitionSet.ORDERING_TOGETHER)
            .setDuration(280)
            .setInterpolator(ease))
    }

    private fun render() {
        orb.mode = when {
            stage == Stage.LISTENING -> MicOrbView.Mode.LISTENING
            stage == Stage.THINKING || stage == Stage.SEARCHING -> MicOrbView.Mode.THINKING
            stage == Stage.ERROR -> MicOrbView.Mode.ERROR
            speaking -> MicOrbView.Mode.SPEAKING
            else -> MicOrbView.Mode.IDLE
        }

        status.visibility = if (stage in STATUS_STAGES) View.VISIBLE else View.GONE
        wave.visibility = if (stage == Stage.LISTENING) View.VISIBLE else View.GONE
        statusText.shimmer = stage == Stage.THINKING
        statusText.text = when (stage) {
            Stage.LISTENING -> getString(R.string.listening)
            Stage.THINKING -> getString(R.string.thinking)
            Stage.READY -> getString(R.string.ready_again)
            else -> ""
        }

        pill.visibility = if (pillState != Pill.NONE && stage in PILL_STAGES) View.VISIBLE else View.GONE

        val answerShown = stage in ANSWER_STAGES && !(stage == Stage.READY && answer.text.isEmpty())
        answerScroll.visibility = if (answerShown) View.VISIBLE else View.GONE
        answer.setTextColor(
            when (stage) {
                Stage.NOT_HEARD -> MUTED
                Stage.ERROR -> ERROR
                else -> TEXT
            },
        )
        typewriter.speaking = speaking && (stage == Stage.ANSWERING || stage == Stage.DONE)
        renderHints(hintsFor())
    }

    private fun setPill(state: Pill) {
        morph()
        pillState = state
        spinner.visibility = if (state == Pill.SEARCHING) View.VISIBLE else View.GONE
        favs.visibility = if (state == Pill.FOUND && favs.childCount > 0) View.VISIBLE else View.GONE
        pillQuery.visibility = if (state == Pill.SEARCHING) View.VISIBLE else View.GONE
        render()
    }

    /** One small round "favicon" per source, the site's first letter, popping in one after another. */
    private fun showFavicons(sources: List<Source>) {
        favs.removeAllViews()
        sources.take(MAX_FAVICONS).forEachIndexed { i, source ->
            val size = (14 * dp).toInt()
            val fav = TextView(this).apply {
                text = source.site.firstOrNull()?.uppercase() ?: "?"
                gravity = Gravity.CENTER
                textSize = 6.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(FAVICON_COLORS[i % FAVICON_COLORS.size])
                    setStroke(dp.toInt(), PILL)
                }
            }
            favs.addView(fav, LinearLayout.LayoutParams(size, size).apply {
                if (i > 0) marginStart = (-4.5f * dp).toInt()
            })
            fav.alpha = 0f
            fav.scaleX = 0.6f
            fav.scaleY = 0.6f
            fav.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(70L * i).setDuration(300)
                .setInterpolator(ease).start()
        }
    }

    private fun hintsFor(): List<Hint> {
        val close = Hint(listOf(Key.BACK), R.string.hint_close)
        val again = listOf(Key.ARROWS, Key.THEN, Key.MIC)
        return when {
            stage == Stage.LISTENING -> listOf(Hint(listOf(Key.MIC), R.string.hint_hold), close)
            stage == Stage.READY -> listOf(close)
            stage == Stage.THINKING || stage == Stage.SEARCHING -> listOf(Hint(listOf(Key.BACK), R.string.hint_cancel))
            stage == Stage.NOT_HEARD || stage == Stage.ERROR -> listOf(Hint(again, R.string.hint_retry), close)
            speaking -> listOf(Hint(listOf(Key.OK), R.string.hint_stop_voice), close)
            else -> listOf(Hint(again, R.string.hint_again), close)
        }
    }

    /** The remote keys that work right now, as keycaps; rebuilt, with a fade, only when they change. */
    private fun renderHints(wanted: List<Hint>) {
        if (wanted == shownHints) return
        shownHints = wanted
        hints.removeAllViews()
        wanted.forEachIndexed { i, hint ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            hint.keys.forEachIndexed { k, key ->
                row.addView(keyView(key), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (15 * dp).toInt(),
                ).apply { if (k > 0) marginStart = (3 * dp).toInt() })
            }
            row.addView(TextView(this).apply {
                setText(hint.label)
                textSize = 9.5f
                setTextColor(SECONDARY)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = (5 * dp).toInt() })
            hints.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { if (i > 0) marginStart = (15 * dp).toInt() })
        }
        hints.alpha = 0f
        hints.animate().alpha(1f).setDuration(250).start()
    }

    private fun keyView(key: Key): View {
        val pad = (4 * dp).toInt()
        return when (key) {
            Key.THEN -> TextView(this).apply {
                setText(R.string.hint_then)
                textSize = 8f
                gravity = Gravity.CENTER
                setTextColor(THEN)
            }
            Key.OK -> TextView(this).apply {
                text = "OK"
                textSize = 7f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(KEY)
                minWidth = (17 * dp).toInt()
                setPadding(pad, 0, pad, 0)
                setBackgroundResource(R.drawable.key)
            }
            else -> ImageView(this).apply {
                setImageResource(
                    when (key) {
                        Key.MIC -> R.drawable.ic_key_mic
                        Key.ARROWS -> R.drawable.ic_key_arrows
                        else -> R.drawable.ic_key_back
                    },
                )
                scaleType = ImageView.ScaleType.CENTER
                minimumWidth = (17 * dp).toInt()
                setPadding(pad, 0, pad, 0)
                setBackgroundResource(R.drawable.key)
            }
        }
    }

    // ---- in and out ------------------------------------------------------------------------------------------

    /** A view appearing: it rises a few dp as it fades in. */
    private fun rise(view: View) {
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = 4 * dp
        view.animate().alpha(1f).translationY(0f).setInterpolator(ease).setDuration(260).start()
    }

    private fun enter() {
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(300).start()
        card.alpha = 0f
        card.translationY = 14 * dp
        card.scaleX = 0.985f
        card.scaleY = 0.985f
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setInterpolator(ease).setDuration(380).start()
    }

    private fun close() {
        if (closing) return
        closing = true
        voice.stop()
        speaker.stop()
        asking?.cancel()
        scrim.animate().alpha(0f).setDuration(240).start()
        card.animate().alpha(0f).translationY(10 * dp).scaleX(0.99f).scaleY(0.99f)
            .setInterpolator(AccelerateInterpolator()).setDuration(240)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finish()
                    overridePendingTransition(0, 0)
                }
            }).start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            close()
            return true
        }
        // OK while the answer is read out: stop talking, keep the panel.
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            speaker.stop()
            return true
        }
        // An arrow readies a follow-up. The remote keeps its microphone "open" after a session only Google's
        // privileged assistant can close, so the next voice press would close it instead; any real key press
        // resets the remote (docs/FINDINGS.md, 7). The arrow is that key press, given a meaning.
        if (keyCode in ARROWS && stage !in BUSY_STAGES) {
            speaker.stop()
            // "Did not catch that, press an arrow" has been done; only a real answer stays on screen.
            if (stage == Stage.NOT_HEARD || stage == Stage.ERROR) typewriter.clear()
            show(Stage.READY)
            scheduleClose()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** The viewer went elsewhere (Home, an app, another input): the overlay does not linger behind. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        card.removeCallbacks(autoClose)
        scope.cancel()
        voice.release()
        speaker.release()
        super.onDestroy()
    }

    companion object {
        private const val AUTO_CLOSE_MS = 25_000L
        private const val EXTRA_QUESTION = "question"
        private const val MAX_FAVICONS = 5
        private val ARROWS = setOf(
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        )
        private val STATUS_STAGES = setOf(Stage.LISTENING, Stage.THINKING, Stage.READY)
        private val PILL_STAGES = setOf(Stage.SEARCHING, Stage.ANSWERING, Stage.DONE)
        private val BUSY_STAGES = setOf(Stage.LISTENING, Stage.THINKING, Stage.SEARCHING)
        private val ANSWER_STAGES = setOf(Stage.ANSWERING, Stage.DONE, Stage.READY, Stage.NOT_HEARD, Stage.ERROR)

        private val TEXT = Color.parseColor("#FFF8FAFF")
        private val LIVE = Color.parseColor("#FFC7C9DE")
        private val MUTED = Color.parseColor("#FFD5D5DC")
        private val ERROR = Color.parseColor("#FFF4AFAF")
        private val SECONDARY = Color.parseColor("#FFA3A3AD")
        private val THEN = Color.parseColor("#FF6F6F78")
        private val KEY = Color.parseColor("#FFE1E1E8")
        private val PILL = Color.parseColor("#FF35363A")
        private val FAVICON_COLORS = intArrayOf(
            Color.parseColor("#FF3E7BD8"), Color.parseColor("#FFE5483F"), Color.parseColor("#FF3A9D6A"),
            Color.parseColor("#FF7A5AD8"), Color.parseColor("#FFC98A1C"),
        )

        /**
         * The system prompt: how to answer and what the assistant cannot do, today's date and time (the model
         * has no clock), and whether it can search the web, so it neither guesses at news nor claims to look.
         */
        fun systemPrompt(context: Context, searchOn: Boolean, facts: List<String>): String {
            val now = Date()
            val today = context.getString(
                R.string.prompt_today,
                DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMMyyyy"), now),
                DateFormat.getTimeFormat(context).format(now),
            )
            val search = context.getString(if (searchOn) R.string.prompt_search_on else R.string.prompt_search_off)
            val known = if (facts.isEmpty()) null
            else context.getString(R.string.prompt_facts, facts.joinToString("\n") { "- $it" })
            return listOfNotNull(
                context.getString(R.string.system_prompt), today, search, context.getString(R.string.prompt_memory), known,
            ).joinToString("\n\n")
        }
    }
}
