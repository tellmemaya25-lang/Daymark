package com.sabihon.todo.domain.reminder

/**
 * How a due reminder announces itself.
 *
 * Each style owns a **notification channel id**, because Android fixes a channel's sound,
 * vibration and importance at creation time. An app-side "sound on/off" toggle cannot be
 * honoured on an existing channel, so a style is the honest unit of choice: picking Silent
 * means posting to a channel that never makes noise.
 *
 * [channelId] values live here rather than in the notifications layer so exactly one place
 * maps a style to a channel, and so that mapping can be unit tested on the JVM.
 */
enum class ReminderAlertStyle(
    val label: String,
    val channelId: String,
    val summary: String
) {
    /** Full-screen over the lock screen, with sound and vibration. */
    ALARM(
        label = "Alarm",
        // v2 intentionally creates a fresh channel: Android freezes sound/vibration from
        // channel creation, and the previous channel had no explicit alarm sound.
        channelId = "task_reminders_alarm_v2",
        summary = "Full-screen alarm with sound and vibration"
    ),

    /**
     * Heads-up banner. Reuses the channel id the app has shipped with since reminders
     * existed, so anyone who already tuned its ringtone or turned it off keeps that choice.
     */
    NOTIFICATION(
        label = "Notification",
        channelId = "task_reminders",
        summary = "Banner with sound and vibration"
    ),

    /** Tray only: no sound, no vibration, no heads-up. */
    SILENT(
        label = "Silent",
        channelId = "task_reminders_silent",
        summary = "No sound, no vibration - just the tray"
    );

    companion object {
        /** Unknown or absent values fall back to the shipped default, never to SILENT. */
        fun fromName(value: String?): ReminderAlertStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NOTIFICATION
    }
}

/**
 * The user's reminder preferences.
 *
 * Defaults are the behaviour that shipped before this screen existed - reminders on, firing at
 * the due time, as a normal notification - so nobody's reminders change until they change them.
 */
data class ReminderSettings(
    val enabled: Boolean = true,
    val leadMinutes: Int = 0,
    val alertStyle: ReminderAlertStyle = ReminderAlertStyle.NOTIFICATION,
    val snoozeMinutes: Int = 10
) {
    companion object {
        /** Offered in Settings, smallest first. 1440 is "the day before". */
        val LEAD_OPTIONS: List<Int> = listOf(0, 10, 30, 60, 1440)

        val SNOOZE_OPTIONS: List<Int> = listOf(5, 10, 15, 30)

        /** A week. Longer than this is a calendar event, not a reminder. */
        const val MAX_LEAD_MINUTES = 7 * 24 * 60

        val DEFAULT = ReminderSettings()
    }
}

/**
 * When a reminder should actually fire, and how it should look when it does.
 *
 * Pure on purpose: every rule here is a decision the app makes silently, so each one is a unit
 * test rather than something discovered when a reminder does not arrive.
 */
object ReminderPolicy {

    /**
     * Never fire sooner than this from "now". A trigger that has just been scheduled for the
     * current second is a race between the alarm and the write that created it.
     */
    const val MIN_DELAY_MS = 30_000L

    /**
     * Resolves the absolute time a reminder should fire.
     *
     * @param dueAt when the task is due, or null for a task with no date - which cannot be
     *   reminded about at all, because there is no moment to be reminded before.
     * @param leadMinutes how long before the due time to fire, from [ReminderSettings].
     * @param nowMillis the current time, passed in so the rule is testable.
     * @return the trigger time in epoch millis, or null when no reminder should be scheduled.
     *
     * Two edge cases are deliberate:
     *  - **due time already passed** returns null. Silencing a reminder for a moment that has
     *    gone is the only honest option; delivering it late is a notification about history.
     *  - **the lead pushes the trigger into the past** (a one-hour lead on a task due in 20
     *    minutes) is *clamped to now + [MIN_DELAY_MS]* rather than dropped. The user asked to
     *    be warned before this task; "too late to warn you early" still means warn now.
     */
    fun resolveTriggerAt(
        dueAt: Long?,
        leadMinutes: Int,
        nowMillis: Long
    ): Long? {
        if (dueAt == null) return null
        if (dueAt <= nowMillis) return null

        val lead = leadMinutes.coerceIn(0, ReminderSettings.MAX_LEAD_MINUTES)
        val candidate = dueAt - lead * 60_000L
        return if (candidate <= nowMillis) nowMillis + MIN_DELAY_MS else candidate
    }

    /** Snooze always lands in the future, and never further than a day out. */
    fun snoozeUntil(nowMillis: Long, snoozeMinutes: Int): Long =
        nowMillis + snoozeMinutes.coerceIn(1, 24 * 60) * 60_000L

    /**
     * True when [triggerAt] is still worth scheduling.
     *
     * Slightly more forgiving than [resolveTriggerAt]: an alarm one second in the future is
     * still a valid alarm when it is being replayed after a reboot.
     */
    fun isStillAhead(triggerAt: Long?, nowMillis: Long): Boolean =
        triggerAt != null && triggerAt > nowMillis

    /** "At due time", "10 min before", "1 hour before", "1 day before". */
    fun describeLead(minutes: Int): String {
        val safe = minutes.coerceIn(0, ReminderSettings.MAX_LEAD_MINUTES)
        return when {
            safe == 0 -> "At due time"
            safe == 1 -> "1 minute before"
            safe < 60 -> "$safe minutes before"
            safe == 60 -> "1 hour before"
            safe % 1440 == 0 -> {
                val days = safe / 1440
                if (days == 1) "1 day before" else "$days days before"
            }
            safe % 60 == 0 -> "${safe / 60} hours before"
            else -> "$safe minutes before"
        }
    }

    /** The one-line summary Settings shows under the alert-style row. */
    fun describeStyle(style: ReminderAlertStyle): String = style.summary

    /** True when a reminder should be scheduled at all under these settings. */
    fun shouldSchedule(settings: ReminderSettings): Boolean = settings.enabled
}
