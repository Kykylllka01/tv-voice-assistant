package com.tvvoice.assistant.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tvvoice.assistant.AssistActivity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A reminder: what to say and when, in epoch milliseconds. */
data class Reminder(val id: Int, val at: Long, val text: String)

/**
 * Reminders and timers set by voice ("напомни через 15 минут выключить духовку"). Each is an alarm that opens the
 * assistant panel over whatever is on screen, HDMI included, and reads it out. Kept on the TV and set again after
 * a reboot.
 */
class Reminders(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Those still to come, soonest first. Unreadable data is set aside once, not overwritten by the next save. */
    val all: List<Reminder>
        get() {
            val raw = prefs.getString(LIST, "[]").orEmpty()
            val list = parse(raw)
            if (list == null) {
                Log.w(TAG, "reminders unreadable, kept aside")
                prefs.edit().putString(LIST + "_unreadable", raw).putString(LIST, "[]").apply()
                return emptyList()
            }
            return list.sortedBy { it.at }
        }

    /** Sets the alarm first and keeps the reminder only once it is set, so none is listed without an alarm. */
    fun add(text: String, at: Long): Reminder {
        val id = prefs.getInt(NEXT_ID, 1)
        val reminder = Reminder(id, at, text.trim())
        schedule(reminder)
        save(all + reminder, nextId = id + 1)
        return reminder
    }

    /** Cancels those whose text mentions [what]; how many. */
    fun cancel(what: String): Int {
        val needle = what.trim()
        if (needle.isEmpty()) return 0
        val gone = all.filter { it.text.contains(needle, ignoreCase = true) }
        gone.forEach { alarms.cancel(pending(it)) }
        if (gone.isNotEmpty()) save(all - gone.toSet())
        return gone.size
    }

    fun remove(id: Int) = save(all.filterNot { it.id == id })

    /** After a reboot alarms are gone: set the future ones again, drop those missed while the TV was off. */
    fun rescheduleAll(now: Long = System.currentTimeMillis()) {
        val (future, missed) = all.partition { it.at > now }
        if (missed.isNotEmpty()) save(future)
        future.forEach(::schedule)
    }

    /** One line per reminder, for the model: "- today 21:45: выключить духовку". */
    fun describe(zone: ZoneId = ZoneId.systemDefault()): List<String> = all.map { r ->
        val time = Instant.ofEpochMilli(r.at).atZone(zone)
        val today = ZonedDateTime.now(zone).toLocalDate()
        val day = when (time.toLocalDate()) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> time.toLocalDate().toString()
        }
        "- $day ${time.format(HH_MM)}: ${r.text}"
    }

    private fun schedule(r: Reminder) {
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        try {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(r))
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused, setting an inexact one")
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(r))
    }

    private fun pending(r: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context, r.id,
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, r.id).putExtra(EXTRA_TEXT, r.text),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun save(list: List<Reminder>, nextId: Int? = null) {
        val json = JSONArray()
        for (r in list) json.put(JSONObject().put("id", r.id).put("at", r.at).put("text", r.text))
        prefs.edit().putString(LIST, json.toString()).apply { if (nextId != null) putInt(NEXT_ID, nextId) }.apply()
    }

    companion object {
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TEXT = "reminder_text"
        private const val LIST = "list"
        private const val NEXT_ID = "next_id"
        private const val TAG = "TvVoice"
        /** The panel as a reminder opens it: an alias only this app can start, so no other app can make it speak. */
        const val ALERT = "com.tvvoice.assistant.ReminderAlert"
        val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * When a reminder goes off: [minutes] from [now], or at [at] ("21:45", "9:05") the next time that clock
         * time comes round, today or tomorrow. Null when neither is usable.
         */
        fun timeOf(now: ZonedDateTime, minutes: Int?, at: String?): ZonedDateTime? {
            if (minutes != null && minutes > 0) return now.plusMinutes(minutes.toLong())
            val clock = at?.trim()?.let { Regex("""(\d{1,2})[:.](\d{2})""").matchEntire(it) } ?: return null
            val (h, m) = clock.destructured
            if (h.toInt() > 23 || m.toInt() > 59) return null
            val today = now.with(LocalTime.of(h.toInt(), m.toInt()))
            return if (today.isAfter(now)) today else today.plusDays(1)
        }

        /** The reminders in [json]; one bad entry is skipped, not the list. Null when it is no list at all. */
        fun parse(json: String): List<Reminder>? {
            val list = try {
                JSONArray(json)
            } catch (e: JSONException) {
                return null
            }
            return (0 until list.length()).mapNotNull { i ->
                try {
                    list.getJSONObject(i).let { Reminder(it.getInt("id"), it.getLong("at"), it.getString("text")) }
                } catch (e: JSONException) {
                    null
                }
            }
        }
    }
}

/** A reminder's alarm, or the TV starting up: show the reminder, or set the alarms again. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminders(context).rescheduleAll()
            return
        }
        val text = intent.getStringExtra(Reminders.EXTRA_TEXT) ?: return
        // Shown first, struck off after: a reminder that cannot be shown is not lost without a trace.
        try {
            context.startActivity(
                Intent().setClassName(context, Reminders.ALERT)
                    .putExtra(AssistActivity.EXTRA_REMINDER, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.w("TvVoice", "reminder could not be shown: ${e.javaClass.simpleName}")
        }
        Reminders(context).remove(intent.getIntExtra(Reminders.EXTRA_ID, -1))
    }
}
