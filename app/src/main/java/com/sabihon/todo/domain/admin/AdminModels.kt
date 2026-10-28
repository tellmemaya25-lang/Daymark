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

data class AdminUserDetail(
    val user: AdminUserSummary,
    val openTaskCount: Int = 0,
    val completedTaskCount: Int = 0,
    val categoryCount: Int = 0,
    val activeStreakMax: Int = 0
)

data class DailyTaskBucket(
    val label: String,
    val createdCount: Int,
    val completedCount: Int
)

data class AdminPlatformMetrics(
    val totalUsers: Int = 0,
    val activeAdmins: Int = 0,
    val suspendedUsers: Int = 0,
    val newUsersLast7Days: Int = 0,
    val totalTasks: Int = 0,
    val openTasks: Int = 0,
    val completedTasks: Int = 0,
    val overdueOpenTasks: Int = 0,
    val totalCategories: Int = 0,
    val activeHabitTasks: Int = 0,
    val priorityBreakdown: Map<Priority, Int> = emptyMap(),
    val sevenDayVelocity: List<DailyTaskBucket> = emptyList(),
    val permissionLimited: Boolean = false,
    val diagnosticHint: String? = null
) {
    val completionRatePercent: Int
        get() = if (totalTasks <= 0) 0 else ((completedTasks * 100.0) / totalTasks).toInt()
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
