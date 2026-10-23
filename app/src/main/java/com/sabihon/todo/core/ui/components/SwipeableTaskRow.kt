package com.sabihon.todo.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sabihon.todo.core.ui.theme.LocalIsDarkTheme
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.domain.model.SubTask
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Swipeable task row – home page swipe right to edit/delete + use color combo from setting for dark mode
 * - Supports BOTH swipe left and swipe right to reveal edit/delete per request
 * - Gesture: draggable horizontal, thresholds ±80dp, reveal 160dp
 * - Icons centered vertically fill height 56dp, no bg, AccentBlue / #E57373, 24.dp clip outer
 * - Background aligned to revealed side so icons are visible
 * - Bg of task: light #e3f5ff per image1, dark #1E2A44 per Settings dark combo for overall dark mode theme, leave light mode as is
 */
@Composable
fun SwipeableTaskRow(
    title: String,
    isCompleted: Boolean,
    timeLabel: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    subTasks: List<SubTask> = emptyList(),
    categoryLabel: String? = null,
    priorityLabel: String? = null,
    priorityDotColor: Color? = null,
    isHighlighted: Boolean = false,
    onCheckedChange: (Boolean) -> Unit = {},
    onAddSubTask: (() -> Unit)? = null,
    onSubTaskChecked: ((String, Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onToggleComplete: ((Boolean) -> Unit)? = null
) {
    val density = LocalDensity.current
    val startThreshold = with(density) { 80.dp.toPx() }
    val endThreshold = with(density) { -80.dp.toPx() }
    val maxStart = with(density) { 160.dp.toPx() }
    val maxEnd = with(density) { -160.dp.toPx() }

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(isCompleted) {
        if (offsetX.value != 0f) {
            scope.launch {
                offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
        }
    }

    // Bg of task: light #e3f5ff per image1, dark #1E2A44 per Settings dark combo for overall dark mode theme, leave light mode as is
    val isDark = LocalIsDarkTheme.current
    val swipeBg = if (isDark) Color(0xFF23262F) else Color(0xFFE7EDFB)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(swipeBg)
    ) {
        // Background – edit/delete, visible on swipe right (left side) and swipe left (right side)
        // Aligned to revealed edge so icons are visible even with 160dp offset, centered vertically 56dp
        // Bg uses Settings dark combo for dark mode
        val bgAlignment = when {
            offsetX.value > 10f -> Alignment.CenterStart
            offsetX.value < -10f -> Alignment.CenterEnd
            else -> Alignment.Center
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(swipeBg)
                .padding(horizontal = 20.dp, vertical = 4.dp),
            contentAlignment = bgAlignment
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                        onEdit?.invoke()
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit",
                        tint = AccentBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                        onDelete?.invoke()
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = Color(0xFFE57373),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    state = rememberDraggableState { delta ->
                        val newValue = (offsetX.value + delta).coerceIn(maxEnd, maxStart)
                        scope.launch { offsetX.snapTo(newValue) }
                    },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { isDragging = true },
                    onDragStopped = {
                        isDragging = false
                        scope.launch {
                            when {
                                offsetX.value > startThreshold -> {
                                    offsetX.animateTo(maxStart, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                }
                                offsetX.value < endThreshold -> {
                                    offsetX.animateTo(maxEnd, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                }
                                else -> {
                                    offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                }
                            }
                        }
                    }
                )
        ) {
            TaskRow(
                title = title,
                isCompleted = isCompleted,
                timeLabel = timeLabel,
                description = description,
                subTasks = subTasks,
                categoryLabel = categoryLabel,
                priorityLabel = priorityLabel,
                priorityDotColor = priorityDotColor,
                isHighlighted = isHighlighted,
                hasOuterPadding = false,
                onCheckedChange = onCheckedChange,
                onAddSubTask = onAddSubTask,
                onSubTaskChecked = onSubTaskChecked,
                onClick = {
                    if (offsetX.value != 0f) {
                        scope.launch {
                            offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    } else {
                        onClick?.invoke()
                    }
                },
                onLongClick = {
                    if (offsetX.value == 0f) {
                        onLongClick?.invoke()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
