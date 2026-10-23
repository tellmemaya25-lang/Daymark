package com.sabihon.todo.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.LocalIsDarkTheme
import com.sabihon.todo.domain.model.SubTask
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Swipeable task row – reveal Edit / Delete behind the card.
 *
 * - Drag horizontally; release past ±80dp (or flick faster than 1200px/s) to latch open
 * - The action icons fade and scale in with the drag instead of snapping into view
 * - A haptic tick lands the moment the reveal threshold is crossed
 * - The card dips very slightly while dragging so it feels picked up, and the
 *   settle uses a springy spec so it overshoots a hair on release
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
    // ~1200 px/s on a 3x screen – a deliberate flick, not a slow drag.
    val flingVelocity = with(density) { 400.dp.toPx() }

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }

    // Springy settle so a released card overshoots slightly instead of stopping dead.
    val settleSpec = spring<Float>(dampingRatio = 0.58f, stiffness = Spring.StiffnessMediumLow)
    val closeSpec = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

    fun close() = scope.launch { offsetX.animateTo(0f, closeSpec) }

    LaunchedEffect(isCompleted) {
        if (offsetX.value != 0f) close()
    }

    // 0 → 1 as the card slides towards the reveal distance; drives icon alpha/scale.
    val revealFraction = (abs(offsetX.value) / startThreshold).coerceIn(0f, 1f)
    val pastThreshold = offsetX.value > startThreshold || offsetX.value < endThreshold

    LaunchedEffect(pastThreshold) {
        if (pastThreshold) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    val cardScale by animateFloatAsState(
        targetValue = if (isDragging) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "swipeCardScale"
    )

    val isDark = LocalIsDarkTheme.current
    val swipeBg = if (isDark) Color(0xFF23262F) else Color(0xFFE7EDFB)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(swipeBg)
    ) {
        // Revealed actions – kept on the edge the card is sliding away from.
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
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer {
                    alpha = 0.1f + 0.9f * revealFraction
                    val s = 0.78f + 0.22f * revealFraction
                    scaleX = s
                    scaleY = s
                }
            ) {
                IconButton(
                    onClick = {
                        close()
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
                        close()
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
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                }
                .draggable(
                    state = rememberDraggableState { delta ->
                        val newValue = (offsetX.value + delta).coerceIn(maxEnd, maxStart)
                        scope.launch { offsetX.snapTo(newValue) }
                    },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { isDragging = true },
                    onDragStopped = { velocity ->
                        isDragging = false
                        scope.launch {
                            val target = when {
                                offsetX.value > startThreshold || velocity > flingVelocity -> maxStart
                                offsetX.value < endThreshold || velocity < -flingVelocity -> maxEnd
                                else -> 0f
                            }
                            offsetX.animateTo(
                                targetValue = target,
                                animationSpec = if (target == 0f) closeSpec else settleSpec,
                                initialVelocity = velocity
                            )
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
                        close()
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
