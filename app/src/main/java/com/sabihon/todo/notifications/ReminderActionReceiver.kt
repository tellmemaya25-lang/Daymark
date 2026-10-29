package com.sabihon.todo.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sabihon.todo.core.datastore.ReminderPreferences
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.security.InputSecurityValidator
import com.sabihon.todo.domain.usecase.ToggleCompleteUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The two notification buttons: **Mark done** and **Snooze**.
 *
 * Mark done goes through [ToggleCompleteUseCase], not a raw Firestore write. That use case is
 * what refuses to complete a task with open sub-tasks, advances a habit's streak and spawns the
 * next occurrence - so completing a repeating task from the notification used to look successful
 * and silently break the series.
 */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var toggleComplete: ToggleCompleteUseCase

    @Inject
    lateinit var scheduler: ReminderScheduler

    @Inject
    lateinit var preferences: ReminderPreferences

    @Inject
    lateinit var notificationHelper: NotificationHelper

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        if (!InputSecurityValidator.isValidFirestoreDocId(taskId)) return

        val action = intent.action ?: return
        if (action != ACTION_MARK_DONE && action != ACTION_SNOOZE) return

        notificationHelper.cancel(taskId)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_MARK_DONE -> {
                        // Scheduler is cancelled first: a completion that fails (open
                        // sub-tasks) must not leave a reminder armed that the user has
                        // already been told about.
                        scheduler.cancelReminder(taskId)
                        val result = toggleComplete(taskId, true)
                        if (result is Result.Error) {
                            // Nothing can be said from a collapsed notification, and the
                            // reminder is deliberately not re-armed: the user has already seen
                            // it. Leave the task open and log why.
                            android.util.Log.w(
                                TAG,
                                "Mark done refused for $taskId: ${result.message}"
                            )
                        }
                    }
                    ACTION_SNOOZE -> {
                        val minutes = preferences.current().snoozeMinutes
                        scheduler.snooze(
                            taskId = taskId,
                            taskTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                            taskDescription = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty(),
                            minutesFromNow = minutes
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Notification action $action failed for $taskId", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderAction"

        const val ACTION_MARK_DONE = "MARK_DONE"
        const val ACTION_SNOOZE = "SNOOZE"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "taskTitle"
        const val EXTRA_DESCRIPTION = "taskDescription"
    }
}
