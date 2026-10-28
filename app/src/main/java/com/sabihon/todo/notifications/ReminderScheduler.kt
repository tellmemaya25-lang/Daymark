package com.sabihon.todo.notifications

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.sabihon.todo.domain.security.InputSecurityValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules/cancels reminders via WorkManager (Room/SQLite backed).
 * Uses lazy WorkManager to avoid IllegalStateException when Hilt worker factory not yet initialized.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager: WorkManager
        get() = try {
            WorkManager.getInstance(context)
        } catch (e: Exception) {
            WorkManager.getInstance(context)
        }

    fun scheduleReminder(
        taskId: String,
        taskTitle: String,
        taskDescription: String,
        reminderAtMillis: Long
    ) {
        try {
            if (!InputSecurityValidator.isValidFirestoreDocId(taskId)) return
            val now = System.currentTimeMillis()
            val delay = reminderAtMillis - now
            if (delay <= 0) return // Don't schedule past reminders

            val safeTitle = InputSecurityValidator.sanitizeUserText(
                taskTitle,
                maxLength = InputSecurityValidator.MAX_WORK_TITLE_LENGTH
            )
            val safeDescription = InputSecurityValidator.sanitizeUserText(
                taskDescription,
                maxLength = InputSecurityValidator.MAX_WORK_DESCRIPTION_LENGTH,
                allowMultiline = true
            )

            val inputData = Data.Builder()
                .putString("taskId", taskId)
                .putString("taskTitle", safeTitle)
                .putString("taskDescription", safeDescription)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .addTag(taskId)
                .build()

            workManager.enqueueUniqueWork(
                "reminder_$taskId",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        } catch (e: Exception) {
            android.util.Log.e("ReminderScheduler", "Failed to schedule reminder", e)
        }
    }

    fun cancelReminder(taskId: String) {
        try {
            if (!InputSecurityValidator.isValidFirestoreDocId(taskId)) return
            workManager.cancelUniqueWork("reminder_$taskId")
            workManager.cancelAllWorkByTag(taskId)
        } catch (e: Exception) {
            android.util.Log.e("ReminderScheduler", "Failed to cancel reminder", e)
        }
    }
}
