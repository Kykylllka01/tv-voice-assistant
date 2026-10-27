package com.tvvoice.assistant.control

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * What the assistant can do to the TV with an ordinary app's rights: open an installed app, search YouTube, set the
 * volume. Switching inputs or channels and turning the TV off take system rights it does not have, and it says so.
 */
class TvControl(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)

    /**
     * The apps the viewer can open, by their names on the TV (name to package), A to Z, this app left out. A
     * PackageManager scan with a label per app: kept for the process for a few minutes, read off the main thread.
     */
    val apps: Map<String, String>
        get() = cached?.takeIf { SystemClock.elapsedRealtime() - cachedAt < APPS_TTL_MS }
            ?: scan().also {
                cached = it
                cachedAt = SystemClock.elapsedRealtime()
            }

    private fun scan(): Map<String, String> {
        val pm = this.context.packageManager
        val found = sortedMapOf<String, String>(String.CASE_INSENSITIVE_ORDER)
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            for (info in launchers) {
                val pkg = info.activityInfo.packageName
                if (pkg == this.context.packageName || pkg in found.values) continue
                found.putIfAbsent(info.loadLabel(pm).toString().trim(), pkg)
            }
        }
        return found
    }

    /** Opens the app called [name]; the name it has on the TV, or null when there is no such app. */
    fun open(name: String): String? {
        val label = match(apps.keys, name) ?: return null
        val pkg = apps.getValue(label)
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return null
        launch(intent)
        return label
    }

    /**
     * The browser a link opens in: the first real one installed (Android TV ships a stub that only says there is
     * no browser), as package to name; null when there is none.
     */
    val browser: Pair<String, String>? by lazy {
        val pm = this.context.packageManager
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        pm.queryIntentActivities(view, 0)
            .firstOrNull { it.activityInfo.packageName !in STUBS && it.activityInfo.packageName != this.context.packageName }
            ?.let { it.activityInfo.packageName to it.loadLabel(pm).toString() }
    }

    /** Opens [url] in [browser]; false when there is none. */
    fun browse(url: String): Boolean {
        val (pkg, _) = browser ?: return false
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg), 0)
        return true
    }

    /** YouTube's results for [query]: the TV app takes a search link (it has no search intent). */
    fun youtube(query: String): Boolean {
        val pm = context.packageManager
        val pkg = YOUTUBE.firstOrNull { pm.getLaunchIntentForPackage(it) != null || pm.getLeanbackLaunchIntentForPackage(it) != null }
            ?: return false
        // Spaces as %20: URLEncoder's "+" is a form encoding the TV app shows literally ("iPhone+17+обзор").
        val link = Uri.parse(SEARCH).buildUpon().appendQueryParameter("search_query", query).build()
        launch(Intent(Intent.ACTION_VIEW, link).setPackage(pkg))
        return true
    }

    /**
     * The media volume: [action] "set" to [level] percent, "up" or "down" by [level] points (10 if not given),
     * "mute" or "unmute". The TV's own volume panel shows the change. The new level in percent.
     */
    fun volume(action: String, level: Int?): Int {
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val now = audio.getStreamVolume(stream)
        fun steps(percent: Int) = Math.round(percent.coerceIn(0, 100) * max / 100f)
        when (action) {
            "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "up" -> audio.setStreamVolume(stream, (now + maxOf(1, steps(level ?: 10))).coerceAtMost(max), AudioManager.FLAG_SHOW_UI)
            "down" -> audio.setStreamVolume(stream, (now - maxOf(1, steps(level ?: 10))).coerceAtLeast(0), AudioManager.FLAG_SHOW_UI)
            else -> audio.setStreamVolume(stream, steps(level ?: 50), AudioManager.FLAG_SHOW_UI)
        }
        return Math.round(audio.getStreamVolume(stream) * 100f / max)
    }

    /** A moment later, so the panel can show what is opening before the app covers it. */
    private fun launch(intent: Intent, delay: Long = LAUNCH_DELAY_MS) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main.postDelayed({
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // Uninstalled meanwhile, or not allowed: nothing to crash over, but worth a line in the log.
                Log.w("TvVoice", "could not open ${intent.`package`}: ${e.javaClass.simpleName}")
            }
        }, delay)
    }

    companion object {
        private const val LAUNCH_DELAY_MS = 1_200L
        private const val APPS_TTL_MS = 5 * 60_000L
        @Volatile private var cached: Map<String, String>? = null
        @Volatile private var cachedAt = 0L
        private const val SEARCH = "https://www.youtube.com/results"
        private val YOUTUBE = listOf("com.google.android.youtube.tv", "com.google.android.youtube")
        private val STUBS = setOf("com.android.tv.frameworkpackagestubs")

        /**
         * The installed name meant by [wanted]: the same letters ignoring case and punctuation, else one that
         * starts with it, else one that contains it ("кинопоиск" finds "Кинопоиск: фильмы и сериалы").
         */
        fun match(names: Collection<String>, wanted: String): String? {
            fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
            val w = key(wanted)
            if (w.isEmpty()) return null
            return names.firstOrNull { key(it) == w }
                ?: names.firstOrNull { key(it).startsWith(w) }
                ?: names.firstOrNull { w in key(it) }
                ?: names.firstOrNull { key(it).isNotEmpty() && key(it) in w }
        }
    }
}
