package com.sabihon.todo.ui.taskdetail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.ui.theme.LocalIsDarkTheme
import com.sabihon.todo.core.ui.components.SubTaskRoundedCheckbox
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.domain.model.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: String,
    onBack: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    viewModel: TaskDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    var newSubTaskTitle by remember { mutableStateOf("") }
    val addSheetState = rememberModalBottomSheetState()

    LaunchedEffect(taskId) {
        viewModel.loadTask(taskId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All Tasks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onEdit(taskId) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Edit",
                            tint = AccentBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            uiState.task?.let { task ->
                val totalSubs = task.subTasks.size
                val doneSubs = task.subTasks.count { it.isDone }
                val rawProgress = if (totalSubs > 0) doneSubs.toFloat() / totalSubs else if (task.isCompleted) 1f else 0f
                var targetProgress by remember { mutableFloatStateOf(0f) }
                LaunchedEffect(rawProgress) { targetProgress = rawProgress }
                val progress by animateFloatAsState(
                    targetValue = targetProgress,
                    animationSpec = tween(durationMillis = 800),
                    label = "progressAnim"
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header – use color combo from setting for overall dark mode theme, leave light mode as is per request – image1 bg #e3f5ff light, dark uses Settings dark combo
                    val isDark = LocalIsDarkTheme.current
                    // Light mode #e3f5ff per image1, dark mode uses Settings dark combo #1E2A44 for overall dark theme
                    val outerCardBg = if (isDark) Color(0xFF1E2A44) else Color(0xFFE3F5FF)
                    val innerWhiteBg = if (isDark) Color(0xFF2A2B33) else Color.White
                    val innerWhiteBg2 = Color.White

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = outerCardBg),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Title on top – Webinar – light mode dark text on #e3f5ff, dark mode white text on #1E2A44 per Settings combo
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                ),
                                color = if (isDark) Color.White else Color(0xFF101114),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(end = 72.dp)
                            )

                            // Box with description + percentage overlapping right side 30% – light #e3f5ff White inner, dark #1E2A44 with #2A2B33 inner per Settings
                            Box(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = 24.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(innerWhiteBg)
                                        .padding(16.dp)
                                        .padding(end = 32.dp)
                                ) {
                                    Text(
                                        text = task.description.takeIf { it.isNotBlank() } ?: "Cybersecurity is What type of thing this do what Other things should we able to do here. asdasdf asdfas asdf asd asdfa",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp
                                        ),
                                        color = if (isDark) Color(0xFFF5F5F5) else Color(0xFF374151)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .offset(x = 12.dp)
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(innerWhiteBg2)
                                        .padding(2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(modifier = Modifier.size(56.dp)) {
                                        val stroke = 3.5.dp.toPx()
                                        drawCircle(
                                            color = if (isDark) Color(0xFF3A3B44) else Color(0xFFE5E7EB),
                                            style = Stroke(width = stroke)
                                        )
                                        if (progress > 0f) {
                                            drawArc(
                                                color = AccentBlue,
                                                startAngle = -90f,
                                                sweepAngle = 360f * progress,
                                                useCenter = false,
                                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                                            )
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "${(progress * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            ),
                                            color = Color(0xFF101114)
                                        )
                                        Text(
                                            text = "$doneSubs/$totalSubs",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            color = Color(0xFF6B7280)
                                        )
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                HorizontalDivider(thickness = 0.5.dp, color = if (isDark) Color(0xFF2E2F38) else Color(0xFFE5E7EB))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    AssistChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = task.dueAt?.let { formatDate(it) } ?: "Sep 16, 2026 • 1:51 AM",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                color = Color(0xFF374151)
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Filled.CalendarToday, null, modifier = Modifier.size(14.dp), tint = AccentBlue)
                                        },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = Color.White,
                                            labelColor = Color(0xFF374151)
                                        ),
                                        shape = RoundedCornerShape(100.dp)
                                    )
                                    val priorityColor = when (task.priority) {
                                        Priority.LOW -> Color(0xFF6B7280)
                                        Priority.MEDIUM -> Color(0xFF3B82F6)
                                        Priority.HIGH -> Color(0xFFF59E0B)
                                        Priority.URGENT -> Color(0xFFEF4444)
                                    }
                                    AssistChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = task.priority.name.lowercase().replaceFirstChar { it.uppercase() },
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = priorityColor
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Flag, null, modifier = Modifier.size(14.dp), tint = priorityColor)
                                        },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = Color.White,
                                            labelColor = priorityColor
                                        ),
                                        shape = RoundedCornerShape(100.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Sub-tasks – use color combo from setting for overall dark mode theme, leave light mode as is
                    var editingSubId by remember { mutableStateOf<String?>(null) }
                    var editingSubText by remember { mutableStateOf("") }
                    var isSubTasksExpanded by remember { mutableStateOf(false) }
                    val subCardBg = if (isDark) Color(0xFF1E2A44) else Color(0xFFE3F5FF)
                    val subTextPrimary = if (isDark) Color.White else Color(0xFF101114)
                    val subTextSecondary = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = subCardBg),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column {
                            // Show first 5 tasks before showing down arrow if list exceeds 5 items per request
                            val displaySubs = if (task.subTasks.size > 5 && !isSubTasksExpanded) task.subTasks.take(5) else task.subTasks
                            // Scrollable container for sub-tasks – max height 320dp – consistent with All Tasks cards #E3F5FF
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                            displaySubs.forEach { sub ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                        .combinedClickable(onClick = { viewModel.toggleSubTask(sub.id) })
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                ) {
                                    // Checkbox – larger per request – use 1st image for consistency
                                    SubTaskRoundedCheckbox(
                                        checked = sub.isDone,
                                        onCheckedChange = { viewModel.toggleSubTask(sub.id) },
                                        modifier = Modifier.size(28.dp)
                                    )
                                    if (editingSubId == sub.id) {
                                        OutlinedTextField(
                                            value = editingSubText,
                                            onValueChange = { editingSubText = it },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        IconButton(onClick = {
                                            viewModel.editSubTask(sub.id, editingSubText)
                                            editingSubId = null
                                        }, modifier = Modifier.size(36.dp)) {
                                            Text("✓", color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                        }
                                        IconButton(onClick = { editingSubId = null }, modifier = Modifier.size(36.dp)) {
                                            Text("×", fontSize = 18.sp)
                                        }
                                    } else {
                                        // Remove red lines per request – only title – larger icons consistent with 1st image
                                        Text(
                                            text = sub.title,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                lineHeight = 18.sp,
                                                textDecoration = if (sub.isDone) TextDecoration.LineThrough else null
                                            ),
                                            color = if (sub.isDone) subTextSecondary.copy(alpha = 0.6f) else subTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        // Edit icon – use this icon and size from all tasks to edit task per request – image-1 ggadg asdgasg asdgasgd with pencil icon
                                        IconButton(onClick = {
                                            editingSubId = sub.id
                                            editingSubText = sub.title
                                        }, modifier = Modifier.size(48.dp)) {
                                            Icon(Icons.Filled.Edit, null, modifier = Modifier.size(24.dp), tint = AccentBlue)
                                        }
                                        // Delete icon – consistent with all tasks delete trash red 24dp
                                        IconButton(onClick = { viewModel.deleteSubTask(sub.id) }, modifier = Modifier.size(48.dp)) {
                                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(20.dp), tint = Color(0xFF6B7280))
                                        }
                                    }
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.8.dp,
                                    color = Color.Gray.copy(alpha = 0.3f)
                                )
                            }
                            }

                            // CTA with overlapping arrow down btn per request – corner radius 24.dp – show arrow only if exceeds 5
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(top = 12.dp)
                                        .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                                        .background(AccentBlue)
                                        .clickable { showAddSheet = true }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Filled.Edit, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                        Text(
                                            text = "Add task",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 14.sp
                                            )
                                        )
                                    }
                                }
                                // Arrow down btn overlapping CTA – only show if list exceeds 5 items per request – dark mode adapted – larger icons per request
                                if (task.subTasks.size > 5) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .offset(y = (-14).dp)
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(if (isDark) MaterialTheme.colorScheme.surface else Color.White)
                                            .clickable { isSubTasksExpanded = !isSubTasksExpanded }
                                            .padding(4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isSubTasksExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                            contentDescription = if (isSubTasksExpanded) "Collapse" else "Expand",
                                            tint = AccentBlue,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(100.dp))
                }

                if (showAddSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showAddSheet = false },
                        sheetState = addSheetState,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        dragHandle = null
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                                Box(modifier = Modifier.width(40.dp).height(4.dp).background(Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(100.dp)))
                            }
                            Text("Add new task", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                            Text("to ${task.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                value = newSubTaskTitle,
                                onValueChange = { newSubTaskTitle = it },
                                label = { Text("Task name") },
                                placeholder = { Text("e.g. Make notes") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                singleLine = true
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = { showAddSheet = false }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = {
                                        if (newSubTaskTitle.isNotBlank()) viewModel.addSubTask(newSubTaskTitle.trim())
                                        showAddSheet = false
                                        newSubTaskTitle = ""
                                    },
                                    enabled = newSubTaskTitle.isNotBlank(),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                                ) {
                                    Text("Add")
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            } ?: run {
                Box(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp), contentAlignment = Alignment.Center) {
                    Text("Task not found")
                }
            }
        }
    }
}

private fun formatDate(millis: Long): String {
    return try {
        val fmt = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
        fmt.format(Date(millis))
    } catch (e: Exception) {
        "Invalid date"
    }
}
