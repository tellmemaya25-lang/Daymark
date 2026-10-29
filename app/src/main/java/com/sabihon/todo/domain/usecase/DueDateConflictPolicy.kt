package com.sabihon.todo.domain.usecase

import com.sabihon.todo.domain.model.Task

/** Pure rule for preventing two saved, non-deleted tasks from occupying the same due minute. */
object DueDateConflictPolicy {

    fun findConflict(
        tasks: Iterable<Task>,
        candidateDueAt: Long?,
        editingTaskId: String? = null
    ): Task? {
        candidateDueAt ?: return null
        val candidateMinute = Math.floorDiv(candidateDueAt, MILLIS_PER_MINUTE)
        return tasks.firstOrNull { task ->
            task.id != editingTaskId &&
                !task.isDeleted &&
                task.dueAt?.let { Math.floorDiv(it, MILLIS_PER_MINUTE) == candidateMinute } == true
        }
    }

    private const val MILLIS_PER_MINUTE = 60_000L
}
