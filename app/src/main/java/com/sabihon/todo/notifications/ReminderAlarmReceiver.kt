package com.sabihon.todo.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sabihon.todo.domain.security.InputSecurityValidator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The alarm itself: `AlarmManager` fires this, [ReminderDelivery] decides what the user sees.
 *
 * `goAsync()` because delivery reads DataStore and may post a notification; a plain
 * `onReceive` returning immediately can be killed before that finishes.
 */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var delivery: ReminderDelivery

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
            ?.takeIf { InputSecurityValidator.isValidFirestoreDocId(it) }
            ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                delivery.deliver(taskId = taskId, title = title, description = description)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Reminder delivery failed for $taskId", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderAlarm"

        const val ACTION_FIRE = "com.sabihon.todo.notifications.REMINDER_FIRE"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "taskTitle"
        const val EXTRA_DESCRIPTION = "taskDescription"
        const val EXTRA_STYLE = "alertStyle"
    }
}
