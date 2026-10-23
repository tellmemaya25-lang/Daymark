package com.sabihon.todo.ui.home

import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.usecase.DashboardCounts
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Segmented tabs on Home – mirrors the design ref: Today / Upcoming / All tasks.
 */
enum class HomeFilter { TODAY, UPCOMING, ALL }

/**
 * "Default order" control next to the ON YOUR LIST header.
 */
enum class HomeSort { DEFAULT, PRIORITY, DUE_DATE }

/**
 * One cell of the week strip above the task list.
 */
data class WeekDay(
    val date: LocalDate,
    val letter: String,
    val dayOfMonth: Int,
    val isToday: Boolean,
    val hasTasks: Boolean
)

/**
 * Numbers behind the daily momentum card.
 */
data class Momentum(
    val total: Int = 0,
    val done: Int = 0
) {
    val remaining: Int get() = (total - done).coerceAtLeast(0)
    val percent: Int get() = if (total == 0) 0 else ((done.toFloat() / total) * 100f).roundToInt()
}

/**
 * Immutable UiState for Home Dashboard.
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val greetingName: String = "User",
    val avatarInitials: String = "DM",
    val avatarUrl: String? = null,
    val selectedDate: LocalDate = LocalDate.now(),
    val weekDays: List<WeekDay> = emptyList(),
    val counts: DashboardCounts = DashboardCounts(),
    val allTasks: List<Task> = emptyList(),
    val todayTasks: List<Task> = emptyList(),
    val visibleTasks: List<Task> = emptyList(),
    val completedTasks: List<Task> = emptyList(),
    val selectedFilter: HomeFilter = HomeFilter.TODAY,
    val selectedSort: HomeSort = HomeSort.DEFAULT,
    val categoryNames: Map<String, String> = emptyMap(),
    val momentum: Momentum = Momentum(),
    val errorMessage: String? = null,
    val showUndo: Boolean = false,
    val lastDeletedTask: Task? = null
)

sealed interface HomeAction {
    data class ToggleComplete(val taskId: String, val isCompleted: Boolean) : HomeAction
    data class DeleteTask(val task: Task) : HomeAction
    data object UndoDelete : HomeAction
    data class AddSubTask(val taskId: String, val subTaskTitle: String) : HomeAction
    data class ToggleSubTask(val taskId: String, val subTaskId: String, val isDone: Boolean) : HomeAction
    data class SetFilter(val filter: HomeFilter) : HomeAction
    data class SetSort(val sort: HomeSort) : HomeAction
    data class SelectDate(val date: LocalDate) : HomeAction
}
