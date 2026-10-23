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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.sabihon.todo.core.ui.components.DaymarkMark
import com.sabihon.todo.core.ui.components.DaymarkWordmark
import com.sabihon.todo.core.ui.components.FilterEmptyState
import com.sabihon.todo.core.ui.components.LoadingShimmer
import com.sabihon.todo.core.ui.components.MomentumCard
import com.sabihon.todo.core.ui.components.NotelyEmptyState
import com.sabihon.todo.core.ui.components.SegmentedTabs
import com.sabihon.todo.core.ui.components.SwipeableTaskRow
import com.sabihon.todo.core.ui.components.TabItem
import com.sabihon.todo.core.ui.components.WeekStrip
import com.sabihon.todo.domain.model.Priority
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
    var completedExpanded by remember { mutableStateOf(true) }
    var showSortMenu by remember { mutableStateOf(false) }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (uiState.isLoading) {
            LoadingShimmer(modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                item {
                    HomeHeader(
                        greetingName = uiState.greetingName,
                        avatarInitials = uiState.avatarInitials,
                        avatarUrl = uiState.avatarUrl,
                        onSearchClick = onNavigateToSearch,
                        onAvatarClick = onNavigateToProfile
                    )
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
                        sortLabel = when (uiState.selectedSort) {
                            HomeSort.DEFAULT -> "Default order"
                            HomeSort.PRIORITY -> "Priority first"
                            HomeSort.DUE_DATE -> "Due date"
                        },
                        showSortMenu = showSortMenu,
                        onSortMenuChange = { showSortMenu = it },
                        onSortSelected = { sort -> viewModel.onAction(HomeAction.SetSort(sort)) }
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
                        }
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
                                }
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(110.dp)) }
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
    val today = remember {
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
    ) {
        // Brand row: mark + wordmark left, avatar right
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DaymarkMark(size = 26.dp)
            Spacer(Modifier.width(8.dp))
            DaymarkWordmark(fontSize = 19, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            ProfileAvatar(
                initials = avatarInitials,
                photoUrl = avatarUrl,
                onClick = onAvatarClick
            )
        }

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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onSearchClick, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 40.dp circle showing the Google photo when available, otherwise the user's initials.
 */
@Composable
private fun ProfileAvatar(
    initials: String,
    photoUrl: String?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                CircleShape
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = "Profile",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
        } else {
            Text(
                text = initials,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
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
    showSortMenu: Boolean,
    onSortMenuChange: (Boolean) -> Unit,
    onSortSelected: (HomeSort) -> Unit
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
                listOf(
                    HomeSort.DEFAULT to "Default order",
                    HomeSort.PRIORITY to "Priority first",
                    HomeSort.DUE_DATE to "Due date"
                ).forEach { (sort, label) ->
                    DropdownMenuItem(
                        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            onSortSelected(sort)
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
    onLongPress: (Task) -> Unit
) {
    SwipeableTaskRow(
        title = task.title,
        isCompleted = task.isCompleted,
        timeLabel = dueChipLabel(task.dueAt),
        description = task.description.takeIf { it.isNotBlank() },
        subTasks = task.subTasks,
        categoryLabel = categoryName,
        priorityLabel = priorityChipLabel(task.priority),
        priorityDotColor = priorityDotColor(task.priority),
        isHighlighted = isHighlighted,
        onCheckedChange = { checked ->
            viewModel.onAction(HomeAction.ToggleComplete(task.id, checked))
        },
        onAddSubTask = { onAddSubTaskRequest(task) },
        onSubTaskChecked = { subId, done ->
            viewModel.onAction(HomeAction.ToggleSubTask(task.id, subId, done))
        },
        onClick = onOpen,
        onLongClick = { onLongPress(task) },
        onEdit = onEdit,
        onDelete = { viewModel.onAction(HomeAction.DeleteTask(task)) },
        onToggleComplete = { checked ->
            viewModel.onAction(HomeAction.ToggleComplete(task.id, checked))
        }
    )
}

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

/** Formats a java.time date with the device time zone (no UTC drift). */
private fun formatLocalDate(date: java.time.LocalDate, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault())
        .format(Date.from(date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()))

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
