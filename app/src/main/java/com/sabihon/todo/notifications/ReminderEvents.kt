package com.sabihon.todo.notifications

import com.sabihon.todo.domain.reminder.ReminderAlertStyle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A reminder that is due right now, delivered to the UI instead of the notification tray. */
data class DueReminder(
    val taskId: String,
    val title: String,
    val description: String,
    val style: ReminderAlertStyle
)

/**
 * In-process channel between the alarm and the UI.
 *
 * Deliberately not persisted and not replayed: a reminder is an event about *now*. Showing one
 * from ten minutes ago when the app is opened would be worse than never showing it, and the
 * notification is still in the tray for that case.
 */
@Singleton
class ReminderEvents @Inject constructor() {

    private val _due = MutableSharedFlow<DueReminder>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val due: SharedFlow<DueReminder> = _due.asSharedFlow()

    fun emitDue(taskId: String, title: String, description: String, style: ReminderAlertStyle) {
        _due.tryEmit(
            DueReminder(
                taskId = taskId,
                title = title,
                description = description,
                style = style
            )
        )
    }
}
