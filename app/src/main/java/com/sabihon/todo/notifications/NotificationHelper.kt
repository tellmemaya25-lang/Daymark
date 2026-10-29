package com.sabihon.todo.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sabihon.todo.MainActivity
import com.sabihon.todo.R
import com.sabihon.todo.domain.reminder.ReminderAlertStyle
import com.sabihon.todo.domain.security.InputSecurityValidator
import com.sabihon.todo.ui.reminder.ReminderAlarmActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts reminder notifications.
 *
 * One channel per [ReminderAlertStyle]. Android freezes a channel's sound, vibration and
 * importance at creation, so a style is only honest if it has its own channel; an app-side
 * "sound off" switch on a shared channel would be silently ignored by the OS.
 *
 * The style's channel id lives on the enum, in `domain/reminder/ReminderPolicy.kt`, so exactly
 * one place decides which channel a reminder lands in.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /**
         * Kept for callers that predate the style split. Equal to
         * [ReminderAlertStyle.NOTIFICATION]'s channel, so old notifications keep working.
         */
        const val CHANNEL_ID = "task_reminders"
        const val CHANNEL_NAME = "Task Reminders"
        const val REQUEST_CODE_MARK_DONE = 1001
        const val REQUEST_CODE_SNOOZE = 1002
    }

    private val manager: NotificationManager?
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /**
     * Creates every channel. Idempotent and cheap; called from the Application and again before
     * each post so a channel deleted in system settings is recreated rather than throwing.
     */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = manager ?: return

        val alarms = NotificationChannelSpec(
            id = ReminderAlertStyle.ALARM.channelId,
            name = "Task alarms",
            description = "Full-screen alarms for tasks you asked to be reminded about",
            importance = NotificationManager.IMPORTANCE_HIGH,
            vibrate = true
        )
        val reminders = NotificationChannelSpec(
            id = ReminderAlertStyle.NOTIFICATION.channelId,
            name = CHANNEL_NAME,
            description = context.getString(R.string.notification_channel_desc),
            importance = NotificationManager.IMPORTANCE_HIGH,
            vibrate = true
        )
        // LOW, not MIN: the reminder should still appear in the tray and on the lock screen,
        // it simply must not make a sound or buzz.
        val silent = NotificationChannelSpec(
            id = ReminderAlertStyle.SILENT.channelId,
            name = "Task reminders (silent)",
            description = "Reminders that only appear in the tray",
            importance = NotificationManager.IMPORTANCE_LOW,
            vibrate = false
        )

        listOf(alarms, reminders, silent).forEach { spec ->
            val channel = android.app.NotificationChannel(
                spec.id,
                spec.name,
                spec.importance
            ).apply {
                description = spec.description
                enableVibration(spec.vibrate)
                if (!spec.vibrate) {
                    setSound(null, null)
                }
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /** True when the app may post notifications at all. */
    fun canPostNotifications(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Posts one reminder, or silently does nothing when notifications are denied.
     *
     * @return true when the notification was posted.
     */
    fun notifyReminder(
        taskId: String,
        taskTitle: String,
        taskDescription: String,
        style: ReminderAlertStyle
    ): Boolean {
        if (!canPostNotifications()) return false
        ensureChannels()
        return try {
            NotificationManagerCompat.from(context)
                .notify(notificationId(taskId), buildReminder(taskId, taskTitle, taskDescription, style))
            true
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
            false
        }
    }

    fun cancel(taskId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId(taskId)) }
    }

    fun buildReminder(
        taskId: String,
        taskTitle: String,
        taskDescription: String,
        style: ReminderAlertStyle
    ): android.app.Notification {
        val safeTaskId = if (InputSecurityValidator.isValidFirestoreDocId(taskId)) taskId else ""
        val safeTitle = InputSecurityValidator.sanitizeUserText(
            taskTitle,
            maxLength = InputSecurityValidator.MAX_WORK_TITLE_LENGTH
        ).ifBlank { "Task reminder" }
        val safeDescription = InputSecurityValidator.sanitizeUserText(
            taskDescription,
            maxLength = InputSecurityValidator.MAX_WORK_DESCRIPTION_LENGTH
        ).ifBlank { "Task reminder" }

        val contentPendingIntent = PendingIntent.getActivity(
            context,
            safeTaskId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                if (safeTaskId.isNotEmpty()) putExtra("taskId", safeTaskId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Redacted public version: task titles are personal ("Dentist", "Pay rent") and the
        // lock screen is the most exposed surface the app has.
        val publicNotification = NotificationCompat.Builder(context, style.channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("You have a task reminder")
            .build()

        val builder = NotificationCompat.Builder(context, style.channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(safeTitle)
            .setContentText(safeDescription)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)

        when (style) {
            ReminderAlertStyle.ALARM -> {
                builder.setPriority(NotificationCompat.PRIORITY_MAX)
                builder.setCategory(NotificationCompat.CATEGORY_ALARM)
                // The alarm screen, not just the app: an alarm that opens on the home feed has
                // failed to be an alarm. Full-screen intents are also how a locked device wakes.
                val alarmPendingIntent = PendingIntent.getActivity(
                    context,
                    safeTaskId.hashCode() + 7,
                    Intent(context, ReminderAlarmActivity::class.java).apply {
                        putExtra(ReminderAlarmActivity.EXTRA_TASK_ID, safeTaskId)
                        putExtra(ReminderAlarmActivity.EXTRA_TITLE, safeTitle)
                        putExtra(ReminderAlarmActivity.EXTRA_DESCRIPTION, safeDescription)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setFullScreenIntent(alarmPendingIntent, true)
            }
            ReminderAlertStyle.NOTIFICATION -> {
                builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                builder.setCategory(NotificationCompat.CATEGORY_REMINDER)
            }
            ReminderAlertStyle.SILENT -> {
                builder.setPriority(NotificationCompat.PRIORITY_LOW)
                builder.setCategory(NotificationCompat.CATEGORY_REMINDER)
                builder.setSilent(true)
            }
        }

        if (safeTaskId.isNotEmpty()) {
            // The title and description ride along on both actions: by the time a button is
            // tapped, the reminder has already left the reboot queue, so "Snooze" would
            // otherwise reschedule a notification with no text.
            builder.addAction(
                android.R.drawable.checkbox_on_background,
                context.getString(R.string.mark_done),
                broadcast(safeTaskId, safeTitle, safeDescription, ReminderActionReceiver.ACTION_MARK_DONE, REQUEST_CODE_MARK_DONE)
            )
            builder.addAction(
                android.R.drawable.ic_popup_reminder,
                context.getString(R.string.snooze),
                broadcast(safeTaskId, safeTitle, safeDescription, ReminderActionReceiver.ACTION_SNOOZE, REQUEST_CODE_SNOOZE)
            )
        }

        return builder.build()
    }

    /** Notification ids stay tied to the task, so one task can never stack up alerts. */
    fun notificationId(taskId: String): Int = taskId.hashCode()

    private fun broadcast(
        taskId: String,
        title: String,
        description: String,
        action: String,
        baseCode: Int
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            baseCode + taskId.hashCode(),
            Intent(context, ReminderActionReceiver::class.java).apply {
                this.action = action
                putExtra(ReminderActionReceiver.EXTRA_TASK_ID, taskId)
                putExtra(ReminderActionReceiver.EXTRA_TITLE, title)
                putExtra(ReminderActionReceiver.EXTRA_DESCRIPTION, description)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private data class NotificationChannelSpec(
        val id: String,
        val name: String,
        val description: String,
        val importance: Int,
        val vibrate: Boolean
    )
}
