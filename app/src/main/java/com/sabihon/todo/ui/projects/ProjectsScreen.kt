package com.sabihon.todo.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.ui.components.BrandHeader
import com.sabihon.todo.core.ui.components.ConfirmDialog
import com.sabihon.todo.core.ui.theme.LocalIsDarkTheme
import com.sabihon.todo.core.ui.theme.parseColorHex
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.ui.categories.AddEditCategoryDialog
import com.sabihon.todo.core.ui.theme.CategoryPaletteHex

/**
 * Projects – the structure behind the task list.
 *
 * Brand header, a two-tone headline, then one row per project with its open and
 * completed counts. Tapping a row opens that project's tasks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onProjectClick: (String) -> Unit = {},
    onProfileClick: () -> Unit = {},
    /** Opens the flat list of every task, whatever project it belongs to. */
    onAllTasksClick: () -> Unit = {},
    /**
     * Incremented by the tab host each time the bottom bar's "+" is tapped while this
     * page is showing. A plain Int rather than a lambda because the bar lives above the
     * pager, and a counter is all the state this needs: every value but the first one
     * means "open the dialog".
     */
    addRequest: Int = 0,
    /** Tells the tab host the request has been dealt with, so it is not replayed. */
    onAddRequestHandled: () -> Unit = {},
    viewModel: ProjectsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isDark = LocalIsDarkTheme.current
    // Long-press opens this row's actions; Delete asks for confirmation first.
    var actionProject by remember { mutableStateOf<Category?>(null) }
    var confirmDelete by remember { mutableStateOf<Category?>(null) }
    val sheetState = rememberModalBottomSheetState()

    // key(addRequest) restarts the effect on every tap; the guard keeps the very first
    // composition from opening a dialog nobody asked for, and the callback clears the
    // request so returning to this page later does not replay it.
    LaunchedEffect(addRequest) {
        if (addRequest > 0) {
            viewModel.showAddDialog(true)
            onAddRequestHandled()
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    BrandHeader(
                        avatarInitials = uiState.userInitials,
                        avatarUrl = uiState.userPhotoUrl,
                        onAvatarClick = onProfileClick,
                        // No "+" up here any more: on this tab the bottom bar's "+"
                        // creates a project, and two buttons for one job is one too
                        // many. The header is the brand mark and the account only.
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)
                    )
                }

                item {
                    Column(
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp)
                    ) {
                        Text(
                            text = "Everything in its place.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Your projects.",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 30.sp,
                                lineHeight = 35.sp,
                                letterSpacing = (-0.8).sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "A little structure for the things that matter.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(18.dp))
                    }
                }

                // "All tasks" used to be a tab on Home. It lives here now, because a
                // strip of tabs was too much furniture for one list, and this screen is
                // already the place you come to look at everything at once.
                item { AllTasksRow(onClick = onAllTasksClick) }

                if (uiState.projects.isEmpty()) {
                    item { EmptyProjects() }
                } else {
                    itemsIndexed(uiState.projects, key = { _, project -> project.id }) { index, project ->
                        ProjectRow(
                            project = project,
                            isDark = isDark,
                            showDivider = index < uiState.projects.lastIndex,
                            onClick = { onProjectClick(project.id) },
                            onLongClick = { actionProject = project }
                        )
                    }

                    item {
                        Text(
                            text = "Select a project to see its tasks. Long-press to edit or delete.",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)
                        )
                    }
                }

                item { Spacer(Modifier.height(110.dp)) }
            }
            }
        }
    }

    // Long-press actions
    if (actionProject != null) {
        ModalBottomSheet(
            onDismissRequest = { actionProject = null },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    text = actionProject?.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                ProjectActionRow(
                    icon = Icons.Filled.Edit,
                    label = "Edit project",
                    tint = MaterialTheme.colorScheme.onSurface
                ) {
                    val target = actionProject
                    actionProject = null
                    if (target != null) viewModel.editProject(target)
                }
                ProjectActionRow(
                    icon = Icons.Filled.Delete,
                    label = "Delete project",
                    tint = Color(0xFFE57373)
                ) {
                    val target = actionProject
                    actionProject = null
                    confirmDelete = target
                }
            }
        }
    }

    if (confirmDelete != null) {
        ConfirmDialog(
            title = "Delete project?",
            message = "\u201C${confirmDelete?.name.orEmpty()}\u201D will be removed. Its tasks stay in your list, just unfiled.",
            onConfirm = {
                confirmDelete?.let { viewModel.deleteProject(it.id) }
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null }
        )
    }

    if (uiState.showAddDialog) {
        AddEditCategoryDialog(
            initialName = uiState.editingProject?.name ?: "",
            initialColor = uiState.editingProject?.colorHex ?: CategoryPaletteHex.first(),
            noun = "project",
            onDismiss = { viewModel.showAddDialog(false) },
            onConfirm = { name, color -> viewModel.addOrUpdateProject(name, color) }
        )
    }
}

@Composable
private fun ProjectActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
/**
 * Flat "everything" entry – same metrics as a project row so the list reads as one
 * column, but with a neutral tile instead of a tinted project colour: it is not a
 * project, and it should not pretend to be one.
 */
@Composable
private fun AllTasksRow(onClick: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "All tasks",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "Everything, across every project",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
        )
    }
}

@Composable
private fun ProjectRow(
    project: Category,
    isDark: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val open = (project.taskCount - project.completedCount).coerceAtLeast(0)
    val completed = project.completedCount

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon tile tinted with the project colour
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        parseColorHex(project.colorHex).copy(alpha = if (isDark) 0.30f else 0.55f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = projectIcon(project.iconKey, project.name),
                    contentDescription = null,
                    tint = if (isDark) Color.White.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "$open open · $completed complete",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }

        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            )
        }
    }
}

@Composable
private fun EmptyProjects() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.GridView,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "No projects yet",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Tap + to create one and group your tasks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Picks a glyph for a project. Honours the stored `iconKey` when we know it and
 * otherwise falls back to the project name, so seeded categories (Grocery, Work,
 * Learning …) still get something meaningful.
 */
private fun projectIcon(iconKey: String, name: String): ImageVector {
    return when (iconKey.lowercase()) {
        "grid" -> Icons.Filled.GridView
        "star", "sparkle" -> Icons.Filled.AutoAwesome
        "chart", "stats" -> Icons.Filled.BarChart
        "work", "briefcase" -> Icons.Filled.Work
        "school", "learn" -> Icons.Filled.School
        "cart", "grocery" -> Icons.Filled.ShoppingCart
        else -> when {
            name.contains("work", true) -> Icons.Filled.Work
            name.contains("school", true) || name.contains("learn", true) -> Icons.Filled.School
            name.contains("grocer", true) -> Icons.Filled.ShoppingCart
            name.contains("personal", true) -> Icons.Filled.AutoAwesome
            else -> Icons.Filled.GridView
        }
    }
}
