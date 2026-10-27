package com.sabihon.todo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.core.ui.components.AiConsentSheet
import com.sabihon.todo.core.ui.components.BrandHeader
import com.sabihon.todo.core.ui.components.CompletionSplash
import com.sabihon.todo.core.ui.components.FilterEmptyState
import com.sabihon.todo.core.ui.components.LoadingShimmer
import com.sabihon.todo.core.ui.components.MomentumCard
import com.sabihon.todo.core.ui.components.RevealCoordinator
import com.sabihon.todo.core.ui.components.NotelyEmptyState
import com.sabihon.todo.core.ui.components.SegmentedTabs
import com.sabihon.todo.core.ui.components.SwipeableTaskRow
import com.sabihon.todo.core.ui.components.TabItem
import com.sabihon.todo.core.ui.components.WeekStrip
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.core.ui.util.isCompactHeight
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.Task
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAllTasks: () -> Unit = {},
    onNavigateToCategories: () -> Unit = {},
    onNavigateToTaskDetail: (String) -> Unit = {},
    onNavigateToEditTask: (String) -> Unit = {},
    onNavigateToAddTask: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToCategory: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    var showActionSheet by remember { mutableStateOf(false) }
    var showAddSubTaskSheet by remember { mutableStateOf(false) }
    var taskForNewSubTask by remember { mutableStateOf<Task?>(null) }
    var newSubTaskTitle by remember { mutableStateOf("") }
    var splashTaskTitle by remember { mutableStateOf<String?>(null) }
    // Only one revealed task card at a time on this screen.
    val revealCoordinator = remember { RevealCoordinator() }
    var completedExpanded by remember { mutableStateOf(true) }
    var showSortMenu by remember { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState()
    val addSheetState = rememberModalBottomSheetState()

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            viewModel.onAction(HomeAction.ClearError)
        }
    }

    LaunchedEffect(uiState.habitNextLabel) {
        val label = uiState.habitNextLabel
        if (label != null) {
            snackbarHostState.showSnackbar(message = label, duration = SnackbarDuration.Short)
            viewModel.onAction(HomeAction.ClearHabitLabel)
        }
    }

    LaunchedEffect(uiState.showUndo) {
        if (uiState.showUndo) {
            val result = snackbarHostState.showSnackbar(
                message = "Task deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.onAction(HomeAction.UndoDelete)
            } else {
                viewModel.dismissUndo()
            }
        }
    }

    LaunchedEffect(uiState.showPriorityUndo) {
        if (uiState.showPriorityUndo) {
            val count = uiState.priorityUndoCount
            val label = if (count == 1) "Updated 1 task priority" else "Updated $count task priorities"
            val result = snackbarHostState.showSnackbar(
                message = label,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.onAction(HomeAction.UndoPriorityPlan)
            } else {
                viewModel.dismissPriorityUndo()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (uiState.isLoading) {
            LoadingShimmer(modifier = Modifier.padding(innerPadding))
        } else {
            val listState = rememberLazyListState()
            // Landscape (or any short window) cannot spare ~165 dp of pinned header,
            // so there the header becomes the first list item and scrolls away.
            val compactHeader = isCompactHeight()
            LaunchedEffect(compactHeader) {
                // Inserting the header moves every item by one, and listState survives
                // the rotation; start both layouts from the top rather than mid-list.
                listState.scrollToItem(0)
            }
            // Header stays put; a hairline fades in as soon as the list moves under it.
            // In compact mode the header is an item, so index > 0 is true immediately
            // and the divider would fire before the user has scrolled at all.
            val isScrolled by remember {
                derivedStateOf {
                    !compactHeader &&
                        (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
                }
            }
            LaunchedEffect(listState.isScrollInProgress) {
                if (listState.isScrollInProgress) revealCoordinator.closeAll()
            }
            val headerDividerAlpha by animateFloatAsState(
                targetValue = if (isScrolled) 1f else 0f,
                animationSpec = tween(180),
                label = "headerDivider"
            )

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!compactHeader) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        HomeHeader(
                            greetingName = uiState.greetingName,
                            avatarInitials = uiState.avatarInitials,
                            avatarUrl = uiState.avatarUrl,
                            onSearchClick = onNavigateToSearch,
                            onAvatarClick = onNavigateToProfile
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = 0.10f * headerDividerAlpha
                                    )
                                )
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                if (compactHeader) {
                    item {
                        HomeHeaderCompact(
                            avatarInitials = uiState.avatarInitials,
                            avatarUrl = uiState.avatarUrl,
                            onSearchClick = onNavigateToSearch,
                            onAvatarClick = onNavigateToProfile,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }

                item {
                    MomentumCard(
                        total = uiState.momentum.total,
                        done = uiState.momentum.done,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                }

                item {
                    WeekStrip(
                        days = uiState.weekDays,
                        selected = uiState.selectedDate,
                        onSelect = { date -> viewModel.onAction(HomeAction.SelectDate(date)) }
                    )
                }

                item {
                    SegmentedTabs(
                        tabs = listOf(
                            // When another day is picked the badge follows that day, not today's count.
                            TabItem(
                                "today",
                                "Today",
                                if (uiState.selectedDate == java.time.LocalDate.now()) {
                                    uiState.counts.today
                                } else {
                                    uiState.visibleTasks.size + uiState.completedTasks.size
                                }
                            ),
                            TabItem("upcoming", "Upcoming", null),
                            TabItem("all", "All tasks", uiState.counts.all)
                        ),
                        selectedId = when (uiState.selectedFilter) {
                            HomeFilter.TODAY -> "today"
                            HomeFilter.UPCOMING -> "upcoming"
                            HomeFilter.ALL -> "all"
                        },
                        onSelect = { id ->
                            viewModel.onAction(
                                HomeAction.SetFilter(
                                    when (id) {
                                        "today" -> HomeFilter.TODAY
                                        "upcoming" -> HomeFilter.UPCOMING
                                        else -> HomeFilter.ALL
                                    }
                                )
                            )
                        }
                    )
                }

                item {
                    ListHeader(
                        count = uiState.visibleTasks.size,
                        dateHint = if (uiState.selectedDate == java.time.LocalDate.now()) {
                            null
                        } else {
                            formatLocalDate(uiState.selectedDate, "EEE d MMM")
                        },
                        sortLabel = "${uiState.selectedSort.shortLabel} ${uiState.sortDirection.arrow}",
                        sort = uiState.selectedSort,
                        direction = uiState.sortDirection,
                        showSortMenu = showSortMenu,
                        onSortMenuChange = { showSortMenu = it },
                        onSortSelected = { sort -> viewModel.onAction(HomeAction.SetSort(sort)) },
                        onDirectionSelected = { dir -> viewModel.onAction(HomeAction.SetSortDirection(dir)) },
                        onPrioritizeClick = if (uiState.visibleTasks.size >= 2) {
                            { viewModel.onAction(HomeAction.RequestPrioritize) }
                        } else {
                            null
                        }
                    )
                }

                if (uiState.visibleTasks.isEmpty() && uiState.completedTasks.isEmpty()) {
                    item {
                        when (uiState.selectedFilter) {
                            HomeFilter.TODAY -> FilterEmptyState(
                                filterName = if (uiState.selectedDate == java.time.LocalDate.now()) {
                                    "Today"
                                } else {
                                    formatLocalDate(uiState.selectedDate, "EEEE")
                                }
                            )
                            HomeFilter.UPCOMING -> FilterEmptyState(filterName = "Upcoming")
                            HomeFilter.ALL -> NotelyEmptyState(
                                title = "Get started with daymark .",
                                subtitle = "Add notes, calendar events, tasks files and more with the action bar"
                            )
                        }
                    }
                }

                itemsIndexed(uiState.visibleTasks, key = { _, task -> task.id }) { index, task ->
                    HomeTaskCard(
                        task = task,
                        categoryName = task.categoryId?.let { uiState.categoryNames[it] },
                        isHighlighted = index == 0,
                        viewModel = viewModel,
                        revealCoordinator = revealCoordinator,
                        onOpen = { onNavigateToTaskDetail(task.id) },
                        onEdit = {
                            if (onNavigateToEditTask != {}) onNavigateToEditTask(task.id) else onNavigateToTaskDetail(task.id)
                        },
                        onAddSubTaskRequest = { target ->
                            taskForNewSubTask = target
                            newSubTaskTitle = ""
                            showAddSubTaskSheet = true
                        },
                        onLongPress = { target ->
                            selectedTask = target
                            showActionSheet = true
                        },
                        onCompleted = { target -> splashTaskTitle = target.title }
                    )
                }

                if (uiState.completedTasks.isNotEmpty()) {
                    item {
                        CompletedHeader(
                            count = uiState.completedTasks.size,
                            expanded = completedExpanded,
                            onToggle = { completedExpanded = !completedExpanded }
                        )
                    }
                    if (completedExpanded) {
                        itemsIndexed(uiState.completedTasks, key = { _, task -> task.id }) { _, task ->
                            HomeTaskCard(
                                task = task,
                                categoryName = task.categoryId?.let { uiState.categoryNames[it] },
                                isHighlighted = false,
                                viewModel = viewModel,
                                revealCoordinator = revealCoordinator,
                                onOpen = { onNavigateToTaskDetail(task.id) },
                                onEdit = {
                                    if (onNavigateToEditTask != {}) onNavigateToEditTask(task.id) else onNavigateToTaskDetail(task.id)
                                },
                                onAddSubTaskRequest = { target ->
                                    taskForNewSubTask = target
                                    newSubTaskTitle = ""
                                    showAddSubTaskSheet = true
                                },
                                onLongPress = { target ->
                                    selectedTask = target
                                    showActionSheet = true
                                },
                                onCompleted = { target -> splashTaskTitle = target.title }
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(110.dp)) }
                }
            }
            }
        }

        // Bottom sheet – Edit / Mark as Done / Delete
        if (showActionSheet && selectedTask != null) {
            ModalBottomSheet(
                onDismissRequest = { showActionSheet = false },
                sheetState = bottomSheetState,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                dragHandle = null
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .background(
                                    Color.Gray.copy(alpha = 0.4f),
                                    RoundedCornerShape(100.dp)
                                )
                        )
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(3.dp)
                                .background(
                                    Color.Gray.copy(alpha = 0.2f),
                                    RoundedCornerShape(100.dp)
                                )
                        )
                    }

                    Text(
                        text = selectedTask?.title ?: "",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        maxLines = 2
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showActionSheet = false
                                selectedTask?.let {
                                    if (onNavigateToEditTask != {}) onNavigateToEditTask(it.id) else onNavigateToTaskDetail(it.id)
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text("Edit", style = MaterialTheme.typography.bodyLarge)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showActionSheet = false
                                selectedTask?.let { task ->
                                    viewModel.onAction(HomeAction.ToggleComplete(task.id, !task.isCompleted))
                                    // Same rule as the checkbox: same guard, same
                                    // reason. Mark as Done on a task with sub-tasks
                                    // left is rejected by the use case.
                                    if (!task.isCompleted && task.subTasksDone) {
                                        splashTaskTitle = task.title
                                    }
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (selectedTask?.isCompleted == true) "Mark as Pending" else "Mark as Done",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showActionSheet = false
                                selectedTask?.let { viewModel.onAction(HomeAction.DeleteTask(it)) }
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            "Delete",
                            color = Color(0xFFE57373),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(120.dp)
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                    RoundedCornerShape(100.dp)
                                )
                        )
                    }
                }
            }
        }

        // Bottom sheet – add sub-task
        if (showAddSubTaskSheet && taskForNewSubTask != null) {
            ModalBottomSheet(
                onDismissRequest = { showAddSubTaskSheet = false },
                sheetState = addSheetState,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                dragHandle = null
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .background(
                                    Color.Gray.copy(alpha = 0.4f),
                                    RoundedCornerShape(100.dp)
                                )
                        )
                    }

                    Text(
                        text = "Add new task",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )

                    Text(
                        text = "to ${taskForNewSubTask?.title ?: "Task"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = newSubTaskTitle,
                        onValueChange = { newSubTaskTitle = it },
                        label = { Text("Task name") },
                        placeholder = { Text("e.g. Start style guide for website") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            onClick = { showAddSubTaskSheet = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                if (newSubTaskTitle.isNotBlank()) {
                                    taskForNewSubTask?.let { task ->
                                        viewModel.onAction(
                                            HomeAction.AddSubTask(task.id, newSubTaskTitle.trim())
                                        )
                                    }
                                }
                                showAddSubTaskSheet = false
                                newSubTaskTitle = ""
                            },
                            enabled = newSubTaskTitle.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Add")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        if (uiState.showAiConsentSheet) {
            AiConsentSheet(
                onEnableCloudAi = { viewModel.onAction(HomeAction.DecideAiConsent(true)) },
                onUseOfflineRules = { viewModel.onAction(HomeAction.DecideAiConsent(false)) },
                onDismiss = { viewModel.onAction(HomeAction.DismissAiConsent) }
            )
        }

        val activePlan = uiState.prioritizationPlan
        if (uiState.showPrioritizeSheet && activePlan != null) {
            PrioritizeReviewSheet(
                plan = activePlan,
                tasksById = remember(uiState.allTasks) {
                    uiState.allTasks.associateBy { it.id }
                },
                categoryNames = uiState.categoryNames,
                selectedChanges = uiState.selectedPriorityChanges,
                isRefining = uiState.isPrioritizing,
                onToggleProposal = { taskId, checked ->
                    viewModel.onAction(HomeAction.TogglePriorityProposal(taskId, checked))
                },
                onRefresh = { viewModel.onAction(HomeAction.RefreshPrioritize) },
                onApply = { viewModel.onAction(HomeAction.ApplyPriorityPlan) },
                onDismiss = { viewModel.onAction(HomeAction.DismissPrioritizeSheet) }
            )
        }

        // Celebration – fires the moment a task is ticked off
        CompletionSplash(
            visible = splashTaskTitle != null,
            subtitle = splashTaskTitle,
            onFinished = { splashTaskTitle = null }
        )
    }
}

/* ------------------------------------------------------------------ */
/* Header                                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun HomeHeader(
    greetingName: String,
    avatarInitials: String,
    avatarUrl: String?,
    onSearchClick: () -> Unit,
    onAvatarClick: () -> Unit
) {
    val today = rememberTodayLabel()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
    ) {
        // Brand row: mark + wordmark left, avatar right (shared component)
        BrandHeader(
            avatarInitials = avatarInitials,
            avatarUrl = avatarUrl,
            onAvatarClick = onAvatarClick
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "A fresh start, $greetingName.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Make today count.",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 31.sp,
                lineHeight = 36.sp,
                letterSpacing = (-0.8).sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(10.dp))

        // Date line with the search button beside it
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = today,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            SearchCircleButton(onClick = onSearchClick)
        }
    }
}

/**
 * One-row header for landscape and other short windows.
 *
 * Portraits keep the greeting and the "Make today count." headline; here there is no
 * vertical room for either, so the row carries only what is still reachable
 * everywhere else: the brand, today's date, search, and the avatar.
 *
 * Reuses [BrandHeader]'s `actions` slot instead of rebuilding the lockup, so the mark
 * and wordmark cannot drift from the portrait version.
 */
@Composable
private fun HomeHeaderCompact(
    avatarInitials: String,
    avatarUrl: String?,
    onSearchClick: () -> Unit,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BrandHeader(
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        markSize = 22,
        fontSize = 17,
        avatarInitials = avatarInitials,
        avatarUrl = avatarUrl,
        onAvatarClick = onAvatarClick,
        actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rememberTodayLabel(),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                SearchCircleButton(onClick = onSearchClick, size = 32, iconSize = 16)
            }
        }
    )
}

/** Today's date line. Shared so the two header sizes can never disagree. */
@Composable
private fun rememberTodayLabel(): String = remember {
    SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
}

/**
 * The circular search affordance, in the two sizes the headers need.
 */
@Composable
private fun SearchCircleButton(
    onClick: () -> Unit,
    size: Int = 40,
    iconSize: Int = 18
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(size.dp)) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(iconSize.dp)
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* "ON YOUR LIST" header + sort menu                                   */
/* ------------------------------------------------------------------ */

@Composable
private fun ListHeader(
    count: Int,
    dateHint: String?,
    sortLabel: String,
    sort: HomeSort,
    direction: HomeSortDirection,
    showSortMenu: Boolean,
    onSortMenuChange: (Boolean) -> Unit,
    onSortSelected: (HomeSort) -> Unit,
    onDirectionSelected: (HomeSortDirection) -> Unit,
    onPrioritizeClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "ON YOUR LIST",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.4.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        if (dateHint != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = dateHint,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.weight(1f))

        if (onPrioritizeClick != null) {
            val surfaces = LocalDaymarkSurfaces.current
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(surfaces.accentSurface)
                    .clickable(onClick = onPrioritizeClick)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = "Prioritize tasks",
                    tint = AccentBlue,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Prioritize",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = surfaces.onAccentSurface
                )
            }
            Spacer(Modifier.width(6.dp))
        }

        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSortMenuChange(true) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = sortLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { onSortMenuChange(false) }
            ) {
                // Two sections rather than one flat list: what to sort by, and which
                // way. A checkmark says which of each is live, so the header label
                // ("Due date \u2191") and the menu can never disagree.
                SortSectionLabel("Sort by")
                HomeSort.entries.forEach { option ->
                    SortMenuItem(
                        label = option.label,
                        selected = option == sort,
                        onClick = {
                            onSortSelected(option)
                            onSortMenuChange(false)
                        }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )

                SortSectionLabel("Order")
                HomeSortDirection.entries.forEach { option ->
                    SortMenuItem(
                        label = option.label,
                        selected = option == direction,
                        onClick = {
                            onDirectionSelected(option)
                            onSortMenuChange(false)
                        }
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Completed section                                                   */
/* ------------------------------------------------------------------ */

@Composable
private fun CompletedHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Completed",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse completed" else "Expand completed",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/* ------------------------------------------------------------------ */
/* Task card                                                           */
/* ------------------------------------------------------------------ */

@Composable
private fun HomeTaskCard(
    task: Task,
    categoryName: String?,
    isHighlighted: Boolean,
    viewModel: HomeViewModel,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onAddSubTaskRequest: (Task) -> Unit,
    onLongPress: (Task) -> Unit,
    onCompleted: (Task) -> Unit = {},
    revealCoordinator: RevealCoordinator? = null
) {
    SwipeableTaskRow(
        title = task.title,
        isCompleted = task.isCompleted,
        timeLabel = dueChipLabel(task.dueAt),
        description = task.description.takeIf { it.isNotBlank() },
        subTasks = task.subTasks,
        categoryLabel = categoryName,
        repeatLabel = repeatChipText(task),
        priorityLabel = priorityChipLabel(task.priority),
        priorityDotColor = priorityDotColor(task.priority),
        isHighlighted = isHighlighted,
        onCheckedChange = { checked ->
            viewModel.onAction(HomeAction.ToggleComplete(task.id, checked))
            // Only celebrate a tick that will actually land. ToggleCompleteUseCase
            // refuses to finish a task with unfinished sub-tasks (and Home shows why),
            // so firing the splash here unconditionally meant the confetti played for
            // a completion the app had just rejected.
            if (checked && !task.isCompleted && task.subTasksDone) onCompleted(task)
        },
        onAddSubTask = { onAddSubTaskRequest(task) },
        onSubTaskChecked = { subId, done ->
            viewModel.onAction(HomeAction.ToggleSubTask(task.id, subId, done))
            // The last sub-task tick is the moment a checklist task is actually
            // finished - its own checkbox is refused until then - so that is where
            // the celebration belongs, not on a tick the guard rejects. Asking the
            // model the same question the ViewModel is about to write keeps the two
            // from disagreeing.
            if (!task.isCompleted && task.withSubTaskChecked(subId, done).isCompleted) {
                onCompleted(task)
            }
        },
        onClick = onOpen,
        onLongClick = { onLongPress(task) },
        onEdit = onEdit,
        onDelete = { viewModel.onAction(HomeAction.DeleteTask(task)) },
        rowId = task.id,
        coordinator = revealCoordinator
    )
}

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

/** Formats a java.time date with the device time zone (no UTC drift). */
private fun formatLocalDate(date: java.time.LocalDate, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault())
        .format(Date.from(date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()))

/**
 * Habit marker for a card: the streak once there is one to be proud of, otherwise
 * just the cadence. Null for ordinary one-off tasks.
 */
private fun repeatChipText(task: Task): String? = when {
    !task.repeat.isRecurring -> null
    task.streak > 1 -> "${task.streak} in a row"
    task.repeat == RepeatRule.DAILY -> "Daily"
    else -> "Weekly"
}

/** Compact chip label for the card – "Today", "Tomorrow", "Oct 27". */
private fun dueChipLabel(dueAt: Long?): String {
    if (dueAt == null) return "Today"
    val now = System.currentTimeMillis()
    val startOfToday = getStartOfDay(now)
    val startOfTomorrow = startOfToday + 24 * 60 * 60 * 1000L
    val startOfDayAfter = startOfTomorrow + 24 * 60 * 60 * 1000L
    return when {
        dueAt < startOfToday -> "Overdue"
        dueAt < startOfTomorrow -> "Today"
        dueAt < startOfDayAfter -> "Tomorrow"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(dueAt))
    }
}

private fun priorityChipLabel(priority: Priority): String = when (priority) {
    Priority.URGENT -> "Urgent"
    Priority.HIGH -> "High priority"
    Priority.MEDIUM -> "Medium"
    Priority.LOW -> "Low"
}

private fun priorityDotColor(priority: Priority): Color? = when (priority) {
    Priority.URGENT -> Color(0xFFEB5757)
    Priority.HIGH -> Color(0xFFF2994A)
    Priority.MEDIUM -> null
    Priority.LOW -> null
}

private fun getStartOfDay(timeMillis: Long): Long {
    val cal = Calendar.getInstance().apply {
        this.timeInMillis = timeMillis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

@Composable
private fun SortSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun SortMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) AccentBlue else MaterialTheme.colorScheme.onSurface
            )
        },
        trailingIcon = {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
        },
        onClick = onClick
    )
}
