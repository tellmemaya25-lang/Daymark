package com.sabihon.todo.ui.admin

import com.sabihon.todo.domain.admin.AdminAiDailyStats
import com.sabihon.todo.domain.admin.AdminAuditEntry
import com.sabihon.todo.domain.admin.AdminPlatformMetrics
import com.sabihon.todo.domain.admin.AdminSystemConfig
import com.sabihon.todo.domain.admin.AdminUserDetail
import com.sabihon.todo.domain.admin.AdminUserSummary

enum class AdminTab(val label: String) {
    OVERVIEW("Overview"),
    USERS("Users"),
    AI_CONFIG("AI & Config"),
    AUDIT_SECURITY("Audit & Security")
}

enum class AdminUserFilter(val label: String) {
    ALL("All"),
    ADMINS("Admins"),
    ACTIVE("Active"),
    SUSPENDED("Suspended")
}

data class AdminDashboardUiState(
    val isCheckingAccess: Boolean = true,
    val isAuthorizedAdmin: Boolean = false,
    val currentAdminUid: String = "",
    val currentAdminEmail: String = "",
    val selectedTab: AdminTab = AdminTab.OVERVIEW,
    val isLoadingMetrics: Boolean = true,
    val isRefreshing: Boolean = false,
    val metrics: AdminPlatformMetrics = AdminPlatformMetrics(),
    val todayAiStats: AdminAiDailyStats = AdminAiDailyStats(),
    val users: List<AdminUserSummary> = emptyList(),
    val userSearchQuery: String = "",
    val userFilter: AdminUserFilter = AdminUserFilter.ALL,
    val selectedUserDetail: AdminUserDetail? = null,
    val isLoadingUserDetail: Boolean = false,
    val systemConfig: AdminSystemConfig = AdminSystemConfig(),
    val configDraft: AdminSystemConfig = AdminSystemConfig(),
    val hasUnsavedConfigChanges: Boolean = false,
    val isSavingConfig: Boolean = false,
    val auditLogs: List<AdminAuditEntry> = emptyList(),
    val showPasswordSheet: Boolean = false,
    val isUpdatingPassword: Boolean = false,
    val snackbarMessage: String? = null
)
