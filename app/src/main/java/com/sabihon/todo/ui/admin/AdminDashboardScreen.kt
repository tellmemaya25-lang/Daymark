package com.sabihon.todo.ui.admin

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.ui.components.GroupedCard
import com.sabihon.todo.core.ui.components.GroupedDivider
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.DaymarkSheetShape
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.domain.admin.AdminAccessPolicy
import com.sabihon.todo.domain.admin.AdminAiDailyStats
import com.sabihon.todo.domain.admin.AdminAuditEntry
import com.sabihon.todo.domain.admin.AdminPlatformMetrics
import com.sabihon.todo.domain.admin.AdminSystemConfig
import com.sabihon.todo.domain.admin.AdminUserDetail
import com.sabihon.todo.domain.admin.AdminUserSummary
import com.sabihon.todo.domain.admin.DailyTaskBucket
import com.sabihon.todo.domain.admin.UserRole
import com.sabihon.todo.domain.model.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUOTA_PRESETS = listOf(25, 50, 100, 250)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    onOpenWorkspace: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: AdminDashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

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
                    onRefresh = { viewModel.refreshAll() },
                    onOpenWorkspace = onOpenWorkspace
                )

                AdminTabStrip(
                    selectedTab = uiState.selectedTab,
                    onSelectTab = viewModel::selectTab
                )

                when (uiState.selectedTab) {
                    AdminTab.OVERVIEW -> AdminOverviewTab(
                        metrics = uiState.metrics,
                        aiStats = uiState.todayAiStats,
                        isLoading = uiState.isLoadingMetrics
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
                    AdminTab.AI_CONFIG -> AdminAiConfigTab(
                        draft = uiState.configDraft,
                        savedConfig = uiState.systemConfig,
                        hasUnsavedChanges = uiState.hasUnsavedConfigChanges,
                        isSaving = uiState.isSavingConfig,
                        onUpdateDraft = viewModel::updateConfigDraft,
                        onResetDraft = viewModel::resetConfigDraft,
                        onSave = viewModel::saveSystemConfig
                    )
                    AdminTab.AUDIT_SECURITY -> AdminAuditSecurityTab(
                        adminEmail = uiState.currentAdminEmail,
                        adminUid = uiState.currentAdminUid,
                        auditLogs = uiState.auditLogs,
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
            onToggleAdminRole = { viewModel.toggleUserAdminRole(inspectedDetail.user) },
            onToggleSuspend = { viewModel.toggleUserSuspension(inspectedDetail.user) },
            onDismiss = viewModel::dismissUserInspector
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

@Composable
private fun AdminTopHeader(
    adminEmail: String,
    onRefresh: () -> Unit,
    onOpenWorkspace: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp)
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
                onClick = onRefresh,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh metrics",
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

        Spacer(Modifier.height(10.dp))

        Text(
            text = "daymark . control",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
                letterSpacing = (-0.6).sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )

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
                AdminTab.AI_CONFIG -> Icons.Filled.Tune
                AdminTab.AUDIT_SECURITY -> Icons.Filled.Security
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
                    .padding(horizontal = 14.dp, vertical = 8.dp),
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
                    text = tab.label,
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
/* Tab 1: Overview                                                            */
/* -------------------------------------------------------------------------- */

@Composable
private fun AdminOverviewTab(
    metrics: AdminPlatformMetrics,
    aiStats: AdminAiDailyStats,
    isLoading: Boolean
) {
    val surfaces = LocalDaymarkSurfaces.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (metrics.permissionLimited && !metrics.diagnosticHint.isNullOrBlank()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF2994A).copy(alpha = 0.14f))
                        .border(1.dp, Color(0xFFF2994A).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
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
            }
        }

        if (isLoading && metrics.totalUsers == 0 && metrics.totalTasks == 0) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentBlue)
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AdminKpiTile(
                    title = "Total Users",
                    value = "${metrics.totalUsers}",
                    subtitle = "+${metrics.newUsersLast7Days} in 7d · ${metrics.activeAdmins} admin",
                    accent = AccentBlue,
                    modifier = Modifier.weight(1f)
                )
                AdminKpiTile(
                    title = "Total Tasks",
                    value = "${metrics.totalTasks}",
                    subtitle = "${metrics.openTasks} open · ${metrics.completedTasks} done",
                    accent = Color(0xFF34C759),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AdminKpiTile(
                    title = "Completion Rate",
                    value = "${metrics.completionRatePercent}%",
                    subtitle = "${metrics.overdueOpenTasks} overdue · ${metrics.activeHabitTasks} habits",
                    accent = Color(0xFF9B51E0),
                    modifier = Modifier.weight(1f)
                )
                AdminKpiTile(
                    title = "AI Calls Today",
                    value = "${aiStats.totalCalls}",
                    subtitle = "${aiStats.cloudHitRatePercent}% cloud · ${aiStats.averageLatencyMs} ms",
                    accent = Color(0xFFF2994A),
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
                        text = "AI Pipeline Health (${aiStats.dateKey.ifBlank { "Today" }})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    StatLine("Cloud Gemini 2.5 responses", "${aiStats.cloudSuccessCount}")
                    StatLine("Rung-0 deterministic fallbacks", "${aiStats.localFallbackCount}")
                    StatLine("Errors / timeouts", "${aiStats.errorCount}")
                    StatLine("Suggestions accepted / dismissed", "${aiStats.acceptedOutcomes} / ${aiStats.dismissedOutcomes}")
                    StatLine("Total projects / categories", "${metrics.totalCategories}")
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
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
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
            .height(110.dp),
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
                    modifier = Modifier.height(82.dp),
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
                .size(40.dp)
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
/* Tab 3: AI & Runtime Config                                                 */
/* -------------------------------------------------------------------------- */

@Composable
private fun AdminAiConfigTab(
    draft: AdminSystemConfig,
    savedConfig: AdminSystemConfig,
    hasUnsavedChanges: Boolean,
    isSaving: Boolean,
    onUpdateDraft: ((AdminSystemConfig) -> AdminSystemConfig) -> Unit,
    onResetDraft: () -> Unit,
    onSave: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
                    subtitle = "Allow next-task and sub-task suggestions across projects",
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
                    text = "Caps daily Gemini invocations per account before falling back to local rules.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
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
                    subtitle = "Highlights the announcement banner across user workspaces",
                    checked = draft.maintenanceMode,
                    onCheckedChange = { enabled ->
                        onUpdateDraft { it.copy(maintenanceMode = enabled) }
                    },
                    padded = false
                )

                OutlinedTextField(
                    value = draft.announcementBanner,
                    onValueChange = { text ->
                        onUpdateDraft { it.copy(announcementBanner = text.take(180)) }
                    },
                    label = { Text("Global announcement banner (optional)") },
                    placeholder = { Text("e.g. Scheduled maintenance tonight at 11 PM SGT") },
                    maxLines = 3,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

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
/* Tab 4: Audit Log & Security                                                */
/* -------------------------------------------------------------------------- */

@Composable
private fun AdminAuditSecurityTab(
    adminEmail: String,
    adminUid: String,
    auditLogs: List<AdminAuditEntry>,
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
                StatLine("Firebase UID", adminUid.take(16).ifBlank { "—" })
                StatLine(
                    "Privilege tier",
                    if (AdminAccessPolicy.isPrimaryAdminEmail(adminEmail)) "Primary Owner" else "Delegated Admin"
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                ActionButtonRow(
                    icon = Icons.Filled.Lock,
                    label = "Change Admin Password",
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onOpenPasswordSheet
                )
                ActionButtonRow(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    label = "Switch to Personal Task Workspace",
                    tint = AccentBlue,
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
        }

        if (auditLogs.isEmpty()) {
            item {
                Text(
                    text = "No administrative actions recorded yet.",
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
                        text = "Actor: ${entry.actorEmail.ifBlank { entry.actorUid }}",
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
/* Bottom Sheets: User Inspector & Password Rotation                          */
/* -------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserInspectorSheet(
    detail: AdminUserDetail,
    actorUid: String,
    isLoadingCounts: Boolean,
    onToggleAdminRole: () -> Unit,
    onToggleSuspend: () -> Unit,
    onDismiss: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    val user = detail.user
    val canEditRole = AdminAccessPolicy.canModifyUserRole(actorUid, user.uid, user.email)
    val canSuspend = AdminAccessPolicy.canSuspendUser(actorUid, user.uid, user.email)

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
                text = user.displayName,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = user.email.ifBlank { user.uid },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            if (isLoadingCounts) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading workspace activity...", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                StatLine("Open tasks", "${detail.openTaskCount}")
                StatLine("Completed tasks", "${detail.completedTaskCount}")
                StatLine("Projects / categories", "${detail.categoryCount}")
                StatLine("Best habit streak", "${detail.activeStreakMax} days")
            }

            StatLine("Account role", if (user.isPrimaryOwner) "PRIMARY OWNER" else user.role.name)
            StatLine("Status", if (user.isDisabled) "Suspended" else "Active")

            if (!canEditRole && !canSuspend) {
                Text(
                    text = if (user.isPrimaryOwner) {
                        "Primary owner account is protected from role demotion and suspension."
                    } else {
                        "Your active admin session cannot demote or suspend itself."
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
                                .heightIn(min = 46.dp)
                        ) {
                            Text(if (user.role == UserRole.ADMIN) "Revoke Admin" else "Make Admin")
                        }
                    }
                    if (canSuspend) {
                        Button(
                            onClick = onToggleSuspend,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (user.isDisabled) Color(0xFF34C759) else Color(0xFFEB5757),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp)
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
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
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
                text = "Updates your Firebase Authentication password immediately without storing credentials on the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = currentPw,
                onValueChange = { currentPw = it },
                label = { Text("Current password (optional if just signed in)") },
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
