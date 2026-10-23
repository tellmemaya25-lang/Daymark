package com.sabihon.todo.ui.home

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.ui.components.FilterEmptyState
import com.sabihon.todo.core.ui.components.LoadingShimmer
import com.sabihon.todo.core.ui.components.NotelyEmptyState
import com.sabihon.todo.core.ui.components.SectionHeader
import com.sabihon.todo.core.ui.components.SwipeableTaskRow
import com.sabihon.todo.domain.model.Task
import java.text.SimpleDateFormat
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
    val bottomSheetState = rememberModalBottomSheetState()
    val addSheetState = rememberModalBottomSheetState()

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Hello, ${uiState.greetingName},",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "You have work today",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (uiState.isLoading) {
            LoadingShimmer(modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 5 Chips: All first as default
                item {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val chips = listOf(
                            HomeFilter.ALL to "All",
                            HomeFilter.TODAY to "Today",
                            HomeFilter.PENDING to "Pending",
                            HomeFilter.COMPLETED to "Completed",
                            HomeFilter.OVERDUE to "Overdue"
                        )
                        items(chips) { (filter, label) ->
                            FilterChip(
                                selected = uiState.selectedFilter == filter,
                                onClick = { viewModel.onAction(HomeAction.SetFilter(filter)) },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader(
                        title = when (uiState.selectedFilter) {
                            HomeFilter.TODAY -> "Today's Task"
                            HomeFilter.PENDING -> "Pending Tasks"
                            HomeFilter.COMPLETED -> "Completed Tasks"
                            HomeFilter.ALL -> "All Tasks"
                            HomeFilter.OVERDUE -> "Overdue Tasks"
                        },
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (uiState.filteredTasks.isEmpty()) {
                    item {
                        when (uiState.selectedFilter) {
                            HomeFilter.ALL -> {
                                NotelyEmptyState(
                                    title = "Get started with daymark .",
                                    subtitle = "Add notes, calendar events, tasks files and more with the action bar"
                                )
                            }
                            HomeFilter.TODAY -> FilterEmptyState(filterName = "Today")
                            HomeFilter.PENDING -> FilterEmptyState(filterName = "Pending")
                            HomeFilter.COMPLETED -> FilterEmptyState(filterName = "Completed")
                            HomeFilter.OVERDUE -> FilterEmptyState(filterName = "Overdue")
                        }
                    }
                } else {
                    items(uiState.filteredTasks, key = { it.id }) { task ->
                        SwipeableTaskRow(
                            title = task.title,
                            isCompleted = task.isCompleted,
                            timeLabel = formatTimeLabel(task.dueAt),
                            description = task.description.takeIf { it.isNotBlank() },
                            subTasks = task.subTasks,
                            categoryLabel = null,
                            onCheckedChange = { checked ->
                                viewModel.onAction(HomeAction.ToggleComplete(task.id, checked))
                            },
                            onAddSubTask = {
                                taskForNewSubTask = task
                                newSubTaskTitle = ""
                                showAddSubTaskSheet = true
                            },
                            onSubTaskChecked = { subId, done ->
                                viewModel.onAction(HomeAction.ToggleSubTask(task.id, subId, done))
                            },
                            onClick = { onNavigateToTaskDetail(task.id) },
                            onLongClick = {
                                selectedTask = task
                                showActionSheet = true
                            },
                            onEdit = {
                                // Edit should go to edit tasks per request – image pencil blue + trash red
                                if (onNavigateToEditTask != {}) onNavigateToEditTask(task.id) else onNavigateToTaskDetail(task.id)
                            },
                            onDelete = { viewModel.onAction(HomeAction.DeleteTask(task)) },
                            onToggleComplete = { checked ->
                                viewModel.onAction(HomeAction.ToggleComplete(task.id, checked))
                            }
                        )
                    }
                }

                item { Spacer(Modifier.height(100.dp)) }
            }
        }

        // Bottom sheet – Edit / Mark as Done / Delete – matches screenshot Webinar bottom sheet
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
                    // Double handle like screenshot – dark + light gray
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
                        text = selectedTask?.title ?: "Webinar",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        maxLines = 2
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp
                    )

                    // Edit – pencil icon – should go to edit tasks per request
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

                    // Mark as Done – check icon
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showActionSheet = false
                                selectedTask?.let {
                                    viewModel.onAction(HomeAction.ToggleComplete(it.id, !it.isCompleted))
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

                    // Delete – trash red like screenshot
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showActionSheet = false
                                selectedTask?.let {
                                    viewModel.onAction(HomeAction.DeleteTask(it))
                                }
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

                    // Bottom handle like iOS
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

        // Bottom sheet – Add new task / Rename – like image bottom edit, for sub-task
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
                    // Handle
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
    }
}

private fun formatTimeLabel(dueAt: Long?): String {
    if (dueAt == null) return "Today"
    val now = System.currentTimeMillis()
    val startOfToday = getStartOfDay(now)
    val startOfYesterday = startOfToday - 24 * 60 * 60 * 1000L
    val endOfToday = getEndOfDay(now)
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val timeStr = timeFormat.format(Date(dueAt))
    return when {
        dueAt in startOfToday..endOfToday -> "Today • $timeStr"
        dueAt in startOfYesterday until startOfToday -> "Yesterday • $timeStr"
        else -> {
            val dateFormat = SimpleDateFormat("MMM d • h:mm a", Locale.getDefault())
            dateFormat.format(Date(dueAt))
        }
    }
}

private fun getStartOfDay(timeMillis: Long): Long {
    val cal = java.util.Calendar.getInstance().apply {
        this.timeInMillis = timeMillis
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

private fun getEndOfDay(timeMillis: Long): Long {
    val cal = java.util.Calendar.getInstance().apply {
        this.timeInMillis = timeMillis
        set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59)
        set(java.util.Calendar.SECOND, 59); set(java.util.Calendar.MILLISECOND, 999)
    }
    return cal.timeInMillis
}
