package com.tvvoice.assistant.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseTest {
    @Test
    fun readsTheAnswerPiece() {
        val line = """data: {"id":"1","choices":[{"index":0,"delta":{"content":"Привет"},"finish_reason":null}]}"""
        assertEquals(Sse.Event.Delta("Привет"), Sse.parse(line))
    }

    @Test
    fun endsOnDone() {
        assertEquals(Sse.Event.Done, Sse.parse("data: [DONE]"))
    }

    @Test
    fun skipsWhatCarriesNoText() {
        assertNull(Sse.parse(""))
        assertNull(Sse.parse(": keep-alive"))
        assertNull(Sse.parse("""data: {"choices":[{"delta":{"role":"assistant","content":""}}]}"""))
        assertNull(Sse.parse("""data: {"choices":[{"delta":{"content":null}}]}"""))
        assertNull(Sse.parse("""data: {"choices":[{"delta":{"reasoning_content":"хм"}}]}"""))
        assertNull(Sse.parse("""data: {"choices":[]}"""))
        assertNull(Sse.parse("data: not json"))
    }

    @Test
    fun reportsAnErrorInTheStream() {
        assertEquals(
            Sse.Event.Failure("Insufficient Balance"),
            Sse.parse("""data: {"error":{"message":"Insufficient Balance"}}"""),
        )
    }
}
