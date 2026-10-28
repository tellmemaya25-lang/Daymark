package com.sabihon.todo.ui.activity

import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.security.InputSecurityValidator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Activity tab's day timeline, computed as pure data.
 *
 * No Compose, no Android, no clock of its own: the date, zone and "today" are all passed
 * in, so a test can pin a day instead of depending on when it runs. The composable turns
 * these rows into dp; this file decides what belongs on the day and where.
 *
 * Three things it guarantees, because each was a way for a timeline to lie:
 *
 *  - **nothing disappears.** Tasks without a time are not dropped; they go to the
 *    [TimelineRow.Anytime] band at the top. A timeline that silently omits them looks
 *    correct and loses work.
 *  - **nothing runs off the grid.** A block is clamped to the end of its own day, so a
 *    23:30 task with 90 minutes ends at 24:00 rather than bleeding into tomorrow.
 *  - **wall-clock minutes only.** [TimelineBlock.startMinute] is `hour * 60 + minute`
 *    from the zone's wall clock, which is what a day agenda should show. A DST transition
 *    therefore moves a task's label only if the *local* time moved, which is exactly the
 *    behaviour a user expects and is not worth modelling hour-by-hour.
 */
object TimelineLayout {

    /** Block length used when a task has no [Task.durationMinutes]. */
    const val DEFAULT_DURATION_MINUTES = 30

    /** Columns before a cluster overflows into "+N more". */
    const val MAX_LANES = 3

    /** Runs of empty hours this long fold into one [TimelineRow.Gap]. */
    const val GAP_THRESHOLD_HOURS = 3

    /** Every day is drawn as 24 wall-clock hours. */
    const val HOURS_PER_DAY = 24

    const val MINUTES_PER_HOUR = 60

    /**
     * Builds one day.
     *
     * @param tasks everything the app knows about; deleted tasks and other days are
     *   filtered out here rather than by the caller.
     * @param day the wall-clock day being shown.
     * @param zone the zone [day] is expressed in, and the one task times are read in.
     * @param today the real current date in [zone]; untimed tasks only belong to the
     *   day that is actually today, and "overdue" only exists relative to it.
     */
    fun build(
        tasks: List<Task>,
        day: LocalDate,
        zone: ZoneId,
        today: LocalDate = LocalDate.now(zone),
        fallbackDurationMinutes: Int = DEFAULT_DURATION_MINUTES,
        maxLanes: Int = MAX_LANES,
        gapThresholdHours: Int = GAP_THRESHOLD_HOURS
    ): TimelineDay {
        val laneBudget = maxLanes.coerceAtLeast(1)
        val fallback = fallbackDurationMinutes.coerceIn(
            InputSecurityValidator.MIN_DURATION_MINUTES,
            InputSecurityValidator.MAX_DURATION_MINUTES
        )
        val visible = tasks.filterNot { it.isDeleted }

        val timed = visible
            .mapNotNull { task -> startMinuteOf(task, day, zone)?.let { task to it } }
            .map { (task, start) -> blockOf(task, start, fallback) }
            .sortedWith(compareBy({ it.startMinute }, { it.endMinute }, { it.title }))

        val (placed, overflow) = assignLanes(timed, laneBudget)
        val anytime = anytimeFor(visible, day, today, zone)

        return TimelineDay(
            rows = buildRows(placed, anytime, gapThresholdHours),
            overflow = overflow,
            firstTimedMinute = placed.minOfOrNull { it.startMinute }
        )
    }

    /**
     * Minutes from midnight for [task] on [day], or null when it belongs to another day.
     *
     * Null `dueAt` is handled by [anytimeFor], not here: an untimed task has no place on
     * the grid, and inventing one (midnight, or "now") would put it somewhere arbitrary.
     */
    private fun startMinuteOf(task: Task, day: LocalDate, zone: ZoneId): Int? {
        val dueAt = task.dueAt ?: return null
        val local = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDateTime()
        if (local.toLocalDate() != day) return null
        return local.hour * MINUTES_PER_HOUR + local.minute
    }

    private fun blockOf(task: Task, startMinute: Int, fallbackMinutes: Int): TimelineBlock {
        val rawDuration = task.durationMinutes ?: fallbackMinutes
        val duration = rawDuration.coerceIn(
            InputSecurityValidator.MIN_DURATION_MINUTES,
            InputSecurityValidator.MAX_DURATION_MINUTES
        )
        // Clamp to the end of this day rather than letting a late block bleed into tomorrow.
        val endMinute = (startMinute + duration).coerceAtMost(HOURS_PER_DAY * MINUTES_PER_HOUR)
        return TimelineBlock(
            taskId = task.id,
            title = task.title,
            startMinute = startMinute,
            endMinute = endMinute,
            lane = 0,
            laneCount = 1,
            isCompleted = task.isCompleted,
            priority = task.priority,
            categoryId = task.categoryId,
            subTasksDone = task.subTasks.count { it.isDone },
            subTasksTotal = task.subTasks.size
        )
    }

    /**
     * Greedy lane packing with a cap.
     *
     * Each block takes the leftmost lane whose previous block has already ended; when
     * every lane is busy the block overflows instead of being drawn on top of another.
     * Lane *count* is per overlap cluster, not per lane index globally, so a lone
     * afternoon task still gets the full width even if the morning had three columns.
     */
    private fun assignLanes(
        blocks: List<TimelineBlock>,
        maxLanes: Int
    ): Pair<List<TimelineBlock>, List<TimelineBlock>> {
        if (blocks.isEmpty()) return emptyList<TimelineBlock>() to emptyList()

        val laneEnd = IntArray(maxLanes) { Int.MIN_VALUE }
        val assigned = ArrayList<TimelineBlock>(blocks.size)
        val overflow = ArrayList<TimelineBlock>()

        for (block in blocks) {
            var lane = -1
            for (index in 0 until maxLanes) {
                if (laneEnd[index] <= block.startMinute) {
                    lane = index
                    break
                }
            }
            if (lane < 0) {
                overflow += block.copy(lane = -1, laneCount = 0)
            } else {
                laneEnd[lane] = block.endMinute
                assigned += block.copy(lane = lane)
            }
        }

        // One laneCount per transitively-overlapping cluster.
        val counted = ArrayList<TimelineBlock>(assigned.size)
        var index = 0
        while (index < assigned.size) {
            var clusterEnd = assigned[index].endMinute
            val clusterStart = index
            var widest = assigned[index].lane
            index++
            while (index < assigned.size && assigned[index].startMinute < clusterEnd) {
                clusterEnd = maxOf(clusterEnd, assigned[index].endMinute)
                widest = maxOf(widest, assigned[index].lane)
                index++
            }
            val columns = widest + 1
            for (position in clusterStart until index) {
                counted += assigned[position].copy(laneCount = columns)
            }
        }
        return counted to overflow
    }

    /**
     * The band above the grid.
     *
     * Untimed tasks belong to *today* only: showing them on every day would make an
     * undated backlog look scheduled. Overdue-and-still-open tasks are pulled in for the
     * same reason they stay on Home's Today list - otherwise they vanish at midnight.
     */
    private fun anytimeFor(
        tasks: List<Task>,
        day: LocalDate,
        today: LocalDate,
        zone: ZoneId
    ): List<Task> {
        val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        return tasks
            .filter { task ->
                val due = task.dueAt
                when {
                    due == null -> day == today
                    day == today && !task.isCompleted && due < startOfToday -> true
                    else -> false
                }
            }
            .sortedWith(
                compareBy(
                    { it.isCompleted },
                    { it.dueAt ?: Long.MAX_VALUE },
                    { -it.priority.ordinal },
                    { it.title.lowercase() }
                )
            )
    }

    /**
     * Hour rows, with long empty runs folded into one gap row.
     *
     * Folding only happens when something timed exists: a day with no timetable is
     * better drawn as an empty grid (with the empty-state copy over it) than as a single
     * "00:00 → 24:00" row, which reads like the timeline failed to load.
     */
    private fun buildRows(
        blocks: List<TimelineBlock>,
        anytime: List<Task>,
        gapThresholdHours: Int
    ): List<TimelineRow> {
        val rows = ArrayList<TimelineRow>(HOURS_PER_DAY + 1)
        if (anytime.isNotEmpty()) rows += TimelineRow.Anytime(anytime)

        val occupied = BooleanArray(HOURS_PER_DAY)
        for (block in blocks) {
            val firstHour = block.startMinute / MINUTES_PER_HOUR
            val lastHour = (block.endMinute - 1).coerceAtLeast(block.startMinute) / MINUTES_PER_HOUR
            for (hour in firstHour..minOf(lastHour, HOURS_PER_DAY - 1)) {
                occupied[hour] = true
            }
        }
        val canFold = blocks.isNotEmpty() && gapThresholdHours > 0

        var hour = 0
        while (hour < HOURS_PER_DAY) {
            if (occupied[hour]) {
                rows += TimelineRow.Hour(hour, blocksCovering(blocks, hour))
                hour++
                continue
            }
            var runEnd = hour
            while (runEnd + 1 < HOURS_PER_DAY && !occupied[runEnd + 1]) runEnd++
            val runLength = runEnd - hour + 1
            if (canFold && runLength >= gapThresholdHours) {
                rows += TimelineRow.Gap(
                    fromMinute = hour * MINUTES_PER_HOUR,
                    toMinute = (runEnd + 1) * MINUTES_PER_HOUR
                )
            } else {
                for (empty in hour..runEnd) rows += TimelineRow.Hour(empty, emptyList())
            }
            hour = runEnd + 1
        }
        return rows
    }

    private fun blocksCovering(blocks: List<TimelineBlock>, hour: Int): List<TimelineBlock> {
        val hourStart = hour * MINUTES_PER_HOUR
        val hourEnd = hourStart + MINUTES_PER_HOUR
        return blocks.filter { it.startMinute < hourEnd && it.endMinute > hourStart }
    }
}

/** One timed block. Minutes are wall-clock, from midnight; [endMinute] is exclusive. */
data class TimelineBlock(
    val taskId: String,
    val title: String,
    val startMinute: Int,
    val endMinute: Int,
    /** 0-based column, or -1 when this block overflowed the lane budget. */
    val lane: Int,
    /** Columns in this block's overlap cluster; 1 when nothing overlaps it. */
    val laneCount: Int,
    val isCompleted: Boolean,
    val priority: Priority,
    val categoryId: String?,
    val subTasksDone: Int,
    val subTasksTotal: Int
) {
    val durationMinutes: Int get() = endMinute - startMinute
}

/** A row of the day, in the order it is drawn. */
sealed interface TimelineRow {
    /** Untimed work for the day, pinned above the grid. */
    data class Anytime(val tasks: List<Task>) : TimelineRow

    /** One wall-clock hour, with every block that overlaps it. */
    data class Hour(val hour: Int, val blocks: List<TimelineBlock>) : TimelineRow

    /** A folded run of empty hours. Half-open: [fromMinute] inclusive, [toMinute] exclusive. */
    data class Gap(val fromMinute: Int, val toMinute: Int) : TimelineRow {
        val durationMinutes: Int get() = toMinute - fromMinute
    }
}

/** One day's worth of drawing instructions. */
data class TimelineDay(
    val rows: List<TimelineRow> = emptyList(),
    /** Blocks that did not fit the lane budget; the UI shows "+N more". */
    val overflow: List<TimelineBlock> = emptyList(),
    /** Where to scroll on open, or null when nothing is timed. */
    val firstTimedMinute: Int? = null
) {
    val anytime: List<Task>
        get() = rows.filterIsInstance<TimelineRow.Anytime>().firstOrNull()?.tasks.orEmpty()

    /** Distinct timed blocks (a block spanning hours appears in more than one row). */
    val timedBlocks: List<TimelineBlock>
        get() = rows.filterIsInstance<TimelineRow.Hour>()
            .flatMap { it.blocks }
            .distinctBy { it.taskId }
            .sortedBy { it.startMinute }

    val gaps: List<TimelineRow.Gap>
        get() = rows.filterIsInstance<TimelineRow.Gap>()

    /** True when nothing at all belongs to the day, timed or not. */
    val isEmpty: Boolean get() = timedBlocks.isEmpty() && anytime.isEmpty()
}
