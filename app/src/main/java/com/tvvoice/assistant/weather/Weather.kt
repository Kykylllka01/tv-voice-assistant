package com.tvvoice.assistant.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** A place the geocoder found: what the card shows and where the forecast is for. */
data class Place(val name: String, val country: String, val latitude: Double, val longitude: Double)

/** Why there is no forecast: the place was not found, or the service did not answer. */
class WeatherException(message: String) : Exception(message)

/**
 * The weather for the model's `weather` calls, from Open-Meteo (https://open-meteo.com): measured now and forecast
 * by day, for any place by name, with no key. Unlike a web search it is never stale: the numbers are for this
 * hour, in the place's own time zone.
 */
class Weather(
    private val geocoder: String = GEOCODER,
    private val forecaster: String = FORECASTER,
) {
    /** The weather in [place] now and for the next [days] days, as the model reads it. */
    suspend fun forecast(place: String, days: Int): Pair<Place, String> = withContext(Dispatchers.IO) {
        val name = URLEncoder.encode(place.trim(), "UTF-8")
        val found = parsePlace(get("$geocoder?name=$name&count=1&language=ru&format=json"))
            ?: throw WeatherException("place not found: $place")
        val json = get(
            "$forecaster?latitude=${found.latitude}&longitude=${found.longitude}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code," +
                "wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max,wind_speed_10m_max&timezone=auto&wind_speed_unit=ms" +
                "&forecast_days=${days.coerceIn(1, MAX_DAYS)}",
        )
        found to forModel(found, json)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 12_000
            val code = c.responseCode
            if (code !in 200..299) {
                val detail = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
                Log.w(TAG, "weather HTTP $code: $detail")
                throw WeatherException("weather HTTP $code")
            }
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            throw WeatherException(e.message ?: "no connection")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val GEOCODER = "https://geocoding-api.open-meteo.com/v1/search"
        const val FORECASTER = "https://api.open-meteo.com/v1/forecast"
        const val MAX_DAYS = 7
        private const val TAG = "TvVoice"

        /** The best match of a geocoder response, or null when there is none. */
        fun parsePlace(json: String): Place? = try {
            JSONObject(json).optJSONArray("results")?.optJSONObject(0)?.let {
                Place(it.getString("name"), it.optString("country"), it.getDouble("latitude"), it.getDouble("longitude"))
            }
        } catch (e: JSONException) {
            throw WeatherException("unreadable geocoder response")
        }

        /** Now, then one line per day: what the model answers from, in plain words and units. */
        fun forModel(place: Place, json: String): String = try {
            val root = JSONObject(json)
            val lines = mutableListOf("${place.name}, ${place.country} (local time zone ${root.optString("timezone")}).")
            root.optJSONObject("current")?.let { now ->
                lines += "Now (${now.getString("time").replace('T', ' ')}): ${temp(now.getDouble("temperature_2m"))}, " +
                    "feels like ${temp(now.getDouble("apparent_temperature"))}, ${sky(now.getInt("weather_code"))}, " +
                    "wind ${now.getDouble("wind_speed_10m").fmt()} m/s, humidity ${now.getInt("relative_humidity_2m")}%, " +
                    "precipitation ${now.getDouble("precipitation").fmt()} mm."
            }
            root.optJSONObject("daily")?.let { daily ->
                val days = daily.getJSONArray("time")
                for (i in 0 until days.length()) {
                    lines += "${days.getString(i)}: ${sky(daily.getJSONArray("weather_code").getInt(i))}, " +
                        "${temp(daily.getJSONArray("temperature_2m_min").getDouble(i))} to " +
                        "${temp(daily.getJSONArray("temperature_2m_max").getDouble(i))}, " +
                        "chance of precipitation ${daily.getJSONArray("precipitation_probability_max").optInt(i)}%, " +
                        "wind up to ${daily.getJSONArray("wind_speed_10m_max").getDouble(i).fmt()} m/s."
                }
            }
            lines.joinToString("\n")
        } catch (e: JSONException) {
            throw WeatherException("unreadable forecast")
        }

        private fun temp(c: Double) = "${Math.round(c)}°C"

        private fun Double.fmt() = if (this == Math.floor(this)) toInt().toString() else "%.1f".format(Locale.ROOT, this)

        /** WMO weather interpretation codes, as Open-Meteo documents them. */
        fun sky(code: Int): String = when (code) {
            0 -> "clear sky"
            1 -> "mainly clear"
            2 -> "partly cloudy"
            3 -> "overcast"
            45, 48 -> "fog"
            51, 53, 55 -> "drizzle"
            56, 57 -> "freezing drizzle"
            61 -> "light rain"
            63 -> "rain"
            65 -> "heavy rain"
            66, 67 -> "freezing rain"
            71 -> "light snow"
            73 -> "snow"
            75 -> "heavy snow"
            77 -> "snow grains"
            80, 81, 82 -> "rain showers"
            85, 86 -> "snow showers"
            95 -> "thunderstorm"
            96, 99 -> "thunderstorm with hail"
            else -> "weather code $code"
        }
    }
}
