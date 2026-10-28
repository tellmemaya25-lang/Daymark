package com.sabihon.todo.ui.admin

import com.sabihon.todo.domain.admin.AdminAiDailyStats
import com.sabihon.todo.domain.admin.AdminAuditEntry
import com.sabihon.todo.domain.admin.AdminExportPayload
import com.sabihon.todo.domain.admin.AdminPlatformMetrics
import com.sabihon.todo.domain.admin.AdminSessionMode
import com.sabihon.todo.domain.admin.AdminSystemConfig
import com.sabihon.todo.domain.admin.AdminTaskRecord
import com.sabihon.todo.domain.admin.AdminUserDetail
import com.sabihon.todo.domain.admin.AdminUserSummary

enum class AdminTab(val label: String) {
    OVERVIEW("Overview"),
    USERS("Users"),
    TASKS("Tasks"),
    AI_CONFIG("AI & Config"),
    AUDIT_SECURITY("Audit & Security")
}

enum class AdminUserFilter(val label: String) {
    ALL("All"),
    ADMINS("Admins"),
    ACTIVE("Active"),
    SUSPENDED("Suspended")
}

enum class AdminTaskFilter(val label: String) {
    ALL("All"),
    OPEN("Open"),
    COMPLETED("Done"),
    OVERDUE("Overdue"),
    URGENT_HIGH("Urgent / High"),
    HABITS("Habits")
}

enum class AdminAuditFilter(val label: String) {
    ALL("All"),
    AUTH("Auth"),
    USERS("Users"),
    TASKS("Tasks"),
    CONFIG("Config"),
    EXPORT("Export")
}

data class AdminDashboardUiState(
    val isCheckingAccess: Boolean = true,
    val isAuthorizedAdmin: Boolean = false,
    val sessionMode: AdminSessionMode = AdminSessionMode.FIREBASE_NATIVE,
    val currentAdminUid: String = "",
    val currentAdminEmail: String = "",
    val selectedTab: AdminTab = AdminTab.OVERVIEW,
    val isLoadingMetrics: Boolean = true,
    val isLoadingTasks: Boolean = false,
    val isRefreshing: Boolean = false,
    val metrics: AdminPlatformMetrics = AdminPlatformMetrics(),
    val todayAiStats: AdminAiDailyStats = AdminAiDailyStats(),

    // Users directory & governance
    val users: List<AdminUserSummary> = emptyList(),
    val userSearchQuery: String = "",
    val userFilter: AdminUserFilter = AdminUserFilter.ALL,
    val selectedUserDetail: AdminUserDetail? = null,
    val isLoadingUserDetail: Boolean = false,
    val confirmDeleteUser: AdminUserSummary? = null,

    // Global tasks explorer & CRUD
    val platformTasks: List<AdminTaskRecord> = emptyList(),
    val taskSearchQuery: String = "",
    val taskFilter: AdminTaskFilter = AdminTaskFilter.ALL,
    val editingTask: AdminTaskRecord? = null,
    val isSavingTask: Boolean = false,

    // Runtime AI & system config
    val systemConfig: AdminSystemConfig = AdminSystemConfig(),
    val configDraft: AdminSystemConfig = AdminSystemConfig(),
    val hasUnsavedConfigChanges: Boolean = false,
    val isSavingConfig: Boolean = false,
    val geminiApiKey: String = "",

    // Audit, Export & Security
    val auditLogs: List<AdminAuditEntry> = emptyList(),
    val auditFilter: AdminAuditFilter = AdminAuditFilter.ALL,
    val auditSearchQuery: String = "",
    val showExportSheet: Boolean = false,
    val isExporting: Boolean = false,
    val readyExportPayload: AdminExportPayload? = null,
    val showPasswordSheet: Boolean = false,
    val isUpdatingPassword: Boolean = false,
    val isLinkingGoogle: Boolean = false,
    val snackbarMessage: String? = null
)
