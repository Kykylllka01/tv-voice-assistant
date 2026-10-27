package com.tvvoice.assistant.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherTest {
    private val moscow = Place("Москва", "Россия", 55.75222, 37.61556)

    @Test
    fun findsThePlace() {
        val json = """{"results":[{"id":524901,"name":"Москва","latitude":55.75222,"longitude":37.61556,""" +
            """"timezone":"Europe/Moscow","country":"Россия"}],"generationtime_ms":0.2}"""
        assertEquals(moscow, Weather.parsePlace(json))
        assertNull(Weather.parsePlace("""{"generationtime_ms":0.2}"""))
    }

    @Test
    fun describesNowAndEachDay() {
        val json = """{"timezone":"Europe/Moscow",
            "current":{"time":"2026-10-27T18:45","temperature_2m":5.8,"apparent_temperature":1.2,
              "relative_humidity_2m":58,"precipitation":0.00,"weather_code":0,"wind_speed_10m":4.04},
            "daily":{"time":["2026-10-27","2026-10-28"],"weather_code":[3,61],"temperature_2m_max":[9.1,9.8],
              "temperature_2m_min":[1.0,0.9],"precipitation_probability_max":[0,40],"wind_speed_10m_max":[4.11,5.3]}}"""
        assertEquals(
            listOf(
                "Москва, Россия (local time zone Europe/Moscow).",
                "Now (2026-10-27 18:45): 6°C, feels like 1°C, clear sky, wind 4.0 m/s, humidity 58%, precipitation 0 mm.",
                "2026-10-27: overcast, 1°C to 9°C, chance of precipitation 0%, wind up to 4.1 m/s.",
                "2026-10-28: light rain, 1°C to 10°C, chance of precipitation 40%, wind up to 5.3 m/s.",
            ),
            Weather.forModel(moscow, json).lines(),
        )
    }

    @Test
    fun namesUnknownCodes() {
        assertEquals("thunderstorm with hail", Weather.sky(99))
        assertEquals("weather code 42", Weather.sky(42))
    }
}
