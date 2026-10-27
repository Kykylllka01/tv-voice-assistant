package com.tvvoice.assistant.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceBufferTest {
    @Test
    fun emitsSentencesAsTheyComplete() {
        val b = SentenceBuffer()
        assertEquals(emptyList<String>(), b.add("Сегодня в Мо"))
        assertEquals(listOf("Сегодня в Москве ясно."), b.add("скве ясно. Днём "))
        assertEquals(listOf("Днём до +5°!"), b.add("до +5°! Вечером ветер"))
        assertEquals("Вечером ветер", b.flush())
    }

    @Test
    fun flushesOnce() {
        val b = SentenceBuffer()
        b.add("Без точки в конце")
        assertEquals("Без точки в конце", b.flush())
        assertNull(b.flush())
    }

    @Test
    fun doesNotSplitNumbersOrAbbreviations() {
        val b = SentenceBuffer()
        assertEquals(emptyList<String>(), b.add("Число пи 3.14, т.е. примерно три"))
        assertEquals("Число пи 3.14, т.е. примерно три", b.flush())
    }

    @Test
    fun splitsOnLineBreaks() {
        assertEquals(listOf("Шаги", "раз"), SentenceBuffer().add("**Шаги**\n- раз\n"))
    }
}

class SpeakableTest {
    @Test
    fun dropsMarkdown() {
        assertEquals("Важно: читать ссылку", Speakable.of("**Важно**: читать [ссылку](https://x.y)"))
        assertEquals("Заголовок", Speakable.of("## Заголовок"))
        assertEquals("пункт", Speakable.of("1. пункт"))
    }

    @Test
    fun nothingToSay() {
        assertNull(Speakable.of(" **  ** "))
    }

    @Test
    fun displayKeepsLines() {
        assertEquals("Шаги\n- раз", Speakable.display("**Шаги**\n- раз"))
    }
}

class SpeakableSymbolsTest {
    @Test
    fun dropsEmojiAndSourceNumbers() {
        assertEquals("Сегодня солнечно, +5.", Speakable.of("Сегодня солнечно 🌞, +5 [1]."))
        assertEquals("Курс 95 рублей [1, 2].", Speakable.display("Курс 95 рублей [1, 2]. 💵"))
    }
}
