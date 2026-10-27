package com.tvvoice.assistant.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {
    @Test
    fun readsTavilyResults() {
        val json = """{"query":"q","results":[
            {"title":"Погода в Москве","url":"https://www.gismeteo.ru/weather-moscow/","content":"Сегодня +5","score":0.9},
            {"title":"No link","url":"","content":"x"}]}"""
        val sources = WebSearch.parse(json)
        assertEquals(1, sources.size)
        assertEquals("gismeteo.ru", sources[0].site)
        assertTrue(WebSearch.forModel(sources).startsWith("[1] Погода в Москве\nhttps://www.gismeteo.ru/weather-moscow/\nСегодня +5"))
    }

    @Test
    fun saysWhenNothingWasFound() {
        assertEquals("No results.", WebSearch.forModel(WebSearch.parse("""{"results":[]}""")))
    }

    @Test
    fun onlyWebLinksBecomeSources() {
        val json = """{"results":[
            {"title":"a","url":"javascript:alert(1)","content":"x"},
            {"title":"b","url":"intent://scan/#Intent;scheme=zxing;end","content":"x"},
            {"title":"c","url":"file:///sdcard/x","content":"x"},
            {"title":"d","url":"https://rbc.ru/news/1","content":"x"}]}"""
        assertEquals(listOf("https://rbc.ru/news/1"), WebSearch.parse(json).map { it.url })
    }
}
