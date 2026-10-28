package com.sabihon.todo.domain.admin

import com.sabihon.todo.domain.model.Priority

enum class UserRole {
    USER,
    ADMIN;

    companion object {
        fun fromString(raw: String?): UserRole =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: USER
    }
}

enum class AdminSessionMode {
    FIREBASE_NATIVE,
    HYBRID_OWNER_SESSION
}

data class AdminUserSummary(
    val uid: String,
    val displayName: String,
    val email: String,
    val photoUrl: String? = null,
    val role: UserRole = UserRole.USER,
    val isDisabled: Boolean = false,
    val themePref: String = "SYSTEM",
    val createdAtMillis: Long? = null,
    val lastLoginAtMillis: Long? = null
) {
    val isPrimaryOwner: Boolean
        get() = AdminAccessPolicy.isPrimaryAdminEmail(email)
}

data class AdminTaskRecord(
    val taskId: String,
    val ownerUid: String,
    val ownerName: String,
    val ownerEmail: String,
    val title: String,
    val description: String = "",
    val priority: Priority = Priority.MEDIUM,
    val categoryId: String? = null,
    val categoryName: String = "",
    val isCompleted: Boolean = false,
    val dueAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val createdAtMillis: Long = 0L,
    val isRecurring: Boolean = false,
    val streak: Int = 0,
    val subTasksDone: Int = 0,
    val subTasksTotal: Int = 0
)

data class AdminUserDetail(
    val user: AdminUserSummary,
    val openTaskCount: Int = 0,
    val completedTaskCount: Int = 0,
    val categoryCount: Int = 0,
    val activeStreakMax: Int = 0,
    val recentTasks: List<AdminTaskRecord> = emptyList()
)

data class DailyTaskBucket(
    val label: String,
    val createdCount: Int,
    val completedCount: Int
)

data class AdminCategoryStat(
    val name: String,
    val taskCount: Int
)

data class AdminPlatformMetrics(
    val totalUsers: Int = 0,
    val activeUsersLast7Days: Int = 0,
    val activeAdmins: Int = 0,
    val suspendedUsers: Int = 0,
    val newUsersLast7Days: Int = 0,
    val totalTasks: Int = 0,
    val openTasks: Int = 0,
    val completedTasks: Int = 0,
    val overdueOpenTasks: Int = 0,
    val totalCategories: Int = 0,
    val activeHabitTasks: Int = 0,
    val maxHabitStreak: Int = 0,
    val priorityBreakdown: Map<Priority, Int> = emptyMap(),
    val themeBreakdown: Map<String, Int> = emptyMap(),
    val topCategories: List<AdminCategoryStat> = emptyList(),
    val sevenDayVelocity: List<DailyTaskBucket> = emptyList(),
    val sevenDayAiTrend: List<AdminAiDailyStats> = emptyList(),
    val permissionLimited: Boolean = false,
    val diagnosticHint: String? = null
) {
    val completionRatePercent: Int
        get() = if (totalTasks <= 0) 0 else ((completedTasks * 100.0) / totalTasks).toInt()

    val avgTasksPerUser: String
        get() = if (totalUsers <= 0) "0.0" else String.format(java.util.Locale.US, "%.1f", totalTasks.toDouble() / totalUsers)
}

data class AdminAiDailyStats(
    val dateKey: String = "",
    val totalCalls: Long = 0L,
    val cloudSuccessCount: Long = 0L,
    val localFallbackCount: Long = 0L,
    val errorCount: Long = 0L,
    val totalLatencyMs: Long = 0L,
    val acceptedOutcomes: Long = 0L,
    val dismissedOutcomes: Long = 0L
) {
    val averageLatencyMs: Long
        get() = if (totalCalls <= 0L) 0L else totalLatencyMs / totalCalls

    val cloudHitRatePercent: Int
        get() = if (totalCalls <= 0L) 0 else ((cloudSuccessCount * 100.0) / totalCalls).toInt()

    val acceptanceRatePercent: Int
        get() {
            val totalOutcomes = acceptedOutcomes + dismissedOutcomes
            return if (totalOutcomes <= 0L) 0 else ((acceptedOutcomes * 100.0) / totalOutcomes).toInt()
        }
}

data class AdminSystemConfig(
    val cloudAiGlobalEnabled: Boolean = true,
    val prioritizeEnabled: Boolean = true,
    val suggestionsEnabled: Boolean = true,
    val dailyAiQuotaPerUser: Int = 50,
    val maintenanceMode: Boolean = false,
    val announcementBanner: String = "",
    val updatedByEmail: String = "",
    val updatedAtMillis: Long? = null
)

data class AdminAuditEntry(
    val id: String = "",
    val actorUid: String = "",
    val actorEmail: String = "",
    val action: String = "",
    val targetId: String = "",
    val summary: String = "",
    val createdAtMillis: Long = 0L
)

enum class AdminExportType(val title: String, val subtitle: String, val extension: String, val mimeType: String) {
    USERS_CSV(
        title = "Users Directory (CSV)",
        subtitle = "UID, name, email, role, status, theme, created & last login timestamps",
        extension = "csv",
        mimeType = "text/csv"
    ),
    TASKS_CSV(
        title = "Global Tasks Ledger (CSV)",
        subtitle = "All tasks across users with priority, completion, owner, project & streaks",
        extension = "csv",
        mimeType = "text/csv"
    ),
    FULL_SNAPSHOT_JSON(
        title = "Full Admin Snapshot (JSON)",
        subtitle = "Metrics, users, AI telemetry, runtime config, and latest audit logs",
        extension = "json",
        mimeType = "application/json"
    )
}

data class AdminExportPayload(
    val fileName: String,
    val mimeType: String,
    val content: String,
    val rowCount: Int
)
