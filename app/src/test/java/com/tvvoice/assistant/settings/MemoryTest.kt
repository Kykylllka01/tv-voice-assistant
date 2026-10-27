package com.tvvoice.assistant.settings

import com.tvvoice.assistant.chat.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryTest {
    @Test
    fun keepsOneTrimmedLinePerFact() {
        assertEquals(
            listOf("Живёт в Москве", "Погоду смотрит на gismeteo.ru"),
            Memory.normalize(listOf("  - Живёт в Москве ", "", "Погоду смотрит на gismeteo.ru\n\n")),
        )
    }

    @Test
    fun aRepeatMovesToTheEnd() {
        assertEquals(
            listOf("Болеет за Зенит", "живёт в москве"),
            Memory.normalize(listOf("Живёт в Москве", "Болеет за Зенит", "живёт в москве")),
        )
    }

    @Test
    fun keepsTheNewestFactsWithinTheLimit() {
        val many = (1..Memory.MAX_FACTS + 5).map { "Факт $it" }
        val kept = Memory.normalize(many)
        assertEquals(Memory.MAX_FACTS, kept.size)
        assertEquals("Факт ${Memory.MAX_FACTS + 5}", kept.last())
        assertEquals(Memory.MAX_CHARS, Memory.normalize(listOf("а".repeat(500))).single().length)
    }

    @Test
    fun forgetsWhatMentionsTheWords() {
        val facts = listOf("Живёт в Москве", "Погоду смотрит на gismeteo.ru", "Зовут Алексей")
        assertEquals(listOf("Живёт в Москве"), Memory.matching(facts, "москв"))
        assertEquals(emptyList<String>(), Memory.matching(facts, "  "))
    }

    @Test
    fun conversationPicksUpTheLastTurns() {
        val conversation = Conversation("sys", maxTurns = 2)
        conversation.restore(listOf(Conversation.Turn("1", "a"), Conversation.Turn("2", "b"), Conversation.Turn("3", "c")))
        assertEquals(listOf("2", "3"), conversation.history.map { it.question })
        assertEquals(6, conversation.messagesFor("4").size)
    }
}
