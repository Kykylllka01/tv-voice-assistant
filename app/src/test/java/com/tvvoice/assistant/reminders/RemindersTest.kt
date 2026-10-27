package com.tvvoice.assistant.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RemindersTest {
    private val now = ZonedDateTime.of(2026, 10, 27, 21, 30, 0, 0, ZoneId.of("Asia/Yekaterinburg"))

    @Test
    fun inSomeMinutes() {
        assertEquals(now.plusMinutes(15), Reminders.timeOf(now, 15, null))
    }

    @Test
    fun atAClockTimeTodayOrTomorrow() {
        assertEquals(now.withHour(21).withMinute(45), Reminders.timeOf(now, null, "21:45"))
        assertEquals(now.plusDays(1).withHour(7).withMinute(5), Reminders.timeOf(now, null, "7.05"))
        // The same minute has passed: the next one is tomorrow.
        assertEquals(now.plusDays(1), Reminders.timeOf(now, null, "21:30"))
    }

    @Test
    fun nothingUsable() {
        assertNull(Reminders.timeOf(now, null, null))
        assertNull(Reminders.timeOf(now, 0, "в девять"))
        assertNull(Reminders.timeOf(now, null, "25:00"))
    }

    @Test
    fun readsWhatItSaves() {
        assertEquals(
            listOf(Reminder(3, 1_761_584_700_000, "Выключить духовку")),
            Reminders.parse("""[{"id":3,"at":1761584700000,"text":"Выключить духовку"}]"""),
        )
        assertNull(Reminders.parse("not json"))
        // One broken entry is skipped, the rest kept.
        assertEquals(
            listOf(Reminder(4, 5L, "Позвонить")),
            Reminders.parse("""[{"id":"x"},{"id":4,"at":5,"text":"Позвонить"}]"""),
        )
    }
}
