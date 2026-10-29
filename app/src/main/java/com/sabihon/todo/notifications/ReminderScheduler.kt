package com.sabihon.todo.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.sabihon.todo.core.datastore.ReminderPreferences
import com.sabihon.todo.domain.reminder.ReminderAlertStyle
import com.sabihon.todo.domain.reminder.ReminderPolicy
import com.sabihon.todo.domain.security.InputSecurityValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts a reminder in the system's hands.
 *
 * `AlarmManager`, not WorkManager. WorkManager's `setInitialDelay` is a *minimum*: Doze and
 * battery saver are allowed to defer that work by 15+ minutes, which is how a 09:00 reminder
 * arrives after the meeting. An alarm is the only API that promises a specific moment.
 *
 * The ladder, in order:
 *  1. `setExactAndAllowWhileIdle` when the app may schedule exact alarms;
 *  2. `setAndAllowWhileIdle` when it may not (Android 12+ gates exact alarms behind a
 *     user-granted permission, denied by default on Android 14+ for non-alarm-clock apps).
 *     Inexact but still Doze-piercing: minutes late, never hours;
 *  3. WorkManager only if AlarmManager refuses outright, so a reminder is late rather than lost.
 *
 * Every scheduled alarm is mirrored into [ScheduledReminderStore], because `AlarmManager`
 * forgets everything at shutdown.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ReminderPreferences,
    private val store: ScheduledReminderStore
) {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * Schedules (or reschedules) the reminder for one task.
     *
     * Suspending because it consults the saved settings: the master switch and the alert style
     * live in DataStore, and a reminder scheduled under yesterday's settings is worse than none.
     * Callers are already on a background dispatcher.
     *
     * @param reminderAtMillis the absolute time the user wants to be told, already resolved
     *   from the due date and lead time (see [ReminderPolicy.resolveTriggerAt]).
     */
    suspend fun scheduleReminder(
        taskId: String,
        taskTitle: String,
        taskDescription: String,
        reminderAtMillis: Long
    ) {
        if (!InputSecurityValidator.isValidFirestoreDocId(taskId)) return

        // Whatever was queued for this task is replaced, never duplicated: editing a due date
        // must move the reminder, not add a second one.
        cancelAlarm(taskId)
        store.remove(taskId)

        val settings = preferences.current()
        val now = System.currentTimeMillis()
        if (!ReminderPolicy.isStillAhead(reminderAtMillis, now)) return

        val entry = ScheduledReminder(
            taskId = taskId,
            title = InputSecurityValidator.sanitizeUserText(
                taskTitle,
                maxLength = InputSecurityValidator.MAX_WORK_TITLE_LENGTH
            ),
            description = InputSecurityValidator.sanitizeUserText(
                taskDescription,
                maxLength = InputSecurityValidator.MAX_WORK_DESCRIPTION_LENGTH,
                allowMultiline = true
            ),
            triggerAt = reminderAtMillis,
            style = settings.alertStyle.name
        )

        // Queued even while the master switch is off, so turning reminders back on can arm
        // what the user already set up. In that state nothing is armed: a wake-up that
        // delivery would immediately drop is pure battery cost.
        store.put(entry)
        if (!ReminderPolicy.shouldSchedule(settings)) return

        if (!armAlarm(entry)) {
            // AlarmManager refused (rare, but seen on heavily restricted OEM builds).
            // WorkManager is inexact, and a late reminder beats a missing one.
            armWorkManagerFallback(entry)
        }
    }

    /** Snoozes one task's reminder to [minutesFromNow] from now. */
    suspend fun snooze(
        taskId: String,
        taskTitle: String,
        taskDescription: String,
        minutesFromNow: Int
    ) {
        val at = ReminderPolicy.snoozeUntil(System.currentTimeMillis(), minutesFromNow)
        scheduleReminder(taskId, taskTitle, taskDescription, at)
    }

    suspend fun cancelReminder(taskId: String) {
        if (!InputSecurityValidator.isValidFirestoreDocId(taskId)) return
        cancelAlarm(taskId)
        store.remove(taskId)
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName(taskId)) }
    }

    /**
     * Re-arms everything still ahead. Called after boot, after an app update, and whenever the
     * user turns reminders back on.
     */
    suspend fun rescheduleAll() {
        // Nothing is armed while the master switch is off. The queue survives, so switching
        // reminders back on re-arms every reminder the user had already configured.
        if (!ReminderPolicy.shouldSchedule(preferences.current())) return
        val now = System.currentTimeMillis()
        store.prune(now).forEach { entry ->
            if (!armAlarm(entry)) armWorkManagerFallback(entry)
        }
    }

    /** True when the OS would let this app fire an alarm at an exact minute. */
    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager?.canScheduleExactAlarms() == true
    }

    // ---- internals -------------------------------------------------------------------

    private fun armAlarm(entry: ScheduledReminder): Boolean {
        val manager = alarmManager ?: return false
        val pending = alarmIntent(entry)
        return try {
            if (canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.triggerAt, pending)
            }
            true
        } catch (e: SecurityException) {
            // Exact alarms revoked between the check and the call.
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.triggerAt, pending)
            }.isSuccess
        } catch (e: Exception) {
            android.util.Log.e("ReminderScheduler", "Could not set alarm for ${entry.taskId}", e)
            false
        }
    }

    private fun cancelAlarm(taskId: String) {
        val manager = alarmManager ?: return
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_FIRE
        }
        // FLAG_NO_CREATE + null check: cancelling an alarm that was never set would otherwise
        // allocate a PendingIntent for every task the user edits.
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(taskId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        manager.cancel(pending)
        pending.cancel()
    }

    private fun alarmIntent(entry: ScheduledReminder): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_FIRE
            putExtra(ReminderAlarmReceiver.EXTRA_TASK_ID, entry.taskId)
            putExtra(ReminderAlarmReceiver.EXTRA_TITLE, entry.title)
            putExtra(ReminderAlarmReceiver.EXTRA_DESCRIPTION, entry.description)
            putExtra(ReminderAlarmReceiver.EXTRA_STYLE, entry.style)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(entry.taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Stable per task, so a task's alarm replaces itself and never collides with another. */
    private fun requestCode(taskId: String): Int = taskId.hashCode()

    private fun armWorkManagerFallback(entry: ScheduledReminder) {
        runCatching {
            val delay = (entry.triggerAt - System.currentTimeMillis()).coerceAtLeast(0L)
            val data = Data.Builder()
                .putString(ReminderAlarmReceiver.EXTRA_TASK_ID, entry.taskId)
                .putString(ReminderAlarmReceiver.EXTRA_TITLE, entry.title)
                .putString(ReminderAlarmReceiver.EXTRA_DESCRIPTION, entry.description)
                .putString(
                    ReminderAlarmReceiver.EXTRA_STYLE,
                    entry.style.ifBlank { ReminderAlertStyle.NOTIFICATION.name }
                )
                .build()
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag(entry.taskId)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(entry.taskId), ExistingWorkPolicy.REPLACE, request)
        }.onFailure {
            android.util.Log.e("ReminderScheduler", "Fallback scheduling failed for ${entry.taskId}", it)
        }
    }

    private fun workName(taskId: String): String = "reminder_$taskId"
}
