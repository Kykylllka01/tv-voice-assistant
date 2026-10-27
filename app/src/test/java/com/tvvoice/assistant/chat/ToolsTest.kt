package com.tvvoice.assistant.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {
    @Test
    fun readsAToolCallPiece() {
        val first = """data: {"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":""}}]}}]}"""
        assertEquals(Sse.Event.ToolCallPiece(0, "call_1", "web_search", ""), Sse.parse(first))
        val next = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\""}}]}}]}"""
        assertEquals(Sse.Event.ToolCallPiece(0, null, null, "{\"query\""), Sse.parse(next))
    }

    @Test
    fun assemblesCallsFromPieces() {
        val calls = ToolCallAssembler()
        calls.add(Sse.Event.ToolCallPiece(0, "call_1", "web_search", ""))
        calls.add(Sse.Event.ToolCallPiece(0, null, null, "{\"query\":"))
        calls.add(Sse.Event.ToolCallPiece(0, null, null, " \"погода Москва\"}"))
        val call = calls.calls().single()
        assertEquals("call_1", call.id)
        assertEquals("погода Москва", call.query)
    }

    @Test
    fun badArgumentsHaveNoQuery() {
        assertNull(ToolCall("c", "web_search", "{\"query\":").query)
        assertNull(ToolCall("c", "web_search", "{}").query)
    }

    @Test
    fun requestCarriesToolsAndTheSearchRound() {
        val call = ToolCall("call_1", "web_search", "{\"query\":\"курс доллара\"}")
        val body = JSONObject(
            Conversation("sys").requestBody(
                "deepseek-chat", "Какой курс?", Tools.definitions(search = true),
                listOf(Tools.assistantTurn("", listOf(call)), Tools.result("call_1", "[1] ЦБ")),
            ),
        )
        assertEquals("web_search", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        val messages = body.getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals("call_1", messages.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", messages.getJSONObject(3).getString("role"))
    }

    @Test
    fun memoryIsOfferedWithoutSearch() {
        val names = Tools.definitions(search = false).let { tools ->
            (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        }
        assertTrue(names.containsAll(listOf(Tools.WEATHER, Tools.REMEMBER, Tools.FORGET, Tools.OPEN_APP, Tools.SET_REMINDER)))
        assertTrue(Tools.WEB_SEARCH !in names)
        assertEquals("Живёт в Москве", ToolCall("c", Tools.REMEMBER, "{\"fact\":\" Живёт в Москве \"}").fact)
    }

    @Test
    fun aRememberedTurnKeepsItsMemoryCall() {
        val call = ToolCall("call_7", Tools.REMEMBER, "{\"fact\":\"Болеет за Зенит\"}")
        val conversation = Conversation("sys")
        conversation.remember(
            "Запомни: я болею за Зенит", "Запомнил.",
            listOf(Tools.assistantTurn("", listOf(call)), Tools.result("call_7", "Saved.")),
        )
        val messages = JSONObject(conversation.requestBody("m", "Что ты обо мне знаешь?")).getJSONArray("messages")
        val roles = (0 until messages.length()).map { messages.getJSONObject(it).getString("role") }
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant", "user"), roles)
        assertEquals("call_7", messages.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test
    fun plainRequestOffersNoTools() {
        assertTrue(!JSONObject(Conversation("sys").requestBody("m", "q")).has("tools"))
    }
}
