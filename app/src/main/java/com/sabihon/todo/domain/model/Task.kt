package com.sabihon.todo.domain.model

/**
 * Core Task domain model – immutable.
 */
data class Task(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val categoryId: String? = null,
    val priority: Priority = Priority.MEDIUM,
    val dueAt: Long? = null, // epoch millis
    val reminderAt: Long? = null,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val subTasks: List<SubTask> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val searchKeywords: List<String> = emptyList(),

    // ---- repeating tasks (habits) ----

    /** How often this task comes back. [RepeatRule.NONE] for ordinary one-offs. */
    val repeat: RepeatRule = RepeatRule.NONE,

    /** Weekdays for [RepeatRule.WEEKLY], 1 = Monday … 7 = Sunday. Empty otherwise. */
    val repeatDays: List<Int> = emptyList(),

    /** Consecutive completed occurrences. 0 means no streak yet. */
    val streak: Int = 0,

    /** Epoch day (`LocalDate.toEpochDay()`) of the last completion, or null. */
    val lastCompletedDay: Long? = null,

    /**
     * Identifies every occurrence spawned from the same habit. Null on the original
     * task, which itself acts as the template.
     */
    val seriesId: String? = null
) {
    /**
     * True when there is nothing left to tick. A task with no sub-tasks is trivially
     * ready - the absence of a checklist is not an incomplete checklist.
     *
     * Deliberately one definition, in the model: `ToggleCompleteUseCase` refuses to
     * complete a task while this is false, and Home must not celebrate a completion
     * that the use case is about to reject. Two copies of this predicate is how the
     * celebration ended up firing on a tick that never happened.
     */
    val subTasksDone: Boolean get() = subTasks.all { it.isDone }
}
