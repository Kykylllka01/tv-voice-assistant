package com.tvvoice.assistant.settings

import android.content.Context

/** The app's few settings. The two keys (model API, web search) go through [SecureStore]; the rest is plain. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = secret(KEY)
        set(value) = putSecret(KEY, value)

    /** A key that is saved and can still be opened; one the Keystore lost counts as none, so settings ask again. */
    val hasKey: Boolean get() = apiKey.isNotEmpty()

    /** The Tavily key for web search; without it the assistant answers from what the model knows. */
    var searchKey: String
        get() = secret(SEARCH_KEY)
        set(value) = putSecret(SEARCH_KEY, value)

    val hasSearchKey: Boolean get() = searchKey.isNotEmpty()

    private fun secret(name: String) = SecureStore.decrypt(prefs.getString(name, null)).orEmpty()

    private fun putSecret(name: String, value: String) = prefs.edit().apply {
        if (value.isBlank()) remove(name) else putString(name, SecureStore.encrypt(value.trim()))
    }.apply()

    var baseUrl: String
        get() = prefs.getString(BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(BASE_URL, value.trim().ifEmpty { DEFAULT_BASE_URL }).apply()

    var model: String
        get() = prefs.getString(MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(MODEL, value.trim().ifEmpty { DEFAULT_MODEL }).apply()

    var speak: Boolean
        get() = prefs.getBoolean(SPEAK, true)
        set(value) = prefs.edit().putBoolean(SPEAK, value).apply()

    /** The text-to-speech voice by its engine name ("ru-ru-x-rud-network"); empty for the engine's default. */
    var voice: String
        get() = prefs.getString(VOICE, "").orEmpty()
        set(value) = prefs.edit().putString(VOICE, value).apply()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"
        private const val KEY = "api_key"
        private const val SEARCH_KEY = "search_key"
        private const val BASE_URL = "base_url"
        private const val MODEL = "model"
        private const val SPEAK = "speak"
        private const val VOICE = "voice"
    }
}
