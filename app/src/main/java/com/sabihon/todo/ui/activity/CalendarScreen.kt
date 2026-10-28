package com.sabihon.todo.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.ui.components.BrandHeader
import com.sabihon.todo.core.ui.components.WeekStrip
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** One hour is 64 dp tall, so 15 minutes is 16 dp — enough for one line of title. */
private const val HOUR_HEIGHT_DP = 64f
private const val MINUTES_PER_HOUR = 60
private const val GUTTER_WIDTH_DP = 52f
private const val BLOCK_GAP_DP = 6f

private val DAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/**
 * The Activity tab's Calendar segment: a 24-hour day agenda.
 *
 * Deliberately a plain scrolling `Column`, not a `LazyColumn`. A day is 24 hour rows and
 * a handful of blocks — a few dozen composables — so virtualisation buys nothing, while
 * it would cost the one thing this screen needs: the ability to scroll to an *arbitrary
 * minute* (`ScrollState.scrollTo(px)`) instead of to an item index. That is what makes
 * "open on the first block" exact rather than a rounded guess.
 *
 * The layout itself comes from [TimelineLayout]; this file only turns rows into dp.
 * Nothing here decides *what* belongs to the day.
 */
@Composable
fun CalendarScreen(
    onTaskClick: (String) -> Unit = {},
    onProfileClick: () -> Unit = {},
    headerExtra: (@Composable () -> Unit)? = null,
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    val minutePx = with(LocalDensity.current) { HOUR_HEIGHT_DP.dp.toPx() } / MINUTES_PER_HOUR

    // Held in a local val so the segment bar can be called from inside the layout
    // lambdas below without the compiler having to smart-cast the parameter.
    val extra: (@Composable () -> Unit)? = headerExtra

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val anytime = uiState.day.anytime
    val firstTimedMinute = uiState.day.firstTimedMinute

    // Open on the first block, an hour above it so the block is not glued to the top.
    // If there is untimed work, open at the top instead: that band is the thing a user
    // most needs to see, and scrolling past it would hide it entirely.
    LaunchedEffect(uiState.selectedDate, firstTimedMinute, anytime.size) {
        if (anytime.isEmpty() && firstTimedMinute != null) {
            val target = ((firstTimedMinute - MINUTES_PER_HOUR).coerceAtLeast(0) * minutePx).roundToInt()
            scrollState.scrollTo(target)
        } else {
            scrollState.scrollTo(0)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            BrandHeader(
                avatarInitials = uiState.userInitials,
                avatarUrl = uiState.userPhotoUrl,
                onAvatarClick = onProfileClick,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)
            )

            DayTitleBlock(uiState)

            if (extra != null) {
                Box(modifier = Modifier.padding(start = 20.dp, end = 20.dp, vertical = 14.dp)) {
                    extra()
                }
            }

            WeekHeader(
                state = uiState,
                onShiftWeek = viewModel::onShiftWeek,
                onSelectDate = viewModel::onSelectDate,
                onTodayClick = viewModel::onTodayClick
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                if (anytime.isNotEmpty()) {
                    AnytimeBand(tasks = anytime, onTaskClick = onTaskClick)
                }

                if (uiState.day.isEmpty && !uiState.isLoading) {
                    EmptyDayHint()
                }

                TimelineGrid(day = uiState.day, onTaskClick = onTaskClick)

                Spacer(Modifier.height(120.dp))
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Header                                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun DayTitleBlock(state: CalendarUiState) {
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp)) {
        Text(
            text = "Small steps. Real progress.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Your day.",
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
            text = daySummary(state),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "Thu 29 Oct · 4 blocks · 2h 15m planned", or the empty-day version. */
private fun daySummary(state: CalendarUiState): String {
    val date = state.selectedDate
    val dateText = if (date == state.today) "Today · ${date.format(DAY_FORMATTER)}" else date.format(DAY_FORMATTER)
    val blocks = state.day.timedBlocks
    if (blocks.isEmpty()) {
        return if (state.day.anytime.isEmpty()) {
            "$dateText · nothing scheduled"
        } else {
            "$dateText · ${state.day.anytime.size} to place"
        }
    }
    val planned = blocks.sumOf { it.durationMinutes }
    val timed = if (blocks.size == 1) "1 block" else "${blocks.size} blocks"
    return "$dateText · $timed · ${TimelineLabels.durationLabel(planned)} planned"
}

@Composable
private fun WeekHeader(
    state: CalendarUiState,
    onShiftWeek: (Long) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onTodayClick: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArrowButton(label = "\u2039", enabled = true) { onShiftWeek(-1) }

            WeekStrip(
                days = state.weekDays,
                selected = state.selectedDate,
                onSelect = onSelectDate,
                modifier = Modifier.weight(1f)
            )

            ArrowButton(label = "\u203A", enabled = true) { onShiftWeek(1) }
        }

        if (state.isAnotherDay) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(AccentBlue.copy(alpha = 0.12f))
                    .clickable(onClick = onTodayClick)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "Back to today",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = AccentBlue
                )
            }
        }
    }
}

@Composable
private fun ArrowButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* ------------------------------------------------------------------ */
/* Grid                                                                */
/* ------------------------------------------------------------------ */

@Composable
private fun TimelineGrid(day: TimelineDay, onTaskClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        day.rows.forEach { row ->
            when (row) {
                is TimelineRow.Anytime -> Unit // drawn above the grid, by AnytimeBand
                is TimelineRow.Hour -> HourRow(row = row, onTaskClick = onTaskClick)
                is TimelineRow.Gap -> GapRow(row = row)
            }
        }
    }
}

@Composable
private fun HourRow(row: TimelineRow.Hour, onTaskClick: (String) -> Unit) {
    val surfaces = LocalDaymarkSurfaces.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HOUR_HEIGHT_DP.dp)
    ) {
        Box(
            modifier = Modifier
                .width(GUTTER_WIDTH_DP.dp)
                .padding(end = 6.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Text(
                text = TimelineLabels.hourLabel(row.hour),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(surfaces.cardBorder)
        )

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(end = 12.dp)
        ) {
            if (row.blocks.isNotEmpty()) {
                HourBlocks(hour = row.hour, blocks = row.blocks, onTaskClick = onTaskClick)
            }
        }
    }
}

/**
 * One hour's worth of the blocks that cover it: `laneCount` equal columns, each holding
 * its block's slice of this hour, offset to the block's real start minute.
 *
 * A block's *content* renders only in the hour it starts in; later slices continue the
 * card so a two-hour task still reads as one block interrupted by an hour label.
 */
@Composable
private fun HourBlocks(hour: Int, blocks: List<TimelineBlock>, onTaskClick: (String) -> Unit) {
    val laneCount = blocks.maxOf { it.laneCount }.coerceAtLeast(1)

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(BLOCK_GAP_DP.dp)
    ) {
        (0 until laneCount).forEach { lane ->
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val block = blocks.firstOrNull { it.lane == lane }
                if (block != null) {
                    val sliceStart = maxOf(block.startMinute, hour * MINUTES_PER_HOUR)
                    val sliceEnd = minOf(block.endMinute, (hour + 1) * MINUTES_PER_HOUR)

                    BlockSlice(
                        block = block,
                        topMinutes = sliceStart - hour * MINUTES_PER_HOUR,
                        sliceMinutes = (sliceEnd - sliceStart).coerceAtLeast(1),
                        showContent = block.startMinute >= hour * MINUTES_PER_HOUR,
                        onTaskClick = onTaskClick
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockSlice(
    block: TimelineBlock,
    topMinutes: Int,
    sliceMinutes: Int,
    showContent: Boolean,
    onTaskClick: (String) -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    val shape = RoundedCornerShape(14.dp)
    val height = (sliceMinutes * HOUR_HEIGHT_DP / MINUTES_PER_HOUR).dp.coerceAtLeast(6.dp)
    val top = (topMinutes * HOUR_HEIGHT_DP / MINUTES_PER_HOUR).dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = top)
            .height(height)
            .clip(shape)
            .background(if (block.isCompleted) surfaces.card.copy(alpha = 0.55f) else surfaces.card)
            .border(1.dp, surfaces.cardBorder, shape)
            .clickable { onTaskClick(block.taskId) }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (showContent) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = block.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 15.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (block.isCompleted) {
                            TextDecoration.LineThrough
                        } else {
                            TextDecoration.None
                        }
                    )
                    if (sliceMinutes >= 40) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${TimelineLabels.timeRangeLabel(block.startMinute, block.endMinute)}  ·  " +
                                TimelineLabels.durationLabel(block.durationMinutes),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                PriorityMark(block)

                Spacer(Modifier.width(4.dp))
                MarkDot(isDone = block.isCompleted)
            }
        }
    }
}

/** A hairline dot for urgent/high work; nothing at all for the rest. */
@Composable
private fun PriorityMark(block: TimelineBlock) {
    val color = when (block.priority) {
        Priority.URGENT -> Color(0xFFEB5757)
        Priority.HIGH -> Color(0xFFF2994A)
        else -> Color.Transparent
    }
    if (color != Color.Transparent) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/** Open vs done, at a glance. Tapping the block opens the task, where completion lives. */
@Composable
private fun MarkDot(isDone: Boolean) {
    val surfaces = LocalDaymarkSurfaces.current
    Box(
        modifier = Modifier
            .padding(top = 3.dp)
            .size(14.dp)
            .clip(CircleShape)
            .background(if (isDone) AccentBlue else Color.Transparent)
            .border(1.5.dp, if (isDone) AccentBlue else surfaces.cardBorder, CircleShape)
    )
}

@Composable
private fun GapRow(row: TimelineRow.Gap) {
    val surfaces = LocalDaymarkSurfaces.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        Box(
            modifier = Modifier.width(GUTTER_WIDTH_DP.dp).padding(end = 6.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                text = "\u22EE",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(surfaces.cardBorder.copy(alpha = 0.6f))
        )

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(end = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "${TimelineLabels.timeRangeLabel(row.fromMinute, row.toMinute)}  ·  " +
                    TimelineLabels.durationLabel(row.durationMinutes),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Anytime band                                                        */
/* ------------------------------------------------------------------ */

@Composable
private fun AnytimeBand(tasks: List<Task>, onTaskClick: (String) -> Unit) {
    val surfaces = LocalDaymarkSurfaces.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
    ) {
        Text(
            text = "ANYTIME",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.4.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        tasks.forEach { task ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(surfaces.card)
                    .border(1.dp, surfaces.cardBorder, RoundedCornerShape(14.dp))
                    .clickable { onTaskClick(task.id) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MarkDot(isDone = task.isCompleted)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (task.dueAt != null && !task.isCompleted) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Overdue",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = Color(0xFFF2994A)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun EmptyDayHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)
    ) {
        Text(
            text = "Nothing scheduled.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Tap a day above, or add a task with a time.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
