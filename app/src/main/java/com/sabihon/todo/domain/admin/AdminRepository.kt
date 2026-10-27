package com.sabihon.todo.domain.admin

import com.sabihon.todo.core.util.Result
import kotlinx.coroutines.flow.Flow

interface AdminRepository {
    suspend fun isCurrentUserAdmin(): Boolean
    fun observeUsers(): Flow<Result<List<AdminUserSummary>>>
    fun observeSystemConfig(): Flow<AdminSystemConfig>
    fun observeAuditLogs(limit: Int = 50): Flow<List<AdminAuditEntry>>
    fun observeTodayAiStats(): Flow<AdminAiDailyStats>
    suspend fun loadPlatformMetrics(): Result<AdminPlatformMetrics>
    suspend fun loadUserDetail(user: AdminUserSummary): Result<AdminUserDetail>
    suspend fun setUserAdminRole(target: AdminUserSummary, makeAdmin: Boolean): Result<Unit>
    suspend fun setUserSuspended(target: AdminUserSummary, suspended: Boolean): Result<Unit>
    suspend fun updateSystemConfig(config: AdminSystemConfig): Result<Unit>
    suspend fun changeAdminPassword(currentPassword: String, newPassword: String): Result<Unit>
    suspend fun recordAuditEvent(action: String, targetId: String, summary: String)
}
