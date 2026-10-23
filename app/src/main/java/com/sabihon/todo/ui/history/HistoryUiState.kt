package com.sabihon.todo.ui.history

import com.sabihon.todo.domain.model.ActivityAction
import com.sabihon.todo.domain.model.ActivityLog
import com.sabihon.todo.domain.model.Task

/**
 * One line in the Activity feed.
 */
data class ActivityFeedItem(
    val id: String,
    val taskId: String,
    val taskTitle: String,
    val action: ActivityAction,
    val timestamp: Long
) {
    /** "Completed", "Created", "Reopened"… */
    val verb: String
        get() = when (action) {
            ActivityAction.CREATED -> "Created"
            ActivityAction.UPDATED -> "Updated"
            ActivityAction.COMPLETED -> "Completed"
            ActivityAction.REOPENED -> "Reopened"
            ActivityAction.DELETED -> "Deleted"
        }

    /** Green dot only for wins; everything else is muted. */
    val isWin: Boolean
        get() = action == ActivityAction.COMPLETED

    /** Short status word for the chip beside the row. */
    val statusLabel: String
        get() = when (action) {
            ActivityAction.CREATED -> "Created"
            ActivityAction.UPDATED -> "Updated"
            ActivityAction.COMPLETED -> "Completed"
            ActivityAction.REOPENED -> "Reopened"
            ActivityAction.DELETED -> "Deleted"
        }

    /** Deleted rows have no task to open any more. */
    val isDeleted: Boolean
        get() = action == ActivityAction.DELETED
}

data class HistoryUiState(
    val isLoading: Boolean = true,
    val userInitials: String = "DM",
    val userPhotoUrl: String? = null,
    val completedTasks: List<Task> = emptyList(),
    val grouped: Map<String, List<Task>> = emptyMap(), // Today, Yesterday, Earlier
    val activityLogs: List<ActivityLog> = emptyList(),
    val feed: List<ActivityFeedItem> = emptyList(),
    val stats: HistoryStats = HistoryStats()
)

data class HistoryStats(
    val completedThisWeek: Int = 0,
    val completionRate: Float = 0f,
    val currentStreak: Int = 0,
    val dailyCounts: List<Int> = List(7) { 0 } // 7-day bar chart
)
