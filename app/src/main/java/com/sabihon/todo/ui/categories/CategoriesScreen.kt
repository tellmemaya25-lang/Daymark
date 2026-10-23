package com.sabihon.todo.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.ui.components.CategoryCard
import com.sabihon.todo.core.ui.components.EmptyState
import com.sabihon.todo.core.ui.components.SabihonFab
import com.sabihon.todo.core.ui.components.TaskRow
import com.sabihon.todo.core.ui.theme.CategoryPaletteHex
import com.sabihon.todo.core.ui.theme.parseColorHex

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
                verticalArrangement = Arrangement.Center
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
                        EmptyState(title = "No categories", description = "Tap + to create your first category")
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
        // Material's default is 28dp – a touch soft for a dialog this small.
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

/**
 * A color as a plain circle – the hex string told you nothing at a glance.
 * Four of the six palette entries are near-white, so every swatch keeps a
 * hairline ring and the tick flips to dark ink on the pale ones.
 */
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
    viewModel: CategoryDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    androidx.compose.runtime.LaunchedEffect(categoryId) {
        viewModel.loadCategory(categoryId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.category?.name ?: "Category") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            SabihonFab(onClick = onAddTask)
        }
    ) { padding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (uiState.tasks.isEmpty()) {
                    item {
                        EmptyState(title = "No tasks in this category", description = "Add tasks to ${uiState.category?.name ?: "this category"}")
                    }
                } else {
                    items(uiState.tasks, key = { it.id }) { task ->
                        TaskRow(
                            title = task.title,
                            isCompleted = task.isCompleted,
                            timeLabel = task.dueAt?.let {
                                java.text.SimpleDateFormat("MMM d • h:mm a", java.util.Locale.getDefault())
                                    .format(java.util.Date(it))
                            } ?: "No date",
                            description = task.description.takeIf { it.isNotBlank() },
                            // Without this the chevron renders but has nothing to expand.
                            subTasks = task.subTasks,
                            onSubTaskChecked = { subTaskId, done ->
                                viewModel.toggleSubTask(task.id, subTaskId, done)
                            },
                            onClick = { onTaskClick(task.id) }
                        )
                    }
                }
            }
        }
    }
}
