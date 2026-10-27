package com.sabihon.todo.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.components.WeekStrip
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.DaymarkSheetShape
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val MONTH_YEAR_FORMATTER = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val SHORT_MONTH_YEAR_FORMATTER = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
private val WEEKDAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * Floating weekly calendar bar that glides down from the inline calendar slot and docks
 * directly above the bottom navigation bar once scrolling reaches the weekly strip.
 *
 * Gestures (`swipe left / right` to move forward/backward in the calendar and
 * `swipe upward` to expand the full monthly calendar) are gated on [isDockedAtBottom]
 * so they only arm after the downward docking animation has finished.
 */
@Composable
fun DockedWeeklyCalendarBar(
    days: List<WeekDay>,
    selectedDate: LocalDate,
    isDockedAtBottom: Boolean,
    onSelectDate: (LocalDate) -> Unit,
    onShiftDays: (Long) -> Unit,
    onExpandMonthCalendar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val surfaces = LocalDaymarkSurfaces.current
    val haptics = LocalHapticFeedback.current

    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    val visualOffsetX by animateFloatAsState(
        targetValue = if (isDockedAtBottom) (dragX * 0.22f).coerceIn(-36f, 36f) else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dockVisualDragX"
    )
    val visualOffsetY by animateFloatAsState(
        targetValue = if (isDockedAtBottom) (dragY * 0.20f).coerceIn(-28f, 10f) else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dockVisualDragY"
    )

    val weekMonday = remember(selectedDate) { selectedDate.with(DayOfWeek.MONDAY) }
    val dockShape = RoundedCornerShape(22.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .graphicsLayer {
                translationX = visualOffsetX
                translationY = visualOffsetY
            }
            .shadow(
                elevation = 8.dp,
                shape = dockShape,
                ambientColor = Color.Black.copy(alpha = 0.20f),
                spotColor = Color.Black.copy(alpha = 0.20f)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = dockShape
            )
            .pointerInput(selectedDate, isDockedAtBottom) {
                if (!isDockedAtBottom) return@pointerInput
                val horizontalThreshold = 38.dp.toPx()
                val verticalUpThreshold = 34.dp.toPx()
                var totalX = 0f
                var totalY = 0f

                detectDragGestures(
                    onDragStart = {
                        totalX = 0f
                        totalY = 0f
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragCancel = {
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragEnd = {
                        val dx = totalX
                        val dy = totalY
                        dragX = 0f
                        dragY = 0f

                        if (abs(dy) > abs(dx) && dy < -verticalUpThreshold) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onExpandMonthCalendar()
                        } else if (abs(dx) >= abs(dy)) {
                            when {
                                // Swipe left -> move forward in the calendar (+1 week)
                                dx < -horizontalThreshold -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onShiftDays(7L)
                                }
                                // Swipe right -> move backward in the calendar (-1 week)
                                dx > horizontalThreshold -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onShiftDays(-7L)
                                }
                            }
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    totalX += dragAmount.x
                    totalY += dragAmount.y
                    dragX = totalX
                    dragY = totalY
                }
            },
        shape = dockShape,
        color = surfaces.card,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Subtle pull-up header cue – also tappable to open the full month calendar.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .clickable(enabled = isDockedAtBottom) { onExpandMonthCalendar() }
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Open monthly calendar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = selectedDate.format(SHORT_MONTH_YEAR_FORMATTER),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }

            AnimatedContent(
                targetState = weekMonday,
                transitionSpec = {
                    val forward = targetState.isAfter(initialState)
                    val enter = slideInHorizontally(tween(240)) { width ->
                        if (forward) width / 2 else -width / 2
                    } + fadeIn(tween(200))
                    val exit = slideOutHorizontally(tween(220)) { width ->
                        if (forward) -width / 2 else width / 2
                    } + fadeOut(tween(160))
                    (enter togetherWith exit).using(SizeTransform(clip = false))
                },
                label = "dockedWeekSlide"
            ) { targetMonday ->
                val sliceDays = remember(targetMonday, days) {
                    if (days.firstOrNull()?.date == targetMonday) {
                        days
                    } else {
                        val today = LocalDate.now()
                        (0..6).map { offset ->
                            val d = targetMonday.plusDays(offset.toLong())
                            WeekDay(
                                date = d,
                                letter = d.dayOfWeek.name.take(1),
                                dayOfMonth = d.dayOfMonth,
                                isToday = d == today,
                                hasTasks = false
                            )
                        }
                    }
                }
                WeekStrip(
                    days = sliceDays,
                    selected = selectedDate,
                    onSelect = onSelectDate
                )
            }
        }
    }
}

/**
 * Full monthly calendar sheet opened when swiping upward on the bottom-docked weekly
 * calendar bar. Supports tapping any date, jumping back to Today, and swiping left/right
 * (or tapping arrows) to browse months.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthlyCalendarSheet(
    selectedDate: LocalDate,
    daysWithTasks: Set<Long>,
    onSelectDate: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    val haptics = LocalHapticFeedback.current
    val today = remember { LocalDate.now() }
    var displayedMonth by remember(selectedDate) {
        mutableStateOf(YearMonth.from(selectedDate))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = DaymarkSheetShape,
        containerColor = surfaces.card,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .pointerInput(displayedMonth) {
                    val threshold = 42.dp.toPx()
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            when {
                                dragged < -threshold -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    displayedMonth = displayedMonth.plusMonths(1)
                                }
                                dragged > threshold -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    displayedMonth = displayedMonth.minusMonths(1)
                                }
                            }
                        }
                    ) { change, amount ->
                        change.consume()
                        dragged += amount
                    }
                }
        ) {
            // Month header + Today pill + Prev/Next month arrows
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayedMonth.atDay(1).format(MONTH_YEAR_FORMATTER),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                if (selectedDate != today || displayedMonth != YearMonth.from(today)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(surfaces.accentSurface)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                displayedMonth = YearMonth.from(today)
                                onSelectDate(today)
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Today",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            ),
                            color = surfaces.onAccentSurface
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }

                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        displayedMonth = displayedMonth.minusMonths(1)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous month",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        displayedMonth = displayedMonth.plusMonths(1)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next month",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Weekday column labels (Monday..Sunday)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                WEEKDAY_LETTERS.forEach { letter ->
                    Text(
                        text = letter,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Animated 6-week grid for the displayed month
            AnimatedContent(
                targetState = displayedMonth,
                transitionSpec = {
                    val forward = targetState.isAfter(initialState)
                    val enter = slideInHorizontally(tween(240)) { width ->
                        if (forward) width / 2 else -width / 2
                    } + fadeIn(tween(200))
                    val exit = slideOutHorizontally(tween(220)) { width ->
                        if (forward) -width / 2 else width / 2
                    } + fadeOut(tween(160))
                    (enter togetherWith exit).using(SizeTransform(clip = false))
                },
                label = "monthlyGridSlide"
            ) { month ->
                val gridCells = remember(month) { buildMonthGridDates(month) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    gridCells.chunked(7).forEach { weekRow ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            weekRow.forEach { date ->
                                val inCurrentMonth = date.month == month.month && date.year == month.year
                                val isSelected = date == selectedDate
                                val isToday = date == today
                                val hasTasks = date.toEpochDay() in daysWithTasks

                                MonthDayCell(
                                    date = date,
                                    inCurrentMonth = inCurrentMonth,
                                    isSelected = isSelected,
                                    isToday = isToday,
                                    hasTasks = hasTasks,
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectDate(date)
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthDayCell(
    date: LocalDate,
    inCurrentMonth: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    hasTasks: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val surfaces = LocalDaymarkSurfaces.current
    val bgColor = when {
        isSelected -> AccentBlue
        isToday -> surfaces.accentSurface
        else -> Color.Transparent
    }
    val textColor = when {
        isSelected -> Color.White
        isToday -> surfaces.onAccentSurface
        inCurrentMonth -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    val dotColor = when {
        !hasTasks -> Color.Transparent
        isSelected -> Color.White.copy(alpha = 0.9f)
        else -> AccentBlue
    }

    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "${date.dayOfMonth}",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Medium
            ),
            color = textColor
        )
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

/**
 * Builds the 42-day (6-week, Monday-first) grid covering [month] so adjacent months
 * never cause the bottom sheet height to jump during horizontal swipe transitions.
 */
private fun buildMonthGridDates(month: YearMonth): List<LocalDate> {
    val firstOfMonth = month.atDay(1)
    val startMonday = firstOfMonth.with(DayOfWeek.MONDAY)
    return (0 until 42).map { offset -> startMonday.plusDays(offset.toLong()) }
}
