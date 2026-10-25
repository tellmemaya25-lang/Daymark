package com.sabihon.todo.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.LocalIsDarkTheme
import com.sabihon.todo.domain.model.SubTask
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Swipeable task row – reveal Edit / Delete behind the card.
 *
 * Motion rules (see docs/swipe-motion-plan.md):
 * - reveal anchors at ±160 dp, commit threshold at 50% of that
 * - release commits on position **or** velocity (800 dp/s), so a quick flick works
 * - dragging past the anchor rubber-bands at 35% instead of hitting a wall
 * - a full swipe (75% of the row) runs the primary action: delete, with undo
 * - one row open per list via [RevealCoordinator]; scrolling folds everything back
 * - the reveal colours up and rings its icons once it will commit, and ticks haptically
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
    repeatLabel: String? = null,
    priorityDotColor: Color? = null,
    isHighlighted: Boolean = false,
    onCheckedChange: (Boolean) -> Unit = {},
    onAddSubTask: (() -> Unit)? = null,
    onSubTaskChecked: ((String, Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    rowId: String? = null,
    coordinator: RevealCoordinator? = null
) {
    val density = LocalDensity.current
    // The two actions stack, so the panel only needs one button's width.
    val revealPx = with(density) { 68.dp.toPx() }
    val commitPx = revealPx / 2f                                  // 50% of travel
    val velocityCommit = with(density) { 800.dp.toPx() }          // px/s
    val overscrollMax = with(density) { 28.dp.toPx() }
    val rubberBand = 0.35f
    // Deep swipe that commits the primary action outright. Expressed against the
    // reveal distance rather than the row width so it behaves the same on any screen.
    val fullSwipePx = with(density) { 132.dp.toPx() }
    // Furthest the finger can take the card, allowing for the damped overscroll.
    val rawMax = fullSwipePx + overscrollMax / rubberBand
    var isDragging by remember { mutableStateOf(false) }
    var isOpen by remember { mutableStateOf(false) }
    var crossedCommit by remember { mutableStateOf(false) }

    // Raw drag distance; what gets drawn is `raw` passed through the rubber band.
    val raw = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val settleSpec = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    val closeSpec = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
    val visual = damp(raw.value, revealPx, rubberBand, overscrollMax)

    fun close() {
        isOpen = false
        rowId?.let { coordinator?.onClosed(it) }
        scope.launch { raw.animateTo(0f, closeSpec) }
    }

    // Only one revealed row per list.
    val openRowId = coordinator?.openRowId
    LaunchedEffect(openRowId) {
        if (isOpen && rowId != null && openRowId != rowId) close()
    }

    LaunchedEffect(isCompleted) {
        if (raw.value != 0f) close()
    }

    // Haptic tick once per crossing, not once per frame.
    val pastCommit = abs(raw.value) >= commitPx
    LaunchedEffect(pastCommit) {
        if (pastCommit && !crossedCommit) {
            crossedCommit = true
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else if (!pastCommit) {
            crossedCommit = false
        }
    }

    // 0 → 1 across the reveal distance; drives icon fade/scale.
    val revealFraction = (abs(visual) / commitPx).coerceIn(0f, 1f)
    // 0 → 1 between "will commit" and "fully revealed"; drives the ring + colour.
    val commitFraction = ((abs(visual) - commitPx) / (revealPx - commitPx)).coerceIn(0f, 1f)

    val cardScale by animateFloatAsState(
        targetValue = if (isDragging) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "swipeCardScale"
    )

    val isDark = LocalIsDarkTheme.current
    val swipeBg = if (isDark) Color(0xFF23262F) else Color(0xFFE7EDFB)
    val ringColor = if (isDark) Color.White.copy(alpha = 0.55f) else AccentBlue.copy(alpha = 0.65f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(swipeBg)
    ) {
        val bgAlignment = when {
            visual > 10f -> Alignment.CenterStart
            visual < -10f -> Alignment.CenterEnd
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.graphicsLayer {
                    alpha = 0.1f + 0.9f * revealFraction
                    val s = 0.78f + 0.22f * revealFraction + 0.06f * commitFraction
                    scaleX = s
                    scaleY = s
                }
            ) {
                SwipeAction(
                    icon = Icons.Filled.Edit,
                    label = "Edit",
                    tint = AccentBlue,
                    revealFraction = revealFraction,
                    commitFraction = commitFraction,
                    ringColor = ringColor,
                    onClick = {
                        close()
                        onEdit?.invoke()
                    }
                )
                SwipeAction(
                    icon = Icons.Filled.Delete,
                    label = "Delete",
                    tint = Color(0xFFE57373),
                    revealFraction = revealFraction,
                    commitFraction = commitFraction,
                    ringColor = ringColor,
                    onClick = {
                        close()
                        onDelete?.invoke()
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(visual.roundToInt(), 0) }
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                }
                .draggable(
                    state = rememberDraggableState { delta ->
                        val next = (raw.value + delta).coerceIn(-rawMax, rawMax)
                        scope.launch { raw.snapTo(next) }
                    },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { isDragging = true },
                    onDragStopped = { velocity ->
                        isDragging = false
                        scope.launch {
                            val travelled = raw.value

                            // Deep swipe runs the primary action outright.
                            if (abs(travelled) >= fullSwipePx) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                isOpen = false
                                rowId?.let { coordinator?.onClosed(it) }
                                raw.snapTo(0f)
                                onDelete?.invoke()
                                return@launch
                            }

                            val openRight = travelled > commitPx || velocity > velocityCommit
                            val openLeft = travelled < -commitPx || velocity < -velocityCommit
                            when {
                                openRight -> {
                                    raw.animateTo(revealPx, settleSpec, initialVelocity = velocity)
                                    isOpen = true
                                    rowId?.let { coordinator?.onOpened(it) }
                                }
                                openLeft -> {
                                    raw.animateTo(-revealPx, settleSpec, initialVelocity = velocity)
                                    isOpen = true
                                    rowId?.let { coordinator?.onOpened(it) }
                                }
                                else -> {
                                    isOpen = false
                                    rowId?.let { coordinator?.onClosed(it) }
                                    raw.animateTo(0f, closeSpec, initialVelocity = velocity)
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
                repeatLabel = repeatLabel,
                priorityLabel = priorityLabel,
                priorityDotColor = priorityDotColor,
                isHighlighted = isHighlighted,
                hasOuterPadding = false,
                onCheckedChange = onCheckedChange,
                onAddSubTask = onAddSubTask,
                onSubTaskChecked = onSubTaskChecked,
                onClick = {
                    if (raw.value != 0f) {
                        close()
                    } else {
                        onClick?.invoke()
                    }
                },
                onLongClick = {
                    if (raw.value == 0f) {
                        onLongClick?.invoke()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * One revealed action. Grows and brightens with the drag, then draws a ring the
 * moment the row will actually commit on release.
 */
@Composable
private fun SwipeAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    revealFraction: Float,
    commitFraction: Float,
    ringColor: Color,
    onClick: () -> Unit
) {
    // Two stacked circles: the ring is always drawn (as in the reference) and
    // thickens with the commit progress so the threshold is still legible.
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .border(
                width = if (commitFraction > 0f) 2.dp else 1.2.dp,
                color = if (commitFraction > 0f) {
                    ringColor.copy(alpha = 0.45f + 0.55f * commitFraction)
                } else {
                    ringColor.copy(alpha = 0.35f + 0.3f * revealFraction)
                },
                shape = CircleShape
            )
            .clickable(
                enabled = revealFraction > 0.35f,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint.copy(alpha = 0.45f + 0.55f * revealFraction),
            modifier = Modifier.size((16 + 3 * commitFraction).dp)
        )
    }
}

/** Compresses travel past [limit] so the card gives instead of stopping dead. */
private fun damp(value: Float, limit: Float, factor: Float, max: Float): Float {
    val magnitude = abs(value)
    if (magnitude <= limit) return value
    val excess = ((magnitude - limit) * factor).coerceAtMost(max)
    return (limit + excess) * sign(value)
}
