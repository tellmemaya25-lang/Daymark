package com.sabihon.todo.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.theme.LocalIsDarkTheme
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.domain.model.SubTask

/**
 * daymark task card.
 *
 * Layout mirrors the task-list reference: checkbox, bold title, description,
 * meta chips (category · due · priority), a hairline sub-task progress bar with
 * "2/3 subtasks (67%)", and a chevron + priority dot on the right.
 *
 * Swipe actions stay in [SwipeableTaskRow], which wraps this card.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    title: String,
    isCompleted: Boolean,
    timeLabel: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    subTasks: List<SubTask> = emptyList(),
    categoryLabel: String? = null,
    priorityLabel: String? = null,
    /**
     * Habit marker, e.g. "Daily" or "4 in a row". Rendered after the priority chip
     * so the calendar icon's positional check below stays valid.
     */
    repeatLabel: String? = null,
    priorityDotColor: Color? = null,
    isHighlighted: Boolean = false,
    hasOuterPadding: Boolean = true,
    onCheckedChange: (Boolean) -> Unit = {},
    onAddSubTask: (() -> Unit)? = null,
    onSubTaskChecked: ((String, Boolean) -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val isDark = LocalIsDarkTheme.current

    // The chevron is a real dropdown: it reveals the sub-task checklist.
    var expanded by remember(title, subTasks.size) { mutableStateOf(false) }
    val canExpand = subTasks.isNotEmpty()
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "chevronRotation"
    )

    val totalSubs = subTasks.size
    val doneSubs = subTasks.count { it.isDone }
    val hasSubTasks = totalSubs > 0
    val effectiveCompleted = if (hasSubTasks) doneSubs == totalSubs else isCompleted

    // Surfaces come from the animated token set rather than from `if (isDark)`, so the
    // card fades with the canvas during a theme switch instead of snapping to near-black
    // halfway through it.
    val surfaces = LocalDaymarkSurfaces.current

    val cardColor by animateColorAsState(
        targetValue = if (isHighlighted) surfaces.cardHighlighted else surfaces.card,
        animationSpec = tween(260),
        label = "taskCardColor"
    )

    val borderColor = if (isHighlighted) {
        AccentBlue.copy(alpha = 0.6f)
    } else {
        surfaces.cardBorder
    }

    val titleColor = when {
        effectiveCompleted -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (hasOuterPadding) Modifier.padding(horizontal = 20.dp, vertical = 5.dp) else Modifier)
            .clip(RoundedCornerShape(20.dp))
            .background(cardColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = { onLongClick?.invoke() ?: onMoreClick?.invoke() }
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            CompactCheckbox(
                checked = effectiveCompleted,
                onCheckedChange = { onCheckedChange(it) }
            )

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        lineHeight = 21.sp
                    ),
                    color = titleColor,
                    textDecoration = if (effectiveCompleted) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val chips = buildList {
                    categoryLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
                    timeLabel.takeIf { it.isNotBlank() }?.let { add(it) }
                    priorityLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
                    // Appended last: the chip row keys the calendar icon off index 1.
                    repeatLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
                }

                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    // Wraps rather than overflows. A row of four chips (category, due,
                    // priority, habit) does not fit across a narrow card, and a plain Row
                    // squeezed the last one to a single letter underneath the chevron.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        chips.forEachIndexed { index, label ->
                            MetaChip(
                                label = label,
                                showCalendar = index == 1 && timeLabel.isNotBlank(),
                                isDark = isDark
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (hasSubTasks) {
                    val rawProgress = doneSubs.toFloat() / totalSubs
                    val progress by animateFloatAsState(
                        targetValue = rawProgress,
                        animationSpec = tween(durationMillis = 600),
                        label = "subTaskProgress"
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(92.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress)
                                    .clip(CircleShape)
                                    .background(if (effectiveCompleted) Color(0xFF4CAF50) else AccentBlue)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "$doneSubs/$totalSubs subtasks (${(rawProgress * 100).toInt()}%)",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (effectiveCompleted) Color(0xFF4CAF50) else AccentBlue
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (effectiveCompleted) "Completed" else "Pending",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = if (effectiveCompleted) {
                                Color(0xFF4CAF50)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (priorityDotColor != null) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(priorityDotColor)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = when {
                        canExpand && expanded -> "Hide sub-tasks"
                        canExpand -> "Show sub-tasks"
                        onMoreClick != null -> "Task options"
                        else -> null
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        // Nothing to expand and no menu behind it – fade it so it doesn't look tappable.
                        alpha = if (canExpand || onMoreClick != null) 0.7f else 0.28f
                    ),
                    modifier = Modifier
                        .size(28.dp)
                        .rotate(chevronRotation)
                        .then(
                            when {
                                canExpand -> Modifier.clickable(
                                    role = Role.Button,
                                    onClick = { expanded = !expanded }
                                )
                                onMoreClick != null -> Modifier.clickable(
                                    role = Role.Button,
                                    onClick = { onMoreClick() }
                                )
                                else -> Modifier
                            }
                        )
                        .padding(4.dp)
                )
            }
        }

        // Dropdown: the sub-task checklist
        AnimatedVisibility(
            visible = expanded && canExpand,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.padding(start = 38.dp, top = 10.dp)) {
                subTasks.forEachIndexed { index, subTask ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .height(0.5.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        )
                    }
                    SubTaskRow(
                        title = subTask.title,
                        isDone = subTask.isDone,
                        enabled = onSubTaskChecked != null,
                        onToggle = {
                            onSubTaskChecked?.invoke(subTask.id, !subTask.isDone)
                        }
                    )
                }
            }
        }
    }
}

/**
 * One line of the expanded checklist – small checkbox + title.
 */
@Composable
private fun SubTaskRow(
    title: String,
    isDone: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Checkbox, onClick = onToggle)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fill by animateColorAsState(
            targetValue = if (isDone) AccentBlue else Color.Transparent,
            animationSpec = tween(180),
            label = "subFill"
        )
        val ring by animateColorAsState(
            targetValue = if (isDone) AccentBlue else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
            animationSpec = tween(180),
            label = "subRing"
        )

        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, ring, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = if (isDone) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (isDone) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 24.dp visual circle in a 40.dp touch target – matches the reference checkbox.
 */
@Composable
private fun CompactCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.98f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "checkboxScale"
    )
    val fill by animateColorAsState(
        targetValue = if (checked) AccentBlue else Color.Transparent,
        animationSpec = tween(200),
        label = "checkboxFill"
    )
    val border by animateColorAsState(
        targetValue = if (checked) AccentBlue else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        animationSpec = tween(200),
        label = "checkboxBorder"
    )

    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) },
        contentAlignment = Alignment.TopStart
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp, start = 2.dp)
                .size(24.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, border, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Completed",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun MetaChip(
    label: String,
    showCalendar: Boolean,
    isDark: Boolean
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (isDark) Color.White.copy(alpha = 0.07f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showCalendar) {
            Icon(
                Icons.Filled.CalendarToday,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(11.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            // Belt and braces: a chip longer than the whole card should end in an
            // ellipsis, never be cut mid-word.
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
