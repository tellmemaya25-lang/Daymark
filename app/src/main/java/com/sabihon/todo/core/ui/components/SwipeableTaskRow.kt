package com.sabihon.todo.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.domain.model.SubTask
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Swipeable task row.
 *
 * ### Shape of the gesture (see docs/swipe-motion-plan.md for the research)
 *
 * **Edge means something.** Swiping right reveals **Delete** — it is the end-aligned,
 * destructive action, so a full swipe right runs it (with the undo snackbar behind).
 * Swiping left reveals **Edit**, a safe action that cannot be triggered by accident.
 * The previous version revealed the same two actions on both sides, which is the
 * ambiguity the platform guidance warns about: the same gesture meant two things.
 *
 * ### Shape of the reveal
 *
 * The actions are **full-height tinted slabs**, not circles parked in a gutter. A slab
 * carries an icon over an 11 sp label, so the action names itself instead of relying on
 * a glyph; it is a 48 dp+ target the moment it is visible; and because it fills the
 * row's height the reveal reads as the row *peeling* rather than as a second container
 * appearing underneath a rounded card.
 *
 * Motion:
 * - reveal anchors at one 76 dp slab; open threshold at 50% of that
 * - release opens on position **or** velocity (800 dp/s), so a flick works
 * - dragging past the anchor rubber-bands at 35% instead of stopping dead
 * - a full swipe (132 dp) commits that edge's action outright
 * - one row open per list via [RevealCoordinator]; scrolling folds everything back
 * - one haptic tick when the 50% threshold is crossed, not per frame
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
    // One slab's width - the whole reveal for that edge.
    val revealPx = with(density) { SlabWidth.toPx() }
    val commitPx = revealPx / 2f                                  // 50% of travel
    val velocityCommit = with(density) { 800.dp.toPx() }          // px/s
    val overscrollMax = with(density) { 28.dp.toPx() }
    val rubberBand = 0.35f
    // Deep swipe that commits that edge's action outright.
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
    val closeSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )
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

    // 0 → 1 across the reveal distance; fades the slab in.
    val revealFraction = (abs(visual) / revealPx).coerceIn(0f, 1f)

    val cardScale by animateFloatAsState(
        targetValue = if (isDragging) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "swipeCardScale"
    )

    val surfaces = LocalDaymarkSurfaces.current
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(shape)
            .background(surfaces.card)
    ) {
        // --- the slabs, one per edge ---------------------------------------
        // Right swipe reveals Delete on the START edge - the gesture uncovers what sits
        // under the card's leading edge. Left swipe reveals Edit on the end edge. One
        // action per direction, and the destructive one is the one a full swing runs.
        if (visual > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
            ) {
                SwipeActionSlab(
                    icon = Icons.Filled.Delete,
                    label = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    background = surfaces.cardHighlighted,
                    revealFraction = revealFraction,
                    onClick = {
                        close()
                        onDelete?.invoke()
                    }
                )
            }
        }
        if (visual < 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
            ) {
                SwipeActionSlab(
                    icon = Icons.Filled.Edit,
                    label = "Edit",
                    tint = surfaces.action,
                    background = surfaces.cardHighlighted,
                    revealFraction = revealFraction,
                    onClick = {
                        close()
                        onEdit?.invoke()
                    }
                )
            }
        }

        // --- the card -------------------------------------------------------
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

                            // Deep swipe runs this edge's action outright - delete to
                            // the right, edit to the left. Never both.
                            if (abs(travelled) >= fullSwipePx) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                isOpen = false
                                rowId?.let { coordinator?.onClosed(it) }
                                raw.snapTo(0f)
                                if (travelled > 0f) onDelete?.invoke() else onEdit?.invoke()
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

/* ------------------------------------------------------------------ */
/* Reveal                                                              */
/* ------------------------------------------------------------------ */

/** Width of a single revealed action. iOS anchors 80 dp; 76 keeps two rows' worth of
 *  lift on a 360 dp screen while still fitting "Delete" at 11 sp. */
private val SlabWidth = 76.dp

/**
 * One revealed action: a full-height slab with the icon over its name.
 *
 * The label is the point. An icon-only circle has to be recognised; a slab that says
 * "Delete" only has to be read. It is a real target from the moment it is visible -
 * the row has already been dragged to reveal it, so there is no reason to make the
 * first tap a no-op.
 */
@Composable
private fun SwipeActionSlab(
    icon: ImageVector,
    label: String,
    tint: Color,
    background: Color,
    revealFraction: Float,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(SlabWidth)
            .fillMaxHeight()
            .background(background)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                // Fades and settles rather than growing from nothing: the slab is
                // already the right size by the time the label is legible.
                alpha = 0.35f + 0.65f * revealFraction
                scaleX = 0.9f + 0.1f * revealFraction
                scaleY = scaleX
            },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                // The whole row already carries a semantic click action for the task;
                // the slab's label below names this action, so the glyph is decorative.
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = tint,
            maxLines = 1
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
