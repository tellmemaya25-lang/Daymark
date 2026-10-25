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
    val timestamp: Long,
    /**
     * True only for the *newest* event of a task that is currently deleted. Restoring
     * twice from an older "Deleted" row would write a second RESTORED entry, so the
     * decision is made once, in [HistoryViewModel.buildFeed], against the whole feed.
     */
    val restorable: Boolean = false,
    /**
     * The newest event of this task is a permanent delete, so there is no document left
     * to open.
     */
    val gone: Boolean = false
) {
    /** "Completed", "Created", "Reopened"… */
    val verb: String
        get() = when (action) {
            ActivityAction.CREATED -> "Created"
            ActivityAction.UPDATED -> "Updated"
            ActivityAction.COMPLETED -> "Completed"
            ActivityAction.REOPENED -> "Reopened"
            ActivityAction.DELETED -> "Deleted"
            ActivityAction.RESTORED -> "Restored"
            ActivityAction.PURGED -> "Deleted for good"
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
            ActivityAction.RESTORED -> "Restored"
            ActivityAction.PURGED -> "Deleted for good"
        }

    /** Deleted rows have no task to open any more. */
    val isDeleted: Boolean
        get() = action == ActivityAction.DELETED

    /**
     * A deleted row offers restore while it is the task's latest event: the document
     * still exists (delete is a soft delete), it is simply flagged `deleted = true`.
     */
    val canRestore: Boolean
        get() = restorable

    /** Reopening only makes sense for a completion. */
    val canReopen: Boolean
        get() = action == ActivityAction.COMPLETED
}

data class HistoryUiState(
    val isLoading: Boolean = true,
    val userInitials: String = "DM",
    val userPhotoUrl: String? = null,
    val completedTasks: List<Task> = emptyList(),
    val grouped: Map<String, List<Task>> = emptyMap(), // Today, Yesterday, Earlier
    val activityLogs: List<ActivityLog> = emptyList(),
    val feed: List<ActivityFeedItem> = emptyList(),
    val stats: HistoryStats = HistoryStats(),
    /** One-shot snackbar text for restore / reopen / delete-for-good. */
    val message: String? = null
)

data class HistoryStats(
    val completedThisWeek: Int = 0,
    val completionRate: Float = 0f,
    val currentStreak: Int = 0,
    val dailyCounts: List<Int> = List(7) { 0 } // 7-day bar chart
)
