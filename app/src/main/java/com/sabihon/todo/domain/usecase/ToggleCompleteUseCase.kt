package com.sabihon.todo.domain.usecase

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.repository.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import javax.inject.Inject

/**
 * What completing a task did to its series.
 *
 * Carried back to the caller so the UI can say where the next occurrence landed;
 * null [nextDueAt] means the task does not repeat (or was reopened).
 */
data class HabitAdvance(
    val nextDueAt: Long? = null,
    val streak: Int = 0
)

/**
 * Toggle task completion.
 *
 * Guards:
 *  - a task with sub-tasks cannot be completed until they are all done
 *  - completing a repeating task advances its series (streak + next occurrence)
 */
class ToggleCompleteUseCase @Inject constructor(
    private val repository: TaskRepository
) {
    suspend operator fun invoke(taskId: String, isCompleted: Boolean): Result<HabitAdvance> {
        var task: Task? = null

        if (isCompleted) {
            task = readTask(taskId)
            if (task != null &&
                task.subTasks.isNotEmpty() &&
                task.subTasks.any { !it.isDone }
            ) {
                return Result.Error(
                    Exception(
                        "Complete all ${task.subTasks.size} sub-tasks first " +
                            "(${task.subTasks.count { it.isDone }}/${task.subTasks.size} done)"
                    )
                )
            }
        }

        val result = repository.toggleComplete(taskId, isCompleted)
        if (result is Result.Error) return result

        val advance = if (isCompleted && task != null) advanceSeries(task) else HabitAdvance()
        return Result.Success(advance)
    }

    /**
     * Waits for the first real emission.
     *
     * `observeTaskById` opens with `Result.Loading`, so taking `.first()` used to
     * always yield Loading and the sub-task guard never actually ran. Anything that
     * fails or times out is treated as "no task": a lookup problem must not block
     * the user from ticking something off.
     */
    private suspend fun readTask(taskId: String): Task? {
        return try {
            withTimeoutOrNull(READ_TIMEOUT_MS) {
                repository.observeTaskById(taskId).first { it !is Result.Loading }
            }.let { emission -> (emission as? Result.Success)?.data }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Records the streak and schedules the next occurrence.
     *
     * Every step is best-effort: the task is already completed at this point and a
     * failure to schedule the next copy must not report the completion as failed.
     */
    private suspend fun advanceSeries(task: Task): HabitAdvance {
        if (!task.repeat.isRecurring) return HabitAdvance()
        try {
            val today = LocalDate.now()
            val lastCompleted = task.lastCompletedDay?.let { LocalDate.ofEpochDay(it) }
            val streak = RepeatScheduler.streakAfter(
                rule = task.repeat,
                repeatDays = task.repeatDays,
                previousStreak = task.streak,
                lastCompletedDay = lastCompleted,
                today = today
            )
            repository.updateStreak(task.id, streak, today.toEpochDay())

            val dueDay = RepeatScheduler.dayOf(task.dueAt)
            val nextDay = RepeatScheduler.nextOccurrenceAfterCompletion(
                rule = task.repeat,
                repeatDays = task.repeatDays,
                dueDay = dueDay,
                today = today
            ) ?: return HabitAdvance(streak = streak)

            val timeOfDay = RepeatScheduler.timeOfDayOf(task.dueAt)
            val nextDueAt = RepeatScheduler.dueAtMillis(nextDay, timeOfDay)
            // Keep the original gap between reminder and due time, so a habit that
            // warned 30 minutes early keeps warning 30 minutes early.
            val nextReminderAt = task.reminderAt
                ?.let { reminder -> task.dueAt?.let { due -> nextDueAt - (due - reminder) } }

            val seriesId = task.seriesId ?: task.id
            repository.spawnOccurrence(
                task.copy(
                    id = "${seriesId}_${nextDay.toEpochDay()}",
                    dueAt = nextDueAt,
                    reminderAt = nextReminderAt,
                    isCompleted = false,
                    completedAt = null,
                    subTasks = task.subTasks.map { it.copy(isDone = false) },
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    streak = streak,
                    lastCompletedDay = today.toEpochDay(),
                    seriesId = seriesId
                )
            )
            return HabitAdvance(nextDueAt = nextDueAt, streak = streak)
        } catch (_: Exception) {
            // A habit that fails to reschedule is still a completed task.
            return HabitAdvance()
        }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 3_000L
    }
}
