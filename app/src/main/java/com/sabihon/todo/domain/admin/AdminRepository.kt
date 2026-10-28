package com.sabihon.todo.domain.admin

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Priority
import kotlinx.coroutines.flow.Flow

interface AdminRepository {
    suspend fun isCurrentUserAdmin(): Boolean
    suspend fun getSessionMode(): AdminSessionMode
    fun observeUsers(): Flow<Result<List<AdminUserSummary>>>
    fun observeSystemConfig(): Flow<AdminSystemConfig>
    fun observeAuditLogs(limit: Int = 80): Flow<List<AdminAuditEntry>>
    fun observeTodayAiStats(): Flow<AdminAiDailyStats>
    suspend fun loadPlatformMetrics(): Result<AdminPlatformMetrics>
    suspend fun loadAllPlatformTasks(): Result<List<AdminTaskRecord>>
    suspend fun loadUserDetail(user: AdminUserSummary): Result<AdminUserDetail>

    // User CRUD & Governance
    suspend fun updateUserDisplayName(target: AdminUserSummary, newDisplayName: String): Result<Unit>
    suspend fun sendUserPasswordReset(email: String): Result<Unit>
    suspend fun setUserAdminRole(target: AdminUserSummary, makeAdmin: Boolean): Result<Unit>
    suspend fun setUserSuspended(target: AdminUserSummary, suspended: Boolean): Result<Unit>
    suspend fun deleteUserWorkspace(target: AdminUserSummary): Result<Unit>

    // Global Task CRUD
    suspend fun togglePlatformTaskComplete(task: AdminTaskRecord): Result<Unit>
    suspend fun updatePlatformTask(
        task: AdminTaskRecord,
        newTitle: String,
        newDescription: String,
        newPriority: Priority
    ): Result<Unit>
    suspend fun deletePlatformTask(task: AdminTaskRecord): Result<Unit>

    // Runtime Ops, Export & Security
    suspend fun updateSystemConfig(config: AdminSystemConfig): Result<Unit>
    suspend fun generateExport(type: AdminExportType): Result<AdminExportPayload>
    suspend fun linkGoogleAdminToken(idToken: String): Result<Unit>
    suspend fun changeAdminPassword(currentPassword: String, newPassword: String): Result<Unit>
    suspend fun recordAuditEvent(action: String, targetId: String, summary: String)
}
