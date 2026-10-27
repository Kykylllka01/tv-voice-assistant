package com.tvvoice.assistant.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    @Test
    fun startsWithTheSystemPromptAndEndsWithTheQuestion() {
        val m = Conversation("Отвечай коротко").messagesFor("Сколько лет Земле?")
        assertEquals(listOf("system", "user"), m.map { it.role })
        assertEquals("Сколько лет Земле?", m.last().content)
    }

    @Test
    fun keepsOnlyTheLastTurns() {
        val c = Conversation("s", maxTurns = 2)
        c.remember("q1", "a1")
        c.remember("q2", "a2")
        c.remember("q3", "a3")
        assertEquals(listOf("s", "q2", "a2", "q3", "a3", "q4"), c.messagesFor("q4").map { it.content })
    }

    @Test
    fun forgetsAnUnansweredQuestion() {
        val c = Conversation("s")
        c.remember("q", " ")
        assertEquals(0, c.size)
    }

    @Test
    fun buildsAStreamingRequest() {
        val body = JSONObject(Conversation("s").requestBody("deepseek-chat", "q"))
        assertEquals("deepseek-chat", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(2, body.getJSONArray("messages").length())
    }
}
