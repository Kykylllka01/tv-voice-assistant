package com.tvvoice.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerTest {
    @Test
    fun offersOneVoicePerSpeakerOnlineFirst() {
        // The Russian voices of Google's engine on the DIGMA TV, in the order the engine lists them.
        val onTv = listOf(
            "ru-ru-x-rud-network" to true, "ru-ru-x-ruf-local" to false, "ru-ru-x-ruf-network" to true,
            "ru-RU-language" to false, "ru-ru-x-rue-local" to false, "ru-ru-x-rue-network" to true,
            "ru-ru-x-ruc-network" to true, "ru-ru-x-rud-local" to false, "ru-ru-x-dfc-network" to true,
            "ru-ru-x-dfc-local" to false, "ru-ru-x-ruc-local" to false,
        )
        assertEquals(
            listOf(
                "ru-ru-x-dfc-network", "ru-ru-x-ruc-network", "ru-ru-x-rud-network",
                "ru-ru-x-rue-network", "ru-ru-x-ruf-network",
            ),
            Speaker.choices(onTv),
        )
    }

    @Test
    fun keepsALocalVoiceWithoutAnOnlineTwin() {
        assertEquals(listOf("ru-ru-x-abc-local"), Speaker.choices(listOf("ru-ru-x-abc-local" to false)))
    }
}
