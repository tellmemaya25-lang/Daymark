package com.sabihon.todo.notifications

import com.sabihon.todo.core.datastore.ReminderPreferences
import com.sabihon.todo.domain.reminder.ReminderAlertStyle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides *how* a due reminder reaches the user, at the moment it is due.
 *
 * Shared by the alarm receiver and the WorkManager fallback so the two can never disagree -
 * before this, the alarm path and the worker path each built their own notification.
 *
 * Rules, in order:
 *  1. **Master switch.** Turning reminders off must silence the ones already queued, not just
 *     stop new ones; that is what a user expects from an off switch.
 *  2. **Foreground.** An app the user is looking at gets an in-app popup instead of a banner.
 *  3. **Background.** The saved alert style picks the channel (and, for Alarm, the
 *     full-screen intent).
 */
@Singleton
class ReminderDelivery @Inject constructor(
    private val preferences: ReminderPreferences,
    private val notificationHelper: NotificationHelper,
    private val events: ReminderEvents,
    private val foreground: AppForegroundTracker,
    private val store: ScheduledReminderStore
) {

    suspend fun deliver(
        taskId: String,
        title: String,
        description: String
    ) {
        val settings = preferences.current()

        // Fired means done: drop it from the reboot queue whether or not it is shown, so a
        // restart cannot replay a reminder the user has already seen.
        store.remove(taskId)

        if (!settings.enabled) return

        // The saved setting wins over whatever was stamped when the alarm was queued:
        // switching to Silent must silence reminders that were already waiting.
        val style = settings.alertStyle

        // An Alarm is a system alert, not a silent in-app dialog. Sending it through the
        // alarm channel even while Daymark is open is what guarantees the configured tone and
        // vibration are honoured. Notification/ Silent keep their quieter in-app treatment.
        if (foreground.isForeground && style != ReminderAlertStyle.ALARM) {
            events.emitDue(taskId = taskId, title = title, description = description, style = style)
            return
        }

        val posted = notificationHelper.notifyReminder(
            taskId = taskId,
            taskTitle = title,
            taskDescription = description,
            style = style
        )
        // Keep the foreground popup as a last-resort notice if Android notification access is
        // off. It cannot ring or vibrate, but it is better than silently dropping the reminder.
        if (!posted && foreground.isForeground) {
            events.emitDue(taskId = taskId, title = title, description = description, style = style)
        }
    }
}
