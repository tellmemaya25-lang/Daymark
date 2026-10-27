package com.sabihon.todo.ui.home

import com.sabihon.todo.domain.ai.PrioritizationPlan
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.usecase.DashboardCounts
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Segmented tabs on Home – mirrors the design ref: Today / Upcoming / All tasks.
 */
enum class HomeFilter { TODAY, UPCOMING, ALL }

/**
 * What the ON YOUR LIST control sorts by.
 */
enum class HomeSort { SMART, PRIORITY, DUE_DATE }

/** Which way the sort runs. */
enum class HomeSortDirection { ASCENDING, DESCENDING }

val HomeSort.label: String
    get() = when (this) {
        HomeSort.SMART -> "Smart focus"
        HomeSort.PRIORITY -> "Priority first"
        HomeSort.DUE_DATE -> "Due date"
    }

val HomeSortDirection.label: String
    get() = when (this) {
        HomeSortDirection.ASCENDING -> "Ascending"
        HomeSortDirection.DESCENDING -> "Descending"
    }

/** Short enough to live in the header row without pushing the count off screen. */
val HomeSort.shortLabel: String
    get() = when (this) {
        HomeSort.SMART -> "Smart"
        HomeSort.PRIORITY -> "Priority"
        HomeSort.DUE_DATE -> "Due date"
    }

val HomeSortDirection.arrow: String
    get() = when (this) {
        HomeSortDirection.ASCENDING -> "\u2191"
        HomeSortDirection.DESCENDING -> "\u2193"
    }

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
    val isRefreshing: Boolean = false,
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
    val selectedSort: HomeSort = HomeSort.DUE_DATE,
    val sortDirection: HomeSortDirection = HomeSortDirection.ASCENDING,
    val categoryNames: Map<String, String> = emptyMap(),
    val momentum: Momentum = Momentum(),
    val errorMessage: String? = null,
    val showUndo: Boolean = false,
    val lastDeletedTask: Task? = null,
    /**
     * One-shot line confirming where a completed habit rescheduled to, e.g.
     * "Next: Tue, Oct 27". Null when nothing is pending.
     */
    val habitNextLabel: String? = null,

    // ---- Smart AI prioritization ----
    val showAiConsentSheet: Boolean = false,
    val showPrioritizeSheet: Boolean = false,
    val isPrioritizing: Boolean = false,
    val prioritizationPlan: PrioritizationPlan? = null,
    /** Checked proposals (`taskId -> suggestedPriority`) inside the review sheet. */
    val selectedPriorityChanges: Map<String, Priority> = emptyMap(),
    /** Validated focus rank map (`taskId -> 1..N`) used when `HomeSort.SMART` is active. */
    val aiFocusRanks: Map<String, Int> = emptyMap(),
    val showPriorityUndo: Boolean = false,
    val priorityUndoCount: Int = 0
)

sealed interface HomeAction {
    data class ToggleComplete(val taskId: String, val isCompleted: Boolean) : HomeAction
    data class DeleteTask(val task: Task) : HomeAction
    data object UndoDelete : HomeAction

    /** Clears a one-shot error once it has been shown. */
    data object ClearError : HomeAction

    /** Clears the habit reschedule confirmation once it has been shown. */
    data object ClearHabitLabel : HomeAction
    data class AddSubTask(val taskId: String, val subTaskTitle: String) : HomeAction
    data class ToggleSubTask(val taskId: String, val subTaskId: String, val isDone: Boolean) : HomeAction
    data class SetFilter(val filter: HomeFilter) : HomeAction
    data class SetSort(val sort: HomeSort) : HomeAction
    data class SetSortDirection(val direction: HomeSortDirection) : HomeAction
    data class SelectDate(val date: LocalDate) : HomeAction

    // ---- Smart AI prioritization ----
    data object RequestPrioritize : HomeAction
    data class DecideAiConsent(val enableCloud: Boolean) : HomeAction
    data object DismissAiConsent : HomeAction
    data object RefreshPrioritize : HomeAction
    data object DismissPrioritizeSheet : HomeAction
    data class TogglePriorityProposal(val taskId: String, val checked: Boolean) : HomeAction
    data object ApplyPriorityPlan : HomeAction
    data object UndoPriorityPlan : HomeAction
}
