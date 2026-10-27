package com.sabihon.todo.ui.projects

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.ui.components.EmptyState
import com.sabihon.todo.core.ui.components.RevealCoordinator
import com.sabihon.todo.core.ui.components.SwipeableTaskRow
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.ui.home.HomeSort
import com.sabihon.todo.ui.home.HomeSortDirection
import com.sabihon.todo.ui.home.arrow
import com.sabihon.todo.ui.home.label
import com.sabihon.todo.ui.home.shortLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Flat, neutral list of every created task across all projects (`Projects > All tasks`).
 *
 * Uses plain neutral task cards (`isHighlighted = false`) with no colored category cards
 * so every created task can be scanned, ticked off, or edited in one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllTasksScreen(
    onBack: () -> Unit = {},
    onTaskClick: (String) -> Unit = {},
    viewModel: AllTasksViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val revealCoordinator = remember { RevealCoordinator() }
    var showSortMenu by remember { mutableStateOf(false) }
    var completedExpanded by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            revealCoordinator.closeAll()
        }
    }

    LaunchedEffect(uiState.snackbarMessage, uiState.deletedTaskIdForUndo) {
        val message = uiState.snackbarMessage ?: return@LaunchedEffect
        val undoId = uiState.deletedTaskIdForUndo
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = if (undoId != null) "Undo" else null,
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed && undoId != null) {
            viewModel.undoDelete(undoId)
        }
        viewModel.clearSnackbar()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "All tasks",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (uiState.totalCount > 0) {
                        Box(modifier = Modifier.padding(end = 12.dp)) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showSortMenu = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = "Sort tasks",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "${uiState.sort.shortLabel} ${uiState.sortDirection.arrow}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                AllTasksMenuSection("Sort by")
                                HomeSort.entries.forEach { option ->
                                    AllTasksSortItem(
                                        label = option.label,
                                        selected = option == uiState.sort,
                                        onClick = {
                                            viewModel.setSort(option)
                                            showSortMenu = false
                                        }
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                )
                                AllTasksMenuSection("Order")
                                HomeSortDirection.entries.forEach { dir ->
                                    AllTasksSortItem(
                                        label = dir.label,
                                        selected = dir == uiState.sortDirection,
                                        onClick = {
                                            viewModel.setSortDirection(dir)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (uiState.openTasks.isEmpty() && uiState.completedTasks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyState(
                            title = "No tasks yet",
                            description = "Tap + below to create your first task"
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (uiState.openTasks.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Open",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "${uiState.openTasks.size}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            items(uiState.openTasks, key = { it.id }) { task ->
                                NeutralAllTaskCard(
                                    task = task,
                                    categoryName = task.categoryId?.let { uiState.categoryNames[it] },
                                    revealCoordinator = revealCoordinator,
                                    onCheckedChange = { checked ->
                                        viewModel.toggleComplete(task.id, checked)
                                    },
                                    onSubTaskChecked = { subId, done ->
                                        viewModel.toggleSubTask(task, subId, done)
                                    },
                                    onOpen = { onTaskClick(task.id) },
                                    onDelete = { viewModel.deleteTask(task) }
                                )
                            }
                        }

                        if (uiState.completedTasks.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { completedExpanded = !completedExpanded }
                                        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
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
                                        text = "${uiState.completedTasks.size}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Icon(
                                        imageVector = if (completedExpanded) {
                                            Icons.Filled.ExpandLess
                                        } else {
                                            Icons.Filled.ExpandMore
                                        },
                                        contentDescription = if (completedExpanded) {
                                            "Collapse completed"
                                        } else {
                                            "Expand completed"
                                        },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            if (completedExpanded) {
                                items(uiState.completedTasks, key = { it.id }) { task ->
                                    NeutralAllTaskCard(
                                        task = task,
                                        categoryName = task.categoryId?.let { uiState.categoryNames[it] },
                                        revealCoordinator = revealCoordinator,
                                        onCheckedChange = { checked ->
                                            viewModel.toggleComplete(task.id, checked)
                                        },
                                        onSubTaskChecked = { subId, done ->
                                            viewModel.toggleSubTask(task, subId, done)
                                        },
                                        onOpen = { onTaskClick(task.id) },
                                        onDelete = { viewModel.deleteTask(task) }
                                    )
                                }
                            }
                        }

                        item { Spacer(Modifier.height(110.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NeutralAllTaskCard(
    task: Task,
    categoryName: String?,
    revealCoordinator: RevealCoordinator,
    onCheckedChange: (Boolean) -> Unit,
    onSubTaskChecked: (String, Boolean) -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    SwipeableTaskRow(
        title = task.title,
        isCompleted = task.isCompleted,
        timeLabel = formatDueLabel(task.dueAt),
        description = task.description.takeIf { it.isNotBlank() },
        subTasks = task.subTasks,
        categoryLabel = categoryName,
        repeatLabel = formatRepeatLabel(task),
        priorityLabel = formatPriorityLabel(task.priority),
        priorityDotColor = formatPriorityDot(task.priority),
        isHighlighted = false,
        onCheckedChange = onCheckedChange,
        onSubTaskChecked = onSubTaskChecked,
        onClick = onOpen,
        onEdit = onOpen,
        onDelete = onDelete,
        rowId = task.id,
        coordinator = revealCoordinator
    )
}

private fun formatRepeatLabel(task: Task): String? = when {
    !task.repeat.isRecurring -> null
    task.streak > 1 -> "${task.streak} in a row"
    task.repeat == RepeatRule.DAILY -> "Daily"
    else -> "Weekly"
}

private fun formatDueLabel(dueAt: Long?): String {
    if (dueAt == null) return "No due date"
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startOfToday = cal.timeInMillis
    val startOfTomorrow = startOfToday + 24 * 60 * 60 * 1000L
    val startOfDayAfter = startOfTomorrow + 24 * 60 * 60 * 1000L
    return when {
        dueAt < startOfToday -> "Overdue"
        dueAt < startOfTomorrow -> "Today"
        dueAt < startOfDayAfter -> "Tomorrow"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(dueAt))
    }
}

private fun formatPriorityLabel(priority: Priority): String = when (priority) {
    Priority.URGENT -> "Urgent"
    Priority.HIGH -> "High priority"
    Priority.MEDIUM -> "Medium"
    Priority.LOW -> "Low"
}

private fun formatPriorityDot(priority: Priority): Color? = when (priority) {
    Priority.URGENT -> Color(0xFFEB5757)
    Priority.HIGH -> Color(0xFFF2994A)
    Priority.MEDIUM -> null
    Priority.LOW -> null
}

@Composable
private fun AllTasksMenuSection(text: String) {
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
private fun AllTasksSortItem(
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
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
        },
        onClick = onClick
    )
}
