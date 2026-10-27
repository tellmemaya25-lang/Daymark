package com.sabihon.todo.ui.categories

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.ui.components.CategoryCard
import com.sabihon.todo.core.ui.components.CompletionSplash
import com.sabihon.todo.core.ui.components.EmptyState
import com.sabihon.todo.core.ui.components.RevealCoordinator
import com.sabihon.todo.core.ui.components.SabihonFab
import com.sabihon.todo.core.ui.components.SwipeableTaskRow
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.CategoryPaletteHex
import com.sabihon.todo.core.ui.theme.parseColorHex
import com.sabihon.todo.domain.ai.SuggestedTaskCandidate
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.ui.home.HomeSort
import com.sabihon.todo.ui.home.HomeSortDirection
import com.sabihon.todo.ui.home.SuggestedTasksCard
import com.sabihon.todo.ui.home.arrow
import com.sabihon.todo.ui.home.label
import com.sabihon.todo.ui.home.shortLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit = {},
    onCategoryClick: (String) -> Unit = {},
    viewModel: CategoriesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All Task List") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            SabihonFab(onClick = { viewModel.showAddDialog(true) })
        }
    ) { padding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (uiState.categories.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No categories",
                            description = "Tap + to create your first category"
                        )
                    }
                } else {
                    items(uiState.categories, key = { it.id }) { cat ->
                        CategoryCard(
                            name = cat.name,
                            colorHex = cat.colorHex,
                            taskCount = cat.taskCount,
                            completedCount = cat.completedCount,
                            onClick = { onCategoryClick(cat.id) }
                        )
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        if (uiState.showAddDialog) {
            AddEditCategoryDialog(
                initialName = uiState.editingCategory?.name ?: "",
                initialColor = uiState.editingCategory?.colorHex ?: CategoryPaletteHex.first(),
                onDismiss = { viewModel.showAddDialog(false) },
                onConfirm = { name, color ->
                    viewModel.addOrUpdateCategory(name, color)
                }
            )
        }
    }
}

@Composable
fun AddEditCategoryDialog(
    initialName: String,
    initialColor: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
    noun: String = "category"
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedColor by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = if (initialName.isBlank()) "New $noun" else "Edit $noun",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("${noun.replaceFirstChar { it.uppercase() }} name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pick a color", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CategoryPaletteHex.forEach { hex ->
                            ColorSwatch(
                                hex = hex,
                                selected = selectedColor == hex,
                                onClick = { selectedColor = hex }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, selectedColor) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ColorSwatch(
    hex: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color = parseColorHex(hex)
    val ringColor = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outlineVariant
    val tickTint = if (color.luminance() > 0.5f) Color(0xFF1B1C20) else Color.White

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color)
            .border(width = if (selected) 2.dp else 1.dp, color = ringColor, shape = CircleShape)
            .semantics { contentDescription = colorName(hex) }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = tickTint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun colorName(hex: String): String = when (hex.uppercase()) {
    "#D6E4FF" -> "Blue"
    "#FDF3A0" -> "Yellow"
    "#CFF5E7" -> "Mint"
    "#FBD7EA" -> "Pink"
    "#E5DBFF" -> "Lavender"
    "#2F6B4F" -> "Forest green"
    else -> hex
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(
    categoryId: String,
    onBack: () -> Unit = {},
    onTaskClick: (String) -> Unit = {},
    onAddTask: () -> Unit = {},
    onEditSuggestion: (SuggestedTaskCandidate) -> Unit = {},
    viewModel: CategoryDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val revealCoordinator = remember { RevealCoordinator() }
    var showSortMenu by remember { mutableStateOf(false) }
    var completedExpanded by rememberSaveable { mutableStateOf(true) }
    var splashTaskTitle by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(categoryId) {
        viewModel.loadCategory(categoryId)
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = uiState.category?.name ?: "Category",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (uiState.totalCount > 0) {
                            Box {
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
                                    Spacer(Modifier.width(5.dp))
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
                                    CategoryMenuSection("Sort by")
                                    HomeSort.entries.forEach { option ->
                                        CategorySortItem(
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
                                    CategoryMenuSection("Order")
                                    HomeSortDirection.entries.forEach { dir ->
                                        CategorySortItem(
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

                        TextButton(
                            onClick = { viewModel.loadSuggestions(force = true) },
                            enabled = !uiState.isSuggesting
                        ) {
                            if (uiState.isSuggesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Spacer(Modifier.width(5.dp))
                            Text("Suggest")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            floatingActionButton = {
                SabihonFab(onClick = onAddTask)
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
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (uiState.suggestions.isNotEmpty() || uiState.isSuggesting) {
                            item(key = "category_suggestions") {
                                SuggestedTasksCard(
                                    suggestions = uiState.suggestions,
                                    expanded = uiState.suggestionsExpanded,
                                    isRefreshing = uiState.isSuggesting,
                                    onToggleExpanded = { viewModel.toggleSuggestionsExpanded() },
                                    onAccept = { viewModel.acceptSuggestion(it) },
                                    onDismiss = { viewModel.dismissSuggestion(it) },
                                    onRefresh = { viewModel.loadSuggestions(force = true) },
                                    onEdit = onEditSuggestion,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    title = "Suggested for this project"
                                )
                            }
                        }

                        if (uiState.openTasks.isEmpty() && uiState.completedTasks.isEmpty()) {
                            item(key = "category_empty") {
                                EmptyState(
                                    title = "No tasks in this category",
                                    description = "Add tasks to ${uiState.category?.name ?: "this category"}"
                                )
                            }
                        } else {
                            if (uiState.openTasks.isNotEmpty()) {
                                item(key = "category_open_header") {
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
                                    CategoryTaskCard(
                                        task = task,
                                        categoryName = uiState.category?.name,
                                        revealCoordinator = revealCoordinator,
                                        onCheckedChange = { checked ->
                                            if (checked && !task.isCompleted && task.subTasksDone) {
                                                splashTaskTitle = task.title
                                            }
                                            viewModel.toggleComplete(task.id, checked)
                                        },
                                        onSubTaskChecked = { subTaskId, done ->
                                            if (!task.isCompleted && task.withSubTaskChecked(subTaskId, done).isCompleted) {
                                                splashTaskTitle = task.title
                                            }
                                            viewModel.toggleSubTask(task.id, subTaskId, done)
                                        },
                                        onOpen = { onTaskClick(task.id) },
                                        onDelete = { viewModel.deleteTask(task) }
                                    )
                                }
                            }

                            if (uiState.completedTasks.isNotEmpty()) {
                                item(key = "category_completed_header") {
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
                                        CategoryTaskCard(
                                            task = task,
                                            categoryName = uiState.category?.name,
                                            revealCoordinator = revealCoordinator,
                                            onCheckedChange = { checked ->
                                                viewModel.toggleComplete(task.id, checked)
                                            },
                                            onSubTaskChecked = { subTaskId, done ->
                                                viewModel.toggleSubTask(task.id, subTaskId, done)
                                            },
                                            onOpen = { onTaskClick(task.id) },
                                            onDelete = { viewModel.deleteTask(task) }
                                        )
                                    }
                                }
                            }
                        }

                        item { Spacer(Modifier.height(110.dp)) }
                    }
                }
            }
        }

        CompletionSplash(
            visible = splashTaskTitle != null,
            subtitle = splashTaskTitle,
            onFinished = { splashTaskTitle = null }
        )
    }
}

@Composable
private fun CategoryTaskCard(
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
        timeLabel = task.dueAt?.let {
            SimpleDateFormat("MMM d • h:mm a", Locale.getDefault()).format(Date(it))
        } ?: "No date",
        description = task.description.takeIf { it.isNotBlank() },
        subTasks = task.subTasks,
        categoryLabel = categoryName,
        priorityLabel = formatCategoryPriorityLabel(task.priority),
        repeatLabel = formatCategoryRepeatLabel(task),
        priorityDotColor = formatCategoryPriorityDot(task.priority),
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

private fun formatCategoryRepeatLabel(task: Task): String? = when {
    !task.repeat.isRecurring -> null
    task.streak > 1 -> "${task.streak} in a row"
    task.repeat == RepeatRule.DAILY -> "Daily"
    else -> "Weekly"
}

private fun formatCategoryPriorityLabel(priority: Priority): String = when (priority) {
    Priority.URGENT -> "Urgent"
    Priority.HIGH -> "High priority"
    Priority.MEDIUM -> "Medium"
    Priority.LOW -> "Low"
}

private fun formatCategoryPriorityDot(priority: Priority): Color? = when (priority) {
    Priority.URGENT -> Color(0xFFEB5757)
    Priority.HIGH -> Color(0xFFF2994A)
    Priority.MEDIUM -> null
    Priority.LOW -> null
}

@Composable
private fun CategoryMenuSection(text: String) {
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
private fun CategorySortItem(
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
