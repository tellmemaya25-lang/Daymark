package com.sabihon.todo.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Last-resort delivery when `AlarmManager` refused to take the alarm.
 *
 * WorkManager is inexact, so this is deliberately the fallback rather than the main path: a
 * reminder may be minutes late here, but it is not lost. It shares [ReminderDelivery] with the
 * alarm receiver, so the master switch, the foreground rule and the alert style behave
 * identically whichever path fired.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val delivery: ReminderDelivery
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(ReminderAlarmReceiver.EXTRA_TASK_ID) ?: return Result.failure()
        val title = inputData.getString(ReminderAlarmReceiver.EXTRA_TITLE).orEmpty()
        val description = inputData.getString(ReminderAlarmReceiver.EXTRA_DESCRIPTION).orEmpty()

        return try {
            delivery.deliver(taskId = taskId, title = title, description = description)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("ReminderWorker", "Delivery failed for $taskId", e)
            Result.failure()
        }
    }
}
