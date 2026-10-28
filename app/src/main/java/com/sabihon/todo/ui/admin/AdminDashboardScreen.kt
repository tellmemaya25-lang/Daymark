package com.sabihon.todo.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.SabihonApp
import com.sabihon.todo.core.ui.components.GroupedCard
import com.sabihon.todo.core.ui.components.GroupedDivider
import com.sabihon.todo.core.ui.components.GroupedRow
import com.sabihon.todo.core.ui.components.SectionLabel
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.DaymarkSheetShape
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.domain.admin.AdminAccessPolicy
import com.sabihon.todo.domain.admin.AdminAiDailyStats
import com.sabihon.todo.domain.admin.AdminAuditEntry
import com.sabihon.todo.domain.admin.AdminExportPayload
import com.sabihon.todo.domain.admin.AdminExportType
import com.sabihon.todo.domain.admin.AdminPlatformMetrics
import com.sabihon.todo.domain.admin.AdminSessionMode
import com.sabihon.todo.domain.admin.AdminSystemConfig
import com.sabihon.todo.domain.admin.AdminTaskRecord
import com.sabihon.todo.domain.admin.AdminUserDetail
import com.sabihon.todo.domain.admin.AdminUserSummary
import com.sabihon.todo.domain.admin.DailyTaskBucket
import com.sabihon.todo.domain.admin.UserRole
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.ui.auth.GoogleAuthButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUOTA_PRESETS = listOf(15, 25, 50, 100, 250)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    onOpenWorkspace: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: AdminDashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.isCheckingAccess, uiState.isAuthorizedAdmin) {
        if (!uiState.isCheckingAccess && !uiState.isAuthorizedAdmin) {
            onOpenWorkspace()
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.clearSnackbar()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isCheckingAccess || !uiState.isAuthorizedAdmin) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentBlue)
            }
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refreshAll() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AdminTopHeader(
                    adminEmail = uiState.currentAdminEmail,
                    sessionMode = uiState.sessionMode,
                    onExportClick = { viewModel.setShowExportSheet(true) },
                    onRefresh = { viewModel.refreshAll() },
                    onOpenWorkspace = onOpenWorkspace
                )

                AdminTabStrip(
                    selectedTab = uiState.selectedTab,
                    userCount = uiState.users.size,
                    taskCount = uiState.platformTasks.size,
                    onSelectTab = viewModel::selectTab
                )

                when (uiState.selectedTab) {
                    AdminTab.OVERVIEW -> AdminOverviewTab(
                        metrics = uiState.metrics,
                        aiStats = uiState.todayAiStats,
                        sessionMode = uiState.sessionMode,
                        isLinkingGoogle = uiState.isLinkingGoogle,
                        isLoading = uiState.isLoadingMetrics,
                        onGoogleIdToken = viewModel::linkGoogleFirebaseToken,
                        onGoogleError = viewModel::setError
                    )
                    AdminTab.USERS -> AdminUsersTab(
                        users = remember(
                            uiState.users,
                            uiState.userSearchQuery,
                            uiState.userFilter
                        ) { viewModel.filteredUsers() },
                        searchQuery = uiState.userSearchQuery,
                        selectedFilter = uiState.userFilter,
                        onSearchQueryChange = viewModel::setUserSearchQuery,
                        onSelectFilter = viewModel::setUserFilter,
                        onInspectUser = viewModel::inspectUser
                    )
                    AdminTab.TASKS -> AdminTasksTab(
                        tasks = remember(
                            uiState.platformTasks,
                            uiState.taskSearchQuery,
                            uiState.taskFilter
                        ) { viewModel.filteredTasks() },
                        isLoading = uiState.isLoadingTasks,
                        searchQuery = uiState.taskSearchQuery,
                        selectedFilter = uiState.taskFilter,
                        onSearchQueryChange = viewModel::setTaskSearchQuery,
                        onSelectFilter = viewModel::setTaskFilter,
                        onToggleComplete = viewModel::toggleTaskComplete,
                        onEditTask = { viewModel.openEditTask(it) },
                        onDeleteTask = viewModel::deletePlatformTask
                    )
                    AdminTab.AI_CONFIG -> AdminAiConfigTab(
                        draft = uiState.configDraft,
                        savedConfig = uiState.systemConfig,
                        hasUnsavedChanges = uiState.hasUnsavedConfigChanges,
                        isSaving = uiState.isSavingConfig,
                        geminiApiKey = uiState.geminiApiKey,
                        onSaveGeminiApiKey = viewModel::saveGeminiApiKey,
                        onShowMessage = viewModel::setError,
                        onUpdateDraft = viewModel::updateConfigDraft,
                        onResetDraft = viewModel::resetConfigDraft,
                        onSave = viewModel::saveSystemConfig
                    )
                    AdminTab.AUDIT_SECURITY -> AdminAuditSecurityTab(
                        adminEmail = uiState.currentAdminEmail,
                        adminUid = uiState.currentAdminUid,
                        sessionMode = uiState.sessionMode,
                        isLinkingGoogle = uiState.isLinkingGoogle,
                        auditLogs = remember(
                            uiState.auditLogs,
                            uiState.auditFilter,
                            uiState.auditSearchQuery
                        ) { viewModel.filteredAuditLogs() },
                        selectedFilter = uiState.auditFilter,
                        searchQuery = uiState.auditSearchQuery,
                        onSelectFilter = viewModel::setAuditFilter,
                        onSearchQueryChange = viewModel::setAuditSearchQuery,
                        onGoogleIdToken = viewModel::linkGoogleFirebaseToken,
                        onGoogleError = viewModel::setError,
                        onOpenExportSheet = { viewModel.setShowExportSheet(true) },
                        onOpenPasswordSheet = { viewModel.setShowPasswordSheet(true) },
                        onOpenWorkspace = onOpenWorkspace,
                        onSignOut = { viewModel.signOut(onSignedOut) }
                    )
                }
            }
        }
    }

    val inspectedDetail = uiState.selectedUserDetail
    if (inspectedDetail != null) {
        UserInspectorSheet(
            detail = inspectedDetail,
            actorUid = uiState.currentAdminUid,
            isLoadingCounts = uiState.isLoadingUserDetail,
            onSaveDisplayName = { newName ->
                viewModel.updateUserDisplayName(inspectedDetail.user, newName)
            },
            onSendPasswordReset = {
                viewModel.sendUserPasswordReset(inspectedDetail.user.email)
            },
            onToggleAdminRole = { viewModel.toggleUserAdminRole(inspectedDetail.user) },
            onToggleSuspend = { viewModel.toggleUserSuspension(inspectedDetail.user) },
            onRequestDelete = { viewModel.requestDeleteUser(inspectedDetail.user) },
            onDismiss = viewModel::dismissUserInspector
        )
    }

    val pendingDeleteUser = uiState.confirmDeleteUser
    if (pendingDeleteUser != null) {
        AlertDialog(
            onDismissRequest = { viewModel.requestDeleteUser(null) },
            title = { Text("Delete user workspace?") },
            text = {
                Text(
                    "This permanently deletes ${pendingDeleteUser.email.ifBlank { pendingDeleteUser.displayName }}'s " +
                        "Firestore profile, tasks, and categories. This action is logged and cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDeleteUserWorkspace() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEB5757),
                        contentColor = Color.White
                    )
                ) {
                    Text("Delete Workspace")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.requestDeleteUser(null) }) {
                    Text("Cancel")
                }
            }
        )
    }

    val editingTask = uiState.editingTask
    if (editingTask != null) {
        AdminTaskEditSheet(
            task = editingTask,
            isSaving = uiState.isSavingTask,
            onSave = viewModel::saveEditedTask,
            onDelete = { viewModel.deletePlatformTask(editingTask) },
            onDismiss = { viewModel.openEditTask(null) }
        )
    }

    if (uiState.showExportSheet) {
        ExportDataSheet(
            isExporting = uiState.isExporting,
            readyPayload = uiState.readyExportPayload,
            onGenerate = viewModel::triggerExport,
            onSharePayload = { payload ->
                shareExportPayload(context, payload)
            },
            onCopyPayload = { payload ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText(payload.fileName, payload.content))
                scope.launch {
                    snackbarHostState.showSnackbar("Copied ${payload.fileName} to clipboard")
                }
            },
            onDismiss = { viewModel.setShowExportSheet(false) }
        )
    }

    if (uiState.showPasswordSheet) {
        ChangeAdminPasswordSheet(
            isSaving = uiState.isUpdatingPassword,
            onSave = { currentPw, newPw ->
                viewModel.changeAdminPassword(currentPw, newPw)
            },
            onDismiss = { viewModel.setShowPasswordSheet(false) }
        )
    }
}

private fun shareExportPayload(context: Context, payload: AdminExportPayload) {
    runCatching {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = payload.mimeType
            putExtra(Intent.EXTRA_SUBJECT, payload.fileName)
            putExtra(Intent.EXTRA_TITLE, payload.fileName)
            putExtra(Intent.EXTRA_TEXT, payload.content)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Export ${payload.fileName}"))
    }
}

@Composable
private fun AdminTopHeader(
    adminEmail: String,
    sessionMode: AdminSessionMode,
    onExportClick: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWorkspace: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(AccentBlue.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.AdminPanelSettings,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "ADMIN CONSOLE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.0.sp
                        ),
                        color = AccentBlue
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            IconButton(
                onClick = onExportClick,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Export CSV or JSON",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh console",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }

            Spacer(Modifier.width(4.dp))

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(surfaces.card)
                    .border(1.dp, surfaces.cardBorder, RoundedCornerShape(100.dp))
                    .clickable(onClick = onOpenWorkspace)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Workspace",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(5.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "daymark . control",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 25.sp,
                    letterSpacing = (-0.6).sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            RoleBadge(
                text = if (sessionMode == AdminSessionMode.FIREBASE_NATIVE) "FIREBASE LIVE" else "HYBRID SESSION",
                color = if (sessionMode == AdminSessionMode.FIREBASE_NATIVE) Color(0xFF34C759) else Color(0xFFF2994A)
            )
        }

        if (adminEmail.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Signed in as $adminEmail",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AdminTabStrip(
    selectedTab: AdminTab,
    userCount: Int,
    taskCount: Int,
    onSelectTab: (AdminTab) -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AdminTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val icon = when (tab) {
                AdminTab.OVERVIEW -> Icons.Filled.AutoAwesome
                AdminTab.USERS -> Icons.Filled.People
                AdminTab.TASKS -> Icons.AutoMirrored.Filled.FormatListBulleted
                AdminTab.AI_CONFIG -> Icons.Filled.Tune
                AdminTab.AUDIT_SECURITY -> Icons.Filled.Security
            }
            val badgeSuffix = when (tab) {
                AdminTab.USERS -> if (userCount > 0) " ($userCount)" else ""
                AdminTab.TASKS -> if (taskCount > 0) " ($taskCount)" else ""
                else -> ""
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(if (selected) AccentBlue else surfaces.card)
                    .border(
                        width = 1.dp,
                        color = if (selected) AccentBlue else surfaces.cardBorder,
                        shape = RoundedCornerShape(100.dp)
                    )
                    .clickable { onSelectTab(tab) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${tab.label}$badgeSuffix",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Tab 1: Overview & Deep Analytics                                           */
/* -------------------------------------------------------------------------- */

@Composable
private fun AdminOverviewTab(
    metrics: AdminPlatformMetrics,
    aiStats: AdminAiDailyStats,
    sessionMode: AdminSessionMode,
    isLinkingGoogle: Boolean,
    isLoading: Boolean,
    onGoogleIdToken: (String) -> Unit,
    onGoogleError: (String) -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (metrics.permissionLimited && !metrics.diagnosticHint.isNullOrBlank()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFFF2994A).copy(alpha = 0.14f))
                        .border(1.dp, Color(0xFFF2994A).copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Color(0xFFF2994A),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = metrics.diagnosticHint,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (sessionMode == AdminSessionMode.HYBRID_OWNER_SESSION) {
                        GoogleAuthButton(
                            onIdTokenReceived = onGoogleIdToken,
                            onError = onGoogleError,
                            isLoading = isLinkingGoogle,
                            buttonText = "Connect Google Firebase Token"
                        )
                    }
                }
            }
        }

        if (isLoading && metrics.totalUsers == 0 && metrics.totalTasks == 0) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentBlue)
                }
            }
        }

        // Row 1: Users & Governance
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AdminKpiTile(
                    title = "Total Users",
                    value = "${metrics.totalUsers}",
                    subtitle = "+${metrics.newUsersLast7Days} new · ${metrics.activeUsersLast7Days} active 7d",
                    accent = AccentBlue,
                    modifier = Modifier.weight(1f)
                )
                AdminKpiTile(
                    title = "Governance",
                    value = "${metrics.activeAdmins} Admin",
                    subtitle = "${metrics.suspendedUsers} suspended accounts",
                    accent = Color(0xFF9B51E0),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Row 2: Tasks & Completion
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AdminKpiTile(
                    title = "Total Tasks",
                    value = "${metrics.totalTasks}",
                    subtitle = "${metrics.openTasks} open · ${metrics.completedTasks} done",
                    accent = Color(0xFF34C759),
                    modifier = Modifier.weight(1f)
                )
                AdminKpiTile(
                    title = "Completion Rate",
                    value = "${metrics.completionRatePercent}%",
                    subtitle = "${metrics.overdueOpenTasks} overdue open tasks",
                    accent = Color(0xFF2D9CDB),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Row 3: Habits & Categories
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AdminKpiTile(
                    title = "Recurring Habits",
                    value = "${metrics.activeHabitTasks}",
                    subtitle = "Top streak: ${metrics.maxHabitStreak} days",
                    accent = Color(0xFFEB5757),
                    modifier = Modifier.weight(1f)
                )
                AdminKpiTile(
                    title = "Projects",
                    value = "${metrics.totalCategories}",
                    subtitle = "Avg ${metrics.avgTasksPerUser} tasks / user",
                    accent = Color(0xFF27AE60),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Row 4: Cloud AI Today & Suggestion Acceptance
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AdminKpiTile(
                    title = "AI Calls Today",
                    value = "${aiStats.totalCalls}",
                    subtitle = "${aiStats.cloudHitRatePercent}% cloud · ${aiStats.averageLatencyMs} ms",
                    accent = Color(0xFFF2994A),
                    modifier = Modifier.weight(1f)
                )
                AdminKpiTile(
                    title = "AI Acceptance",
                    value = "${aiStats.acceptanceRatePercent}%",
                    subtitle = "${aiStats.acceptedOutcomes} kept · ${aiStats.dismissedOutcomes} skipped",
                    accent = AccentBlue,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (metrics.sevenDayVelocity.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(surfaces.card)
                        .border(1.dp, surfaces.cardBorder, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "7-Day Task Velocity",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        LegendDot(color = AccentBlue, label = "Created")
                        Spacer(Modifier.width(10.dp))
                        LegendDot(color = Color(0xFF34C759), label = "Done")
                    }
                    Spacer(Modifier.height(14.dp))
                    SevenDayVelocityChart(buckets = metrics.sevenDayVelocity)
                }
            }
        }

        if (metrics.sevenDayAiTrend.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(surfaces.card)
                        .border(1.dp, surfaces.cardBorder, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "7-Day AI Volume (Cloud vs Local)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        LegendDot(color = Color(0xFFF2994A), label = "Gemini")
                        Spacer(Modifier.width(10.dp))
                        LegendDot(color = Color(0xFF9B51E0), label = "Local")
                    }
                    Spacer(Modifier.height(14.dp))
                    SevenDayAiTrendChart(days = metrics.sevenDayAiTrend)
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(surfaces.card)
                    .border(1.dp, surfaces.cardBorder, RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Open Tasks by Priority",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                val totalOpen = metrics.openTasks.coerceAtLeast(1)
                listOf(
                    Triple(Priority.URGENT, "Urgent", Color(0xFFEB5757)),
                    Triple(Priority.HIGH, "High", Color(0xFFF2994A)),
                    Triple(Priority.MEDIUM, "Medium", AccentBlue),
                    Triple(Priority.LOW, "Low", Color(0xFF6FCF97))
                ).forEach { (prio, label, tint) ->
                    val count = metrics.priorityBreakdown[prio] ?: 0
                    val ratio = (count.toFloat() / totalOpen.toFloat()).coerceIn(0f, 1f)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(ratio)
                                    .clip(CircleShape)
                                    .background(tint)
                            )
                        }
                    }
                }
            }
        }

        item {
            GroupedCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Top Projects & Theme Adoption",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (metrics.topCategories.isEmpty()) {
                        StatLine("Categories", "No tasks assigned yet")
                    } else {
                        metrics.topCategories.forEach { cat ->
                            StatLine(cat.name, "${cat.taskCount} tasks")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    val sys = metrics.themeBreakdown["SYSTEM"] ?: 0
                    val light = metrics.themeBreakdown["LIGHT"] ?: 0
                    val dark = metrics.themeBreakdown["DARK"] ?: 0
                    StatLine("Theme split (System / Light / Dark)", "$sys / $light / $dark")
                    StatLine("Cloud Gemini 2.5 responses today", "${aiStats.cloudSuccessCount}")
                    StatLine("Rung-0 deterministic fallbacks today", "${aiStats.localFallbackCount}")
                    StatLine("AI errors / timeouts today", "${aiStats.errorCount}")
                }
            }
        }
    }
}

@Composable
private fun AdminKpiTile(
    title: String,
    value: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val surfaces = LocalDaymarkSurfaces.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(surfaces.card)
            .border(1.dp, surfaces.cardBorder, RoundedCornerShape(20.dp))
            .padding(15.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SevenDayVelocityChart(buckets: List<DailyTaskBucket>) {
    val maxVal = buckets.maxOfOrNull { maxOf(it.createdCount, it.completedCount) }
        ?.coerceAtLeast(1) ?: 1

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        buckets.forEach { bucket ->
            val createdRatio = (bucket.createdCount.toFloat() / maxVal.toFloat()).coerceIn(0.06f, 1f)
            val doneRatio = (bucket.completedCount.toFloat() / maxVal.toFloat()).coerceIn(0.06f, 1f)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.height(80.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .fillMaxHeight(if (bucket.createdCount == 0) 0.06f else createdRatio)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                if (bucket.createdCount == 0) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                                } else {
                                    AccentBlue
                                }
                            )
                    )
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .fillMaxHeight(if (bucket.completedCount == 0) 0.06f else doneRatio)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                if (bucket.completedCount == 0) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                                } else {
                                    Color(0xFF34C759)
                                }
                            )
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = bucket.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SevenDayAiTrendChart(days: List<AdminAiDailyStats>) {
    val maxVal = days.maxOfOrNull { maxOf(it.cloudSuccessCount, it.localFallbackCount) }
        ?.coerceAtLeast(1L) ?: 1L

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        days.forEach { stat ->
            val cloudRatio = (stat.cloudSuccessCount.toFloat() / maxVal.toFloat()).coerceIn(0.06f, 1f)
            val localRatio = (stat.localFallbackCount.toFloat() / maxVal.toFloat()).coerceIn(0.06f, 1f)
            val shortLabel = stat.dateKey.takeLast(5) // MM-dd
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.height(80.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .fillMaxHeight(if (stat.cloudSuccessCount == 0L) 0.06f else cloudRatio)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                if (stat.cloudSuccessCount == 0L) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                                } else {
                                    Color(0xFFF2994A)
                                }
                            )
                    )
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .fillMaxHeight(if (stat.localFallbackCount == 0L) 0.06f else localRatio)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                if (stat.localFallbackCount == 0L) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                                } else {
                                    Color(0xFF9B51E0)
                                }
                            )
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = shortLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/* -------------------------------------------------------------------------- */
/* Tab 2: Users & Governance                                                  */
/* -------------------------------------------------------------------------- */

@Composable
private fun AdminUsersTab(
    users: List<AdminUserSummary>,
    searchQuery: String,
    selectedFilter: AdminUserFilter,
    onSearchQueryChange: (String) -> Unit,
    onSelectFilter: (AdminUserFilter) -> Unit,
    onInspectUser: (AdminUserSummary) -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search name, email, or UID...") },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AdminUserFilter.entries.forEach { filter ->
                val active = filter == selectedFilter
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (active) surfaces.accentSurface else surfaces.card)
                        .border(1.dp, surfaces.cardBorder, RoundedCornerShape(100.dp))
                        .clickable { onSelectFilter(filter) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = filter.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (active) surfaces.onAccentSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (users.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No matching accounts",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(users, key = { it.uid }) { user ->
                    AdminUserRow(
                        user = user,
                        onClick = { onInspectUser(user) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminUserRow(
    user: AdminUserSummary,
    onClick: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(surfaces.card)
            .border(1.dp, surfaces.cardBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(AccentBlue.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = user.displayName.take(2).uppercase().ifBlank { "DM" },
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = AccentBlue
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (user.isPrimaryOwner) {
                    RoleBadge("OWNER", AccentBlue)
                } else if (user.role == UserRole.ADMIN) {
                    RoleBadge("ADMIN", Color(0xFF9B51E0))
                }
                if (user.isDisabled) {
                    RoleBadge("SUSPENDED", Color(0xFFEB5757))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = user.email.ifBlank { user.uid },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            user.lastLoginAtMillis?.let { lastSeen ->
                Text(
                    text = "Last login: ${formatTimestamp(lastSeen)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Tab 3: Global Task Explorer & Task CRUD                                    */
/* -------------------------------------------------------------------------- */

@Composable
private fun AdminTasksTab(
    tasks: List<AdminTaskRecord>,
    isLoading: Boolean,
    searchQuery: String,
    selectedFilter: AdminTaskFilter,
    onSearchQueryChange: (String) -> Unit,
    onSelectFilter: (AdminTaskFilter) -> Unit,
    onToggleComplete: (AdminTaskRecord) -> Unit,
    onEditTask: (AdminTaskRecord) -> Unit,
    onDeleteTask: (AdminTaskRecord) -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search task title, owner email, or project...") },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AdminTaskFilter.entries.forEach { filter ->
                val active = filter == selectedFilter
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (active) surfaces.accentSurface else surfaces.card)
                        .border(1.dp, surfaces.cardBorder, RoundedCornerShape(100.dp))
                        .clickable { onSelectFilter(filter) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = filter.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (active) surfaces.onAccentSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (isLoading && tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentBlue)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (tasks.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No tasks match the current filter",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(tasks, key = { "${it.ownerUid}:${it.taskId}" }) { task ->
                        AdminTaskCard(
                            task = task,
                            onToggleComplete = { onToggleComplete(task) },
                            onEdit = { onEditTask(task) },
                            onDelete = { onDeleteTask(task) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminTaskCard(
    task: AdminTaskRecord,
    onToggleComplete: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    val prioColor = when (task.priority) {
        Priority.URGENT -> Color(0xFFEB5757)
        Priority.HIGH -> Color(0xFFF2994A)
        Priority.MEDIUM -> AccentBlue
        Priority.LOW -> Color(0xFF6FCF97)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(surfaces.card)
            .border(1.dp, surfaces.cardBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        IconButton(
            onClick = onToggleComplete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (task.isCompleted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (task.isCompleted) "Reopen task" else "Complete task",
                tint = if (task.isCompleted) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(6.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                ),
                color = if (task.isCompleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoleBadge(task.priority.name, prioColor)
                if (task.categoryName.isNotBlank()) {
                    RoleBadge(task.categoryName.uppercase(), Color(0xFF9B51E0))
                }
                if (task.isRecurring) {
                    RoleBadge("HABIT · ${task.streak}D", Color(0xFF27AE60))
                }
                if (task.subTasksTotal > 0) {
                    RoleBadge("${task.subTasksDone}/${task.subTasksTotal}", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val ownerLine = buildString {
                append(task.ownerEmail.ifBlank { task.ownerName })
                task.dueAtMillis?.let { due ->
                    append(" · Due ")
                    append(formatShortDate(due))
                }
            }
            Text(
                text = ownerLine,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Edit task",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete task",
                tint = Color(0xFFEB5757),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun RoleBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            ),
            color = color
        )
    }
}

/* -------------------------------------------------------------------------- */
/* Tab 4: AI & Runtime Config                                                 */
/* -------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminAiConfigTab(
    draft: AdminSystemConfig,
    savedConfig: AdminSystemConfig,
    hasUnsavedChanges: Boolean,
    isSaving: Boolean,
    geminiApiKey: String,
    onSaveGeminiApiKey: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    onUpdateDraft: ((AdminSystemConfig) -> AdminSystemConfig) -> Unit,
    onResetDraft: () -> Unit,
    onSave: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showApiKeySheet by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }

    val isDebugBuild = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    val firebaseProjectId = remember(context) { SabihonApp.firebaseProjectId(context) }
    var debugAppCheckToken by remember(context) {
        mutableStateOf(if (isDebugBuild) SabihonApp.readAppCheckDebugToken(context) else null)
    }

    LaunchedEffect(isDebugBuild) {
        if (isDebugBuild && debugAppCheckToken == null) {
            delay(600L)
            debugAppCheckToken = SabihonApp.readAppCheckDebugToken(context)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                SectionLabel("CREDENTIALS & APP CHECK")
                GroupedCard {
                    val configuredKey = geminiApiKey.trim()
                    val keyStatus = when {
                        configuredKey.length > 8 -> "${configuredKey.take(4)}…${configuredKey.takeLast(4)}"
                        configuredKey.isNotEmpty() -> "Saved"
                        else -> "Set key"
                    }
                    GroupedRow(
                        label = "Gemini API key",
                        supporting = "Free at aistudio.google.com/apikey — no Blaze plan needed",
                        value = keyStatus,
                        onClick = {
                            apiKeyDraft = geminiApiKey
                            apiKeyVisible = false
                            showApiKeySheet = true
                        }
                    )
                    if (isDebugBuild) {
                        GroupedDivider()
                        val shortToken = debugAppCheckToken?.let { token ->
                            if (token.length > 12) "${token.take(8)}…${token.takeLast(4)}" else token
                        } ?: "Copy"
                        val projectLine = firebaseProjectId?.let { "Project: $it · " }.orEmpty()
                        GroupedRow(
                            label = "App Check debug token",
                            supporting = "${projectLine}Tap to copy for Firebase Console > App Check",
                            value = shortToken,
                            onClick = {
                                scope.launch {
                                    val token = SabihonApp.readAppCheckDebugToken(context)
                                        ?: run {
                                            delay(500L)
                                            SabihonApp.readAppCheckDebugToken(context)
                                        }
                                    debugAppCheckToken = token
                                    if (!token.isNullOrBlank()) {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("App Check Debug Token", token))
                                        onShowMessage("Copied App Check debug token ($token)")
                                    } else {
                                        onShowMessage("Generating debug token — tap again in a second")
                                    }
                                }
                            }
                        )
                    } else {
                        GroupedDivider()
                        val projectLine = firebaseProjectId?.let { "Project: $it · " }.orEmpty()
                        GroupedRow(
                            label = "App Check provider",
                            supporting = "${projectLine}Release Play Integrity attestation active",
                            value = "Enforced",
                            onClick = null
                        )
                    }
                }
            }
        }

        item {
            GroupedCard {
                ConfigToggleRow(
                    title = "Cloud Gemini AI (Master Switch)",
                    subtitle = "Disable to force instant offline Rung-0 heuristics for all users",
                    checked = draft.cloudAiGlobalEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateDraft { it.copy(cloudAiGlobalEnabled = enabled) }
                    }
                )
                GroupedDivider()
                ConfigToggleRow(
                    title = "Task Prioritization",
                    subtitle = "Allow AI focus ranking and priority proposals on Home",
                    checked = draft.prioritizeEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateDraft { it.copy(prioritizeEnabled = enabled) }
                    }
                )
                GroupedDivider()
                ConfigToggleRow(
                    title = "Contextual Task Suggestions",
                    subtitle = "Allow next-task and sub-task step suggestions across projects",
                    checked = draft.suggestionsEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateDraft { it.copy(suggestionsEnabled = enabled) }
                    }
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(surfaces.card)
                    .border(1.dp, surfaces.cardBorder, RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Daily Cloud AI Quota per User",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Dynamically enforced by AiQuotaGuard before switching accounts to local heuristics.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QUOTA_PRESETS.forEach { preset ->
                        val selected = draft.dailyAiQuotaPerUser == preset
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) AccentBlue else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (selected) AccentBlue else surfaces.cardBorder,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    onUpdateDraft { it.copy(dailyAiQuotaPerUser = preset) }
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$preset/d",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(surfaces.card)
                    .border(1.dp, surfaces.cardBorder, RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ConfigToggleRow(
                    title = "Maintenance Notice Mode",
                    subtitle = "Renders the announcement banner in high-attention amber on Home",
                    checked = draft.maintenanceMode,
                    onCheckedChange = { enabled ->
                        onUpdateDraft { it.copy(maintenanceMode = enabled) }
                    },
                    padded = false
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PresetChip("Maintenance") {
                        onUpdateDraft {
                            it.copy(
                                maintenanceMode = true,
                                announcementBanner = "Scheduled maintenance tonight at 11:00 PM SGT — offline sync stays active."
                            )
                        }
                    }
                    PresetChip("AI Update") {
                        onUpdateDraft {
                            it.copy(
                                maintenanceMode = false,
                                announcementBanner = "Smart Gemini 2.5 prioritization & step suggestions are now live."
                            )
                        }
                    }
                    PresetChip("Clear Banner") {
                        onUpdateDraft {
                            it.copy(maintenanceMode = false, announcementBanner = "")
                        }
                    }
                }

                OutlinedTextField(
                    value = draft.announcementBanner,
                    onValueChange = { text ->
                        onUpdateDraft { it.copy(announcementBanner = text.take(180)) }
                    },
                    label = { Text("Global announcement banner") },
                    placeholder = { Text("Broadcast a live message to all Home screens...") },
                    maxLines = 3,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (draft.announcementBanner.isNotBlank()) {
                    val previewAccent = if (draft.maintenanceMode) Color(0xFFF2994A) else AccentBlue
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "LIVE HOME PREVIEW",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(previewAccent.copy(alpha = 0.12f))
                                .border(1.dp, previewAccent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = draft.announcementBanner,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (savedConfig.updatedByEmail.isNotBlank()) {
                    val ts = savedConfig.updatedAtMillis?.let { formatTimestamp(it) }.orEmpty()
                    Text(
                        text = "Last saved by ${savedConfig.updatedByEmail} $ts",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (hasUnsavedChanges) {
                        OutlinedButton(
                            onClick = onResetDraft,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp)
                        ) {
                            Text("Reset")
                        }
                    }
                    Button(
                        onClick = onSave,
                        enabled = hasUnsavedChanges && !isSaving,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentBlue,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Text("Save Configuration", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    if (showApiKeySheet) {
        ModalBottomSheet(
            onDismissRequest = { showApiKeySheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = DaymarkSheetShape,
            containerColor = surfaces.card,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 28.dp)
            ) {
                Text(
                    "Free Gemini API key",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Skip the Firebase Blaze upgrade: create a free key at aistudio.google.com/apikey (no credit card required) and paste it below. Stored locally on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    placeholder = { Text("AIzaSy...") },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector = if (apiKeyVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (apiKeyVisible) {
                                    "Hide API key"
                                } else {
                                    "Show API key"
                                },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        onSaveGeminiApiKey(apiKeyDraft)
                        showApiKeySheet = false
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(
                        text = if (apiKeyDraft.isBlank()) "Clear key" else "Save Gemini key",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = { showApiKeySheet = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    val surfaces = LocalDaymarkSurfaces.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(surfaces.accentSurface)
            .border(1.dp, surfaces.cardBorder, RoundedCornerShape(100.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = surfaces.onAccentSurface
        )
    }
}

@Composable
private fun ConfigToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    padded: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (padded) Modifier.padding(horizontal = 16.dp, vertical = 12.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue)
        )
    }
}

/* -------------------------------------------------------------------------- */
/* Tab 5: Audit Trail, Data Export & Security                                 */
/* -------------------------------------------------------------------------- */

@Composable
private fun AdminAuditSecurityTab(
    adminEmail: String,
    adminUid: String,
    sessionMode: AdminSessionMode,
    isLinkingGoogle: Boolean,
    auditLogs: List<AdminAuditEntry>,
    selectedFilter: AdminAuditFilter,
    searchQuery: String,
    onSelectFilter: (AdminAuditFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onGoogleIdToken: (String) -> Unit,
    onGoogleError: (String) -> Unit,
    onOpenExportSheet: () -> Unit,
    onOpenPasswordSheet: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onSignOut: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(surfaces.card)
                    .border(1.dp, surfaces.cardBorder, RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Admin Session & Credentials",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                StatLine("Authenticated email", adminEmail.ifBlank { "—" })
                StatLine("Session UID", adminUid.take(20).ifBlank { "—" })
                StatLine(
                    "Auth mode",
                    if (sessionMode == AdminSessionMode.FIREBASE_NATIVE) {
                        "Firebase Native Token"
                    } else {
                        "Hybrid Verified Owner"
                    }
                )
                StatLine(
                    "Privilege tier",
                    if (AdminAccessPolicy.isPrimaryAdminEmail(adminEmail)) "Primary Owner" else "Delegated Admin"
                )

                if (sessionMode == AdminSessionMode.HYBRID_OWNER_SESSION) {
                    GoogleAuthButton(
                        onIdTokenReceived = onGoogleIdToken,
                        onError = onGoogleError,
                        isLoading = isLinkingGoogle,
                        buttonText = "Connect Google Firebase Token"
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                ActionButtonRow(
                    icon = Icons.Filled.Download,
                    label = "Export Platform Data (CSV / JSON)",
                    tint = AccentBlue,
                    onClick = onOpenExportSheet
                )
                ActionButtonRow(
                    icon = Icons.Filled.Lock,
                    label = "Change Admin Password",
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onOpenPasswordSheet
                )
                ActionButtonRow(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    label = "Switch to Personal Task Workspace",
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onOpenWorkspace
                )
                ActionButtonRow(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    label = "Sign Out of Admin Session",
                    tint = Color(0xFFEB5757),
                    onClick = onSignOut
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Immutable Audit Log (${auditLogs.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Filter audit events...") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AdminAuditFilter.entries.forEach { filter ->
                        val active = filter == selectedFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .background(if (active) surfaces.accentSurface else surfaces.card)
                                .border(1.dp, surfaces.cardBorder, RoundedCornerShape(100.dp))
                                .clickable { onSelectFilter(filter) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = filter.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (active) surfaces.onAccentSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (auditLogs.isEmpty()) {
            item {
                Text(
                    text = "No matching administrative events recorded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(auditLogs, key = { it.id }) { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(surfaces.card)
                        .border(1.dp, surfaces.cardBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoleBadge(entry.action, AccentBlue)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = formatTimestamp(entry.createdAtMillis),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = entry.summary,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Actor: ${entry.actorEmail.ifBlank { entry.actorUid }} · Target: ${entry.targetId.take(24)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButtonRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = tint
        )
    }
}

/* -------------------------------------------------------------------------- */
/* Bottom Sheets: User Inspector, Task Editor, Data Export, Password Rotation */
/* -------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserInspectorSheet(
    detail: AdminUserDetail,
    actorUid: String,
    isLoadingCounts: Boolean,
    onSaveDisplayName: (String) -> Unit,
    onSendPasswordReset: () -> Unit,
    onToggleAdminRole: () -> Unit,
    onToggleSuspend: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    val user = detail.user
    val canEditRole = AdminAccessPolicy.canModifyUserRole(actorUid, user.uid, user.email)
    val canSuspend = AdminAccessPolicy.canSuspendUser(actorUid, user.uid, user.email)
    val canDelete = AdminAccessPolicy.canDeleteUser(actorUid, user.uid, user.email)
    var nameDraft by remember(user.uid, user.displayName) { mutableStateOf(user.displayName) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = DaymarkSheetShape,
        containerColor = surfaces.card,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${user.email.ifBlank { "No email" }} · UID: ${user.uid.take(14)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { onSaveDisplayName(nameDraft) },
                    enabled = nameDraft.trim().isNotBlank() && nameDraft.trim() != user.displayName,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "Save name", modifier = Modifier.size(16.dp))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            if (isLoadingCounts) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading workspace activity...", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                StatLine("Open / completed tasks", "${detail.openTaskCount} / ${detail.completedTaskCount}")
                StatLine("Projects / categories", "${detail.categoryCount}")
                StatLine("Best habit streak", "${detail.activeStreakMax} days")
                StatLine("Theme preference", user.themePref)
            }

            StatLine("Account role", if (user.isPrimaryOwner) "PRIMARY OWNER" else user.role.name)
            StatLine("Status", if (user.isDisabled) "Suspended" else "Active")

            if (detail.recentTasks.isNotEmpty()) {
                Text(
                    text = "Recent Tasks (${detail.recentTasks.size})",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                detail.recentTasks.take(3).forEach { t ->
                    Text(
                        text = "${if (t.isCompleted) "✓" else "○"} ${t.title} [${t.priority.name}]",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (user.email.isNotBlank()) {
                OutlinedButton(
                    onClick = onSendPasswordReset,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send Password Reset Email")
                }
            }

            if (!canEditRole && !canSuspend && !canDelete) {
                Text(
                    text = if (user.isPrimaryOwner) {
                        "Primary owner account is protected from role demotion, suspension, and deletion."
                    } else {
                        "Your active admin session cannot demote, suspend, or delete itself."
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (canEditRole) {
                        OutlinedButton(
                            onClick = onToggleAdminRole,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                        ) {
                            Text(if (user.role == UserRole.ADMIN) "Revoke Admin" else "Make Admin")
                        }
                    }
                    if (canSuspend) {
                        Button(
                            onClick = onToggleSuspend,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (user.isDisabled) Color(0xFF34C759) else Color(0xFFF2994A),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                        ) {
                            Icon(
                                imageVector = if (user.isDisabled) Icons.Filled.CheckCircle else Icons.Filled.Block,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (user.isDisabled) "Reactivate" else "Suspend")
                        }
                    }
                }
                if (canDelete) {
                    OutlinedButton(
                        onClick = onRequestDelete,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = Color(0xFFEB5757),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Delete User Workspace", color = Color(0xFFEB5757))
                    }
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminTaskEditSheet(
    task: AdminTaskRecord,
    isSaving: Boolean,
    onSave: (String, String, Priority) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    var titleDraft by remember(task.taskId) { mutableStateOf(task.title) }
    var descDraft by remember(task.taskId) { mutableStateOf(task.description) }
    var prioDraft by remember(task.taskId) { mutableStateOf(task.priority) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = DaymarkSheetShape,
        containerColor = surfaces.card,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Edit Platform Task",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Owner: ${task.ownerEmail.ifBlank { task.ownerUid }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = titleDraft,
                onValueChange = { titleDraft = it },
                label = { Text("Task title") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = descDraft,
                onValueChange = { descDraft = it },
                label = { Text("Notes / description") },
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Priority",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Priority.entries.forEach { p ->
                    val selected = prioDraft == p
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) AccentBlue else Color.Transparent)
                            .border(
                                1.dp,
                                if (selected) AccentBlue else surfaces.cardBorder,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { prioDraft = p }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = p.name,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                ) {
                    Text("Delete", color = Color(0xFFEB5757))
                }
                Button(
                    onClick = { onSave(titleDraft, descDraft, prioDraft) },
                    enabled = titleDraft.trim().isNotBlank() && !isSaving,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                ) {
                    Text(if (isSaving) "Saving..." else "Save Changes", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportDataSheet(
    isExporting: Boolean,
    readyPayload: AdminExportPayload?,
    onGenerate: (AdminExportType) -> Unit,
    onSharePayload: (AdminExportPayload) -> Unit,
    onCopyPayload: (AdminExportPayload) -> Unit,
    onDismiss: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = DaymarkSheetShape,
        containerColor = surfaces.card,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Export Platform Data",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Generate RFC-4180 CSV or JSON snapshots for reporting and backups.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AdminExportType.entries.forEach { exportType ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, surfaces.cardBorder, RoundedCornerShape(16.dp))
                        .clickable(enabled = !isExporting) { onGenerate(exportType) }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = exportType.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = exportType.subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isExporting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Building export snapshot...", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (readyPayload != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(
                    text = "Ready: ${readyPayload.fileName} (${readyPayload.rowCount} records)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = AccentBlue
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onCopyPayload(readyPayload) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                    Button(
                        onClick = { onSharePayload(readyPayload) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentBlue,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share File")
                    }
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangeAdminPasswordSheet(
    isSaving: Boolean,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    var currentPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var showCurrent by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = DaymarkSheetShape,
        containerColor = surfaces.card,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Change Admin Password",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Updates or links your Firebase Authentication password directly without storing secrets on disk.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = currentPw,
                onValueChange = { currentPw = it },
                label = { Text("Current password (optional if Google-linked)") },
                singleLine = true,
                visualTransformation = if (showCurrent) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showCurrent = !showCurrent }) {
                        Icon(
                            imageVector = if (showCurrent) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showCurrent) "Hide password" else "Show password"
                        )
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = newPw,
                onValueChange = { newPw = it },
                label = { Text("New password (min 6 characters)") },
                singleLine = true,
                visualTransformation = if (showNew) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showNew = !showNew }) {
                        Icon(
                            imageVector = if (showNew) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showNew) "Hide password" else "Show password"
                        )
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { onSave(currentPw, newPw) },
                enabled = newPw.length >= 6 && !isSaving,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(if (isSaving) "Updating..." else "Update Password", fontWeight = FontWeight.SemiBold)
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(epochMillis))

private fun formatShortDate(epochMillis: Long): String =
    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis))
