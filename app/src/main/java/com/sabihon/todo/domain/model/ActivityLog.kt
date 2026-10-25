package com.sabihon.todo.domain.model

/**
 * Activity log entry for task history.
 */
data class ActivityLog(
    val id: String = "",
    val taskId: String = "",
    val taskTitle: String = "",
    val action: ActivityAction = ActivityAction.CREATED,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ActivityAction {
    CREATED, UPDATED, COMPLETED, REOPENED, DELETED,

    /**
     * A soft-deleted task was put back. Logged like the other verbs, so the feed keeps
     * the delete *and* the undo instead of silently rewriting history.
     */
    RESTORED,

    /**
     * The document itself is gone. Kept distinct from [DELETED] because the difference is
     * what makes a "Deleted" row restorable: only a soft delete leaves something behind
     * to restore.
     */
    PURGED
}
