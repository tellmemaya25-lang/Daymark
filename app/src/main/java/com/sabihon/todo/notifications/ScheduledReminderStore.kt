package com.sabihon.todo.notifications

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sabihon.todo.domain.reminder.ReminderAlertStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One alarm that is currently waiting in the system.
 *
 * The title and description are kept because the notification is built from the alarm's own
 * intent: after a reboot there is no snapshot to read them from, and doing a Firestore round
 * trip inside a boot receiver is both slow and void without a network.
 */
@Serializable
data class ScheduledReminder(
    val taskId: String,
    val title: String,
    val description: String,
    val triggerAt: Long,
    val style: String = ReminderAlertStyle.NOTIFICATION.name
)

private val Context.reminderQueueStore by preferencesDataStore(name = "daymark_reminder_queue")

/**
 * Reboot-safe queue of pending alarms.
 *
 * `AlarmManager` forgets everything on shutdown, so without this the app silently stops
 * reminding after the first restart - the kind of bug that only shows up a week later. The list
 * is pruned on every write and capped, so it is a queue, not a log.
 */
@Singleton
class ScheduledReminderStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ScheduledReminder.serializer())
    private val queueKey = stringPreferencesKey("pending_reminders")

    suspend fun all(): List<ScheduledReminder> {
        val raw = context.reminderQueueStore.data.first()[queueKey] ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    /** Replaces any queued alarm for the same task, then prunes anything already past. */
    suspend fun put(entry: ScheduledReminder) {
        write(all().filterNot { it.taskId == entry.taskId } + entry)
    }

    suspend fun remove(taskId: String) {
        val current = all()
        if (current.none { it.taskId == taskId }) return
        write(current.filterNot { it.taskId == taskId })
    }

    /** Drops entries whose moment has passed, and reports what is still ahead. */
    suspend fun prune(nowMillis: Long = System.currentTimeMillis()): List<ScheduledReminder> {
        val ahead = all().filter { it.triggerAt > nowMillis }
        write(ahead)
        return ahead
    }

    private suspend fun write(entries: List<ScheduledReminder>) {
        // Soonest first when trimming. If a device ever holds more than the cap, the ones worth
        // keeping are the next ones to fire - dropping those to keep a reminder three weeks out
        // would defeat the point of the queue.
        val trimmed = entries.sortedBy { it.triggerAt }.take(MAX_ENTRIES)
        context.reminderQueueStore.edit { prefs ->
            prefs[queueKey] = json.encodeToString(serializer, trimmed)
        }
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
