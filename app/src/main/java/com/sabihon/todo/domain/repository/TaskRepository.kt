package com.sabihon.todo.domain.repository

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.model.TaskFilter
import kotlinx.coroutines.flow.Flow

/**
 * Task repository contract.
 */
interface TaskRepository {
    fun observeTasks(filter: TaskFilter = TaskFilter()): Flow<Result<List<Task>>>
    fun observeTaskById(taskId: String): Flow<Result<Task>>
    suspend fun addTask(task: Task): Result<String>
    suspend fun updateTask(task: Task): Result<Unit>
    suspend fun deleteTask(taskId: String, softDelete: Boolean = true): Result<Unit>
    suspend fun restoreTask(taskId: String): Result<Unit>
    suspend fun toggleComplete(taskId: String, isCompleted: Boolean): Result<Unit>
    suspend fun searchTasks(query: String): Flow<Result<List<Task>>>

    /**
     * Writes streak bookkeeping without touching any other field and without
     * logging an activity entry – a habit ticking over must not fill the feed with
     * "Updated" rows.
     */
    suspend fun updateStreak(taskId: String, streak: Int, lastCompletedDay: Long): Result<Unit>

    /**
     * Creates the next occurrence of a repeating task.
     *
     * Separate from [addTask] because this must stay silent: the activity feed
     * records what the user did, and a daily habit spawning its next copy is not
     * something the user did.
     */
    suspend fun spawnOccurrence(task: Task): Result<String>
}
