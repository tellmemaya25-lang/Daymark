package com.sabihon.todo.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabihon.todo.BuildConfig
import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.AiQuotaGuard
import com.sabihon.todo.data.ai.AiSnapshotCache
import com.sabihon.todo.domain.admin.AdminAuditEntry
import com.sabihon.todo.domain.admin.AdminExportType
import com.sabihon.todo.domain.admin.AdminRepository
import com.sabihon.todo.domain.admin.AdminSystemConfig
import com.sabihon.todo.domain.admin.AdminTaskRecord
import com.sabihon.todo.domain.admin.AdminUserDetail
import com.sabihon.todo.domain.admin.AdminUserSummary
import com.sabihon.todo.domain.admin.UserRole
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class AdminDashboardViewModel @Inject constructor(
    private val adminRepository: AdminRepository,
    private val authRepository: AuthRepository,
    private val aiPreferences: AiPreferenceStore,
    private val aiSnapshotCache: AiSnapshotCache,
    private val aiQuotaGuard: AiQuotaGuard
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminDashboardUiState())
    val uiState: StateFlow<AdminDashboardUiState> = _uiState.asStateFlow()

    init {
        initializeAdminConsole()
    }

    private fun initializeAdminConsole() {
        viewModelScope.launch {
            val authorized = adminRepository.isCurrentUserAdmin()
            val mode = adminRepository.getSessionMode()
            val uid = authRepository.getCurrentUserId().orEmpty()
            val email = authRepository.getCurrentUserEmail().orEmpty()
            _uiState.update {
                it.copy(
                    isCheckingAccess = false,
                    isAuthorizedAdmin = authorized,
                    sessionMode = mode,
                    currentAdminUid = uid,
                    currentAdminEmail = email
                )
            }
            if (!authorized) return@launch

            observeUsers()
            observeSystemConfig()
            observeGeminiApiKey()
            observeTodayAiStats()
            observeAuditLogs()
            reloadMetrics()
            reloadPlatformTasks()
        }
    }

    private fun observeGeminiApiKey() {
        viewModelScope.launch {
            aiPreferences.geminiApiKey.collectLatest { savedKey ->
                val effective = savedKey.ifBlank { BuildConfig.GEMINI_API_KEY }
                _uiState.update { it.copy(geminiApiKey = effective) }
            }
        }
    }

    fun saveGeminiApiKey(rawKey: String) {
        viewModelScope.launch {
            val cleaned = rawKey.trim().removeSurrounding("\"").trim()
            aiPreferences.setGeminiApiKey(cleaned)
            aiSnapshotCache.clearAll()
            aiQuotaGuard.resetRuntimeState()
            _uiState.update {
                it.copy(
                    snackbarMessage = if (cleaned.isNotEmpty()) {
                        "Gemini API key saved — Smart AI ready"
                    } else {
                        "Gemini API key cleared"
                    }
                )
            }
        }
    }

    private fun observeUsers() {
        viewModelScope.launch {
            adminRepository.observeUsers().collectLatest { res ->
                when (res) {
                    is Result.Success -> {
                        _uiState.update { state ->
                            val refreshedDetail = state.selectedUserDetail?.let { detail ->
                                val updatedUser = res.data.find { it.uid == detail.user.uid }
                                if (updatedUser != null) detail.copy(user = updatedUser) else null
                            }
                            state.copy(users = res.data, selectedUserDetail = refreshedDetail)
                        }
                    }
                    is Result.Error -> {
                        _uiState.update {
                            it.copy(snackbarMessage = res.message ?: "Failed to load user directory")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun observeSystemConfig() {
        viewModelScope.launch {
            adminRepository.observeSystemConfig().collectLatest { liveConfig ->
                _uiState.update { state ->
                    val nextDraft = if (state.hasUnsavedConfigChanges) state.configDraft else liveConfig
                    state.copy(
                        systemConfig = liveConfig,
                        configDraft = nextDraft
                    )
                }
            }
        }
    }

    private fun observeTodayAiStats() {
        viewModelScope.launch {
            adminRepository.observeTodayAiStats().collectLatest { stats ->
                _uiState.update { it.copy(todayAiStats = stats) }
            }
        }
    }

    private fun observeAuditLogs() {
        viewModelScope.launch {
            adminRepository.observeAuditLogs(limit = 80).collectLatest { entries ->
                _uiState.update { it.copy(auditLogs = entries) }
            }
        }
    }

    fun reloadMetrics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMetrics = true) }
            val mode = adminRepository.getSessionMode()
            when (val res = adminRepository.loadPlatformMetrics()) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingMetrics = false,
                            sessionMode = mode,
                            metrics = res.data
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingMetrics = false,
                            sessionMode = mode,
                            snackbarMessage = res.message ?: "Couldn't refresh platform metrics"
                        )
                    }
                }
                else -> _uiState.update { it.copy(isLoadingMetrics = false, sessionMode = mode) }
            }
        }
    }

    fun reloadPlatformTasks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTasks = true) }
            when (val res = adminRepository.loadAllPlatformTasks()) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(isLoadingTasks = false, platformTasks = res.data)
                    }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoadingTasks = false) }
                }
                else -> _uiState.update { it.copy(isLoadingTasks = false) }
            }
        }
    }

    fun refreshAll() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            reloadMetrics()
            reloadPlatformTasks()
            delay(450L)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun selectTab(tab: AdminTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab == AdminTab.TASKS && _uiState.value.platformTasks.isEmpty()) {
            reloadPlatformTasks()
        }
    }

    /* ---------------------- Users Tab & Governance ---------------------- */

    fun setUserSearchQuery(query: String) {
        _uiState.update { it.copy(userSearchQuery = query) }
    }

    fun setUserFilter(filter: AdminUserFilter) {
        _uiState.update { it.copy(userFilter = filter) }
    }

    fun filteredUsers(): List<AdminUserSummary> {
        val state = _uiState.value
        val q = state.userSearchQuery.trim().lowercase()
        return state.users.filter { user ->
            val matchesFilter = when (state.userFilter) {
                AdminUserFilter.ALL -> true
                AdminUserFilter.ADMINS -> user.role == UserRole.ADMIN
                AdminUserFilter.ACTIVE -> !user.isDisabled
                AdminUserFilter.SUSPENDED -> user.isDisabled
            }
            val matchesQuery = q.isEmpty() ||
                user.displayName.lowercase().contains(q) ||
                user.email.lowercase().contains(q) ||
                user.uid.lowercase().contains(q)
            matchesFilter && matchesQuery
        }
    }

    fun inspectUser(user: AdminUserSummary) {
        _uiState.update {
            it.copy(
                selectedUserDetail = AdminUserDetail(user = user),
                isLoadingUserDetail = true
            )
        }
        viewModelScope.launch {
            when (val res = adminRepository.loadUserDetail(user)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            selectedUserDetail = res.data,
                            isLoadingUserDetail = false
                        )
                    }
                }
                else -> _uiState.update { it.copy(isLoadingUserDetail = false) }
            }
        }
    }

    fun dismissUserInspector() {
        _uiState.update { it.copy(selectedUserDetail = null, isLoadingUserDetail = false) }
    }

    fun updateUserDisplayName(target: AdminUserSummary, newName: String) {
        viewModelScope.launch {
            when (val res = adminRepository.updateUserDisplayName(target, newName)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(snackbarMessage = "Updated display name to '${newName.trim()}'")
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(snackbarMessage = res.message ?: "Failed to update display name")
                    }
                }
                else -> Unit
            }
        }
    }

    fun sendUserPasswordReset(email: String) {
        viewModelScope.launch {
            when (val res = adminRepository.sendUserPasswordReset(email)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(snackbarMessage = "Password reset link sent to $email")
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(snackbarMessage = res.message ?: "Failed to send password reset email")
                    }
                }
                else -> Unit
            }
        }
    }

    fun toggleUserAdminRole(target: AdminUserSummary) {
        val makeAdmin = target.role != UserRole.ADMIN
        viewModelScope.launch {
            when (val res = adminRepository.setUserAdminRole(target, makeAdmin)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            snackbarMessage = if (makeAdmin) {
                                "Granted Admin access to ${target.email}"
                            } else {
                                "Revoked Admin access from ${target.email}"
                            }
                        )
                    }
                    reloadMetrics()
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(snackbarMessage = res.message ?: "Failed to update role")
                    }
                }
                else -> Unit
            }
        }
    }

    fun toggleUserSuspension(target: AdminUserSummary) {
        val suspend = !target.isDisabled
        viewModelScope.launch {
            when (val res = adminRepository.setUserSuspended(target, suspend)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            snackbarMessage = if (suspend) {
                                "Suspended ${target.email}"
                            } else {
                                "Reactivated ${target.email}"
                            }
                        )
                    }
                    reloadMetrics()
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(snackbarMessage = res.message ?: "Failed to update account status")
                    }
                }
                else -> Unit
            }
        }
    }

    fun requestDeleteUser(target: AdminUserSummary?) {
        _uiState.update { it.copy(confirmDeleteUser = target) }
    }

    fun confirmDeleteUserWorkspace() {
        val target = _uiState.value.confirmDeleteUser ?: return
        _uiState.update { it.copy(confirmDeleteUser = null, selectedUserDetail = null) }
        viewModelScope.launch {
            when (val res = adminRepository.deleteUserWorkspace(target)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(snackbarMessage = "Deleted workspace for ${target.email.ifBlank { target.uid }}")
                    }
                    reloadMetrics()
                    reloadPlatformTasks()
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(snackbarMessage = res.message ?: "Failed to delete user workspace")
                    }
                }
                else -> Unit
            }
        }
    }

    /* ---------------------- Global Tasks Explorer & CRUD ---------------------- */

    fun setTaskSearchQuery(query: String) {
        _uiState.update { it.copy(taskSearchQuery = query) }
    }

    fun setTaskFilter(filter: AdminTaskFilter) {
        _uiState.update { it.copy(taskFilter = filter) }
    }

    fun filteredTasks(): List<AdminTaskRecord> {
        val state = _uiState.value
        val q = state.taskSearchQuery.trim().lowercase()
        val zone = ZoneId.systemDefault()
        val startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

        return state.platformTasks.filter { task ->
            val matchesFilter = when (state.taskFilter) {
                AdminTaskFilter.ALL -> true
                AdminTaskFilter.OPEN -> !task.isCompleted
                AdminTaskFilter.COMPLETED -> task.isCompleted
                AdminTaskFilter.OVERDUE -> !task.isCompleted && task.dueAtMillis != null && task.dueAtMillis < startOfToday
                AdminTaskFilter.URGENT_HIGH -> !task.isCompleted && (task.priority == Priority.URGENT || task.priority == Priority.HIGH)
                AdminTaskFilter.HABITS -> task.isRecurring
            }
            val matchesQuery = q.isEmpty() ||
                task.title.lowercase().contains(q) ||
                task.description.lowercase().contains(q) ||
                task.ownerEmail.lowercase().contains(q) ||
                task.ownerName.lowercase().contains(q) ||
                task.categoryName.lowercase().contains(q)
            matchesFilter && matchesQuery
        }
    }

    fun toggleTaskComplete(task: AdminTaskRecord) {
        viewModelScope.launch {
            when (val res = adminRepository.togglePlatformTaskComplete(task)) {
                is Result.Success -> {
                    _uiState.update { state ->
                        val updated = state.platformTasks.map {
                            if (it.taskId == task.taskId && it.ownerUid == task.ownerUid) {
                                it.copy(isCompleted = !it.isCompleted)
                            } else {
                                it
                            }
                        }
                        state.copy(platformTasks = updated)
                    }
                    reloadMetrics()
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(snackbarMessage = res.message ?: "Failed to toggle task")
                    }
                }
                else -> Unit
            }
        }
    }

    fun openEditTask(task: AdminTaskRecord?) {
        _uiState.update { it.copy(editingTask = task) }
    }

    fun saveEditedTask(newTitle: String, newDescription: String, newPriority: Priority) {
        val target = _uiState.value.editingTask ?: return
        if (_uiState.value.isSavingTask) return
        _uiState.update { it.copy(isSavingTask = true) }
        viewModelScope.launch {
            when (val res = adminRepository.updatePlatformTask(target, newTitle, newDescription, newPriority)) {
                is Result.Success -> {
                    _uiState.update { state ->
                        val updated = state.platformTasks.map {
                            if (it.taskId == target.taskId && it.ownerUid == target.ownerUid) {
                                it.copy(
                                    title = newTitle.trim(),
                                    description = newDescription.trim(),
                                    priority = newPriority
                                )
                            } else {
                                it
                            }
                        }
                        state.copy(
                            isSavingTask = false,
                            editingTask = null,
                            platformTasks = updated,
                            snackbarMessage = "Saved task changes"
                        )
                    }
                    reloadMetrics()
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isSavingTask = false,
                            snackbarMessage = res.message ?: "Failed to update task"
                        )
                    }
                }
                else -> _uiState.update { it.copy(isSavingTask = false) }
            }
        }
    }

    fun deletePlatformTask(task: AdminTaskRecord) {
        viewModelScope.launch {
            when (val res = adminRepository.deletePlatformTask(task)) {
                is Result.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            editingTask = null,
                            platformTasks = state.platformTasks.filterNot {
                                it.taskId == task.taskId && it.ownerUid == task.ownerUid
                            },
                            snackbarMessage = "Deleted task '${task.title.take(28)}'"
                        )
                    }
                    reloadMetrics()
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(snackbarMessage = res.message ?: "Failed to delete task")
                    }
                }
                else -> Unit
            }
        }
    }

    /* ---------------------- AI & Runtime Config ---------------------- */

    fun updateConfigDraft(transform: (AdminSystemConfig) -> AdminSystemConfig) {
        _uiState.update { state ->
            val updated = transform(state.configDraft)
            state.copy(
                configDraft = updated,
                hasUnsavedConfigChanges = updated != state.systemConfig
            )
        }
    }

    fun resetConfigDraft() {
        _uiState.update {
            it.copy(configDraft = it.systemConfig, hasUnsavedConfigChanges = false)
        }
    }

    fun saveSystemConfig() {
        if (_uiState.value.isSavingConfig) return
        _uiState.update { it.copy(isSavingConfig = true) }
        viewModelScope.launch {
            when (val res = adminRepository.updateSystemConfig(_uiState.value.configDraft)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            isSavingConfig = false,
                            hasUnsavedConfigChanges = false,
                            snackbarMessage = "Saved global configuration"
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isSavingConfig = false,
                            snackbarMessage = res.message ?: "Failed to save configuration"
                        )
                    }
                }
                else -> _uiState.update { it.copy(isSavingConfig = false) }
            }
        }
    }

    /* ---------------------- Audit, Export & Security ---------------------- */

    fun setAuditFilter(filter: AdminAuditFilter) {
        _uiState.update { it.copy(auditFilter = filter) }
    }

    fun setAuditSearchQuery(query: String) {
        _uiState.update { it.copy(auditSearchQuery = query) }
    }

    fun filteredAuditLogs(): List<AdminAuditEntry> {
        val state = _uiState.value
        val q = state.auditSearchQuery.trim().lowercase()
        return state.auditLogs.filter { entry ->
            val act = entry.action.uppercase()
            val matchesFilter = when (state.auditFilter) {
                AdminAuditFilter.ALL -> true
                AdminAuditFilter.AUTH -> "BOOTSTRAP" in act || "SESSION" in act || "PASSWORD" in act || "GOOGLE" in act
                AdminAuditFilter.USERS -> "USER" in act || "ADMIN" in act && "TASK" !in act && "EXPORT" !in act
                AdminAuditFilter.TASKS -> "TASK" in act
                AdminAuditFilter.CONFIG -> "CONFIG" in act
                AdminAuditFilter.EXPORT -> "EXPORT" in act
            }
            val matchesQuery = q.isEmpty() ||
                entry.action.lowercase().contains(q) ||
                entry.summary.lowercase().contains(q) ||
                entry.actorEmail.lowercase().contains(q) ||
                entry.targetId.lowercase().contains(q)
            matchesFilter && matchesQuery
        }
    }

    fun setShowExportSheet(show: Boolean) {
        _uiState.update {
            it.copy(
                showExportSheet = show,
                readyExportPayload = if (!show) null else it.readyExportPayload
            )
        }
    }

    fun triggerExport(type: AdminExportType) {
        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            when (val res = adminRepository.generateExport(type)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            readyExportPayload = res.data,
                            snackbarMessage = "Prepared ${res.data.fileName}"
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            snackbarMessage = res.message ?: "Export failed"
                        )
                    }
                }
                else -> _uiState.update { it.copy(isExporting = false) }
            }
        }
    }

    fun linkGoogleFirebaseToken(idToken: String) {
        if (_uiState.value.isLinkingGoogle) return
        _uiState.update { it.copy(isLinkingGoogle = true) }
        viewModelScope.launch {
            when (val res = adminRepository.linkGoogleAdminToken(idToken)) {
                is Result.Success -> {
                    val mode = adminRepository.getSessionMode()
                    val uid = authRepository.getCurrentUserId().orEmpty()
                    val email = authRepository.getCurrentUserEmail().orEmpty()
                    _uiState.update {
                        it.copy(
                            isLinkingGoogle = false,
                            sessionMode = mode,
                            currentAdminUid = uid,
                            currentAdminEmail = email,
                            snackbarMessage = "Connected Google Firebase token & synced admin access"
                        )
                    }
                    reloadMetrics()
                    reloadPlatformTasks()
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isLinkingGoogle = false,
                            snackbarMessage = res.message ?: "Failed to connect Google token"
                        )
                    }
                }
                else -> _uiState.update { it.copy(isLinkingGoogle = false) }
            }
        }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun setShowPasswordSheet(show: Boolean) {
        _uiState.update { it.copy(showPasswordSheet = show) }
    }

    fun changeAdminPassword(currentPassword: String, newPassword: String) {
        if (_uiState.value.isUpdatingPassword) return
        _uiState.update { it.copy(isUpdatingPassword = true) }
        viewModelScope.launch {
            when (val res = adminRepository.changeAdminPassword(currentPassword, newPassword)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            isUpdatingPassword = false,
                            showPasswordSheet = false,
                            snackbarMessage = "Admin password updated"
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isUpdatingPassword = false,
                            snackbarMessage = res.message ?: "Couldn't update password"
                        )
                    }
                }
                else -> _uiState.update { it.copy(isUpdatingPassword = false) }
            }
        }
    }

    fun signOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            authRepository.signOut()
            onSignedOut()
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
