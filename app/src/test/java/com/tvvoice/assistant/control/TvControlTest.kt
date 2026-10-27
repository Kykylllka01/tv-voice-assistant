package com.tvvoice.assistant.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvControlTest {
    // Apps on the DIGMA TV, by their names on the launcher.
    private val apps = listOf("YouTube", "YouTube Music", "Кинопоиск: фильмы и сериалы", "Иви", "VK Видео", "Netflix", "Twitch")

    @Test
    fun findsAnAppByItsName() {
        assertEquals("YouTube", TvControl.match(apps, "youtube"))
        assertEquals("YouTube Music", TvControl.match(apps, "YouTube music"))
        assertEquals("Кинопоиск: фильмы и сериалы", TvControl.match(apps, "Кинопоиск"))
        assertEquals("VK Видео", TvControl.match(apps, "vk видео"))
        assertEquals("VK Видео", TvControl.match(apps, "VK"))
    }

    @Test
    fun nothingForAnUnknownName() {
        assertNull(TvControl.match(apps, "Окко"))
        assertNull(TvControl.match(apps, " "))
    }
}
