package com.sabihon.todo

import com.sabihon.todo.domain.reminder.ReminderAlertStyle
import com.sabihon.todo.domain.reminder.ReminderPolicy
import com.sabihon.todo.domain.reminder.ReminderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every reminder rule that does not need a device to decide.
 *
 * These exist because a reminder that silently does not fire is invisible: no crash, no log the
 * user will see, just a missed thing. The arithmetic is the part that must be right.
 */
class ReminderPolicyTest {

    private val noon = 1_800_000_000_000L
    private val minute = 60_000L

    // ---- trigger resolution ----------------------------------------------------------

    @Test
    fun `a task with no due date has nothing to be reminded about`() {
        assertNull(ReminderPolicy.resolveTriggerAt(dueAt = null, leadMinutes = 10, nowMillis = noon))
    }

    @Test
    fun `a lead of zero fires exactly at the due time`() {
        val due = noon + 60 * minute
        assertEquals(due, ReminderPolicy.resolveTriggerAt(due, leadMinutes = 0, nowMillis = noon))
    }

    @Test
    fun `a positive lead fires that many minutes early`() {
        val due = noon + 2 * 60 * minute
        val trigger = ReminderPolicy.resolveTriggerAt(due, leadMinutes = 30, nowMillis = noon)
        assertEquals(due - 30 * minute, trigger)
    }

    @Test
    fun `a lead larger than the time remaining still warns now instead of never`() {
        val due = noon + 20 * minute
        val trigger = ReminderPolicy.resolveTriggerAt(due, leadMinutes = 60, nowMillis = noon)
        assertEquals(noon + ReminderPolicy.MIN_DELAY_MS, trigger)
    }

    @Test
    fun `a due time that has already passed schedules nothing`() {
        assertNull(ReminderPolicy.resolveTriggerAt(noon - minute, leadMinutes = 10, nowMillis = noon))
        assertNull(ReminderPolicy.resolveTriggerAt(noon, leadMinutes = 0, nowMillis = noon))
    }

    @Test
    fun `the lead is clamped instead of wrapping into the future`() {
        val due = noon + 400 * 24 * 60 * minute
        val trigger = ReminderPolicy.resolveTriggerAt(due, leadMinutes = -500, nowMillis = noon)
        // Negative becomes zero, so the trigger is the due time, not something after it.
        assertEquals(due, trigger)

        val absurd = ReminderPolicy.resolveTriggerAt(due, leadMinutes = 100_000_000, nowMillis = noon)
        // Clamped to a week, so it still lands before the due time rather than a lifetime ago.
        assertEquals(due - ReminderSettings.MAX_LEAD_MINUTES * minute, absurd)
    }

    @Test
    fun `an alarm one second away is still ahead but one second past is not`() {
        assertTrue(ReminderPolicy.isStillAhead(noon + 1_000L, noon))
        assertFalse(ReminderPolicy.isStillAhead(noon - 1_000L, noon))
        assertFalse(ReminderPolicy.isStillAhead(null, noon))
    }

    // ---- snooze ----------------------------------------------------------------------

    @Test
    fun `snooze lands in the future by the chosen number of minutes`() {
        assertEquals(noon + 10 * minute, ReminderPolicy.snoozeUntil(noon, snoozeMinutes = 10))
        assertEquals(noon + 5 * minute, ReminderPolicy.snoozeUntil(noon, snoozeMinutes = 5))
    }

    @Test
    fun `snooze refuses to go backwards or further than a day`() {
        assertTrue(ReminderPolicy.snoozeUntil(noon, snoozeMinutes = 0) > noon)
        assertTrue(ReminderPolicy.snoozeUntil(noon, snoozeMinutes = -30) > noon)
        assertTrue(ReminderPolicy.snoozeUntil(noon, snoozeMinutes = 99_999) <= noon + 24 * 60 * minute)
    }

    // ---- copy ------------------------------------------------------------------------

    @Test
    fun `lead labels read like a sentence, not a number of minutes`() {
        assertEquals("At due time", ReminderPolicy.describeLead(0))
        assertEquals("1 minute before", ReminderPolicy.describeLead(1))
        assertEquals("10 minutes before", ReminderPolicy.describeLead(10))
        assertEquals("1 hour before", ReminderPolicy.describeLead(60))
        assertEquals("2 hours before", ReminderPolicy.describeLead(120))
        assertEquals("1 day before", ReminderPolicy.describeLead(1440))
        assertEquals("2 days before", ReminderPolicy.describeLead(2880))
        assertEquals("90 minutes before", ReminderPolicy.describeLead(90))
    }

    @Test
    fun `every offered lead has a label and survives a round trip`() {
        ReminderSettings.LEAD_OPTIONS.forEach { option ->
            assertTrue(
                "lead $option needs a label",
                ReminderPolicy.describeLead(option).isNotBlank()
            )
        }
        assertEquals(0, ReminderSettings.LEAD_OPTIONS.first())
        assertTrue(ReminderSettings.LEAD_OPTIONS.contains(ReminderSettings.DEFAULT.leadMinutes))
        assertTrue(ReminderSettings.SNOOZE_OPTIONS.contains(ReminderSettings.DEFAULT.snoozeMinutes))
    }

    // ---- styles and channels ---------------------------------------------------------

    @Test
    fun `each alert style maps to its own channel`() {
        val ids = ReminderAlertStyle.entries.map { it.channelId }
        assertEquals("two styles sharing a channel would silently share its sound", ids.size, ids.toSet().size)
    }

    @Test
    fun `the default style keeps the channel the app has always used`() {
        // Existing users may have tuned this channel's ringtone or muted it; changing the id
        // would quietly resurrect a notification they had turned off.
        assertEquals("task_reminders", ReminderAlertStyle.NOTIFICATION.channelId)
        assertEquals(ReminderAlertStyle.NOTIFICATION, ReminderSettings.DEFAULT.alertStyle)
    }

    @Test
    fun `an unknown or empty style falls back to notification, never to silent`() {
        assertEquals(ReminderAlertStyle.NOTIFICATION, ReminderAlertStyle.fromName(null))
        assertEquals(ReminderAlertStyle.NOTIFICATION, ReminderAlertStyle.fromName(""))
        assertEquals(ReminderAlertStyle.NOTIFICATION, ReminderAlertStyle.fromName("loud"))
        assertEquals(ReminderAlertStyle.ALARM, ReminderAlertStyle.fromName("alarm"))
        assertEquals(ReminderAlertStyle.SILENT, ReminderAlertStyle.fromName("SILENT"))
    }

    @Test
    fun `every style can describe itself for the settings row`() {
        ReminderAlertStyle.entries.forEach { style ->
            assertTrue(style.label.isNotBlank())
            assertTrue(style.summary.isNotBlank())
            assertTrue(ReminderPolicy.describeStyle(style).isNotBlank())
        }
    }

    // ---- master switch ---------------------------------------------------------------

    @Test
    fun `the master switch decides whether anything is scheduled`() {
        assertTrue(ReminderPolicy.shouldSchedule(ReminderSettings()))
        assertFalse(ReminderPolicy.shouldSchedule(ReminderSettings(enabled = false)))
    }

    @Test
    fun `defaults preserve the behaviour that shipped before the settings screen`() {
        val defaults = ReminderSettings.DEFAULT
        assertTrue(defaults.enabled)
        assertEquals(0, defaults.leadMinutes)
        assertEquals(ReminderAlertStyle.NOTIFICATION, defaults.alertStyle)
        assertEquals(10, defaults.snoozeMinutes)
    }
}
