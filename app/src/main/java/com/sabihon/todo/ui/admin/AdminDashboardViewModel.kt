package com.sabihon.todo.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.admin.AdminRepository
import com.sabihon.todo.domain.admin.AdminSystemConfig
import com.sabihon.todo.domain.admin.AdminUserDetail
import com.sabihon.todo.domain.admin.AdminUserSummary
import com.sabihon.todo.domain.admin.UserRole
import com.sabihon.todo.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdminDashboardViewModel @Inject constructor(
    private val adminRepository: AdminRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminDashboardUiState())
    val uiState: StateFlow<AdminDashboardUiState> = _uiState.asStateFlow()

    init {
        initializeAdminConsole()
    }

    private fun initializeAdminConsole() {
        viewModelScope.launch {
            val authorized = adminRepository.isCurrentUserAdmin()
            val uid = authRepository.getCurrentUserId().orEmpty()
            val email = authRepository.getCurrentUserEmail().orEmpty()
            _uiState.update {
                it.copy(
                    isCheckingAccess = false,
                    isAuthorizedAdmin = authorized,
                    currentAdminUid = uid,
                    currentAdminEmail = email
                )
            }
            if (!authorized) return@launch

            observeUsers()
            observeSystemConfig()
            observeTodayAiStats()
            observeAuditLogs()
            reloadMetrics()
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
                                if (updatedUser != null) detail.copy(user = updatedUser) else detail
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
            adminRepository.observeAuditLogs(limit = 50).collectLatest { entries ->
                _uiState.update { it.copy(auditLogs = entries) }
            }
        }
    }

    fun reloadMetrics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMetrics = true) }
            when (val res = adminRepository.loadPlatformMetrics()) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(isLoadingMetrics = false, metrics = res.data)
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingMetrics = false,
                            snackbarMessage = res.message ?: "Couldn't refresh platform metrics"
                        )
                    }
                }
                else -> _uiState.update { it.copy(isLoadingMetrics = false) }
            }
        }
    }

    fun refreshAll() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            reloadMetrics()
            delay(450L)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun selectTab(tab: AdminTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

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
