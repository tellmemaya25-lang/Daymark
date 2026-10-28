package com.sabihon.todo

import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.SubTask
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.ui.activity.TimelineBlock
import com.sabihon.todo.ui.activity.TimelineDay
import com.sabihon.todo.ui.activity.TimelineLayout
import com.sabihon.todo.ui.activity.TimelineRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Activity timeline's day layout.
 *
 * Everything is pinned - "today" is Tuesday 2026-10-27, the zone is UTC - so these do
 * not depend on when or where they run. The cases that exist for a reason:
 *
 *  - untimed tasks must land in Anytime (a `dueAt`-only timeline silently loses them)
 *  - a late task must not bleed past midnight
 *  - overlaps must lane-pack, and a fourth column must overflow instead of stacking
 *  - DST must not shift a wall-clock label
 */
class TimelineLayoutTest {

    private val today = LocalDate.of(2026, 10, 27) // Tuesday
    private val utc = ZoneId.of("UTC")

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
            .atZone(utc)
            .toInstant()
            .toEpochMilli()

    private fun task(
        id: String,
        title: String = id,
        dueAt: Long? = null,
        durationMinutes: Int? = null,
        priority: Priority = Priority.MEDIUM,
        isCompleted: Boolean = false,
        subTasks: List<SubTask> = emptyList(),
        seriesId: String? = null
    ) = Task(
        id = id,
        title = title,
        dueAt = dueAt,
        durationMinutes = durationMinutes,
        priority = priority,
        isCompleted = isCompleted,
        subTasks = subTasks,
        seriesId = seriesId
    )

    private fun build(
        tasks: List<Task>,
        day: LocalDate = today,
        zone: ZoneId = utc,
        fallback: Int = TimelineLayout.DEFAULT_DURATION_MINUTES,
        maxLanes: Int = TimelineLayout.MAX_LANES,
        gapThresholdHours: Int = TimelineLayout.GAP_THRESHOLD_HOURS
    ) = TimelineLayout.build(
        tasks = tasks,
        day = day,
        zone = zone,
        today = today,
        fallbackDurationMinutes = fallback,
        maxLanes = maxLanes,
        gapThresholdHours = gapThresholdHours
    )

    private fun hourly(day: TimelineDay) = day.rows.filterIsInstance<TimelineRow.Hour>()

    private fun blockFor(day: TimelineDay, taskId: String): TimelineBlock =
        day.timedBlocks.first { it.taskId == taskId }

    /* --------------------------------- 1 --------------------------------- */

    @Test
    fun `untimed tasks go to Anytime and never onto the grid`() {
        val day = build(
            listOf(
                task("a", "Rise and Shine"),
                task("b", "Wind Down")
            )
        )

        assertEquals(listOf("a", "b"), day.anytime.map { it.id }.sorted())
        assertTrue("nothing timed, so nothing is placed", day.timedBlocks.isEmpty())
        assertTrue(
            "no hour row may carry an untimed task",
            hourly(day).all { row -> row.blocks.isEmpty() }
        )
    }

    /* --------------------------------- 2 --------------------------------- */

    @Test
    fun `a late task is clamped to the end of its own day`() {
        val day = build(
            listOf(task("late", dueAt = at(today, 23, 30), durationMinutes = 90))
        )

        val block = blockFor(day, "late")
        assertEquals(23 * 60 + 30, block.startMinute)
        assertEquals("must not bleed into tomorrow", 24 * 60, block.endMinute)
        assertEquals(30, block.durationMinutes)
    }

    /* --------------------------------- 3 --------------------------------- */

    @Test
    fun `three overlapping tasks pack into three columns`() {
        val day = build(
            listOf(
                task("a", dueAt = at(today, 9, 0), durationMinutes = 60),
                task("b", dueAt = at(today, 9, 15), durationMinutes = 30),
                task("c", dueAt = at(today, 9, 30), durationMinutes = 45)
            )
        )

        assertEquals(0, blockFor(day, "a").lane)
        assertEquals(1, blockFor(day, "b").lane)
        assertEquals(2, blockFor(day, "c").lane)
        day.timedBlocks.forEach { assertEquals(3, it.laneCount) }
        assertTrue(day.overflow.isEmpty())
    }

    /* --------------------------------- 4 --------------------------------- */

    @Test
    fun `a fourth concurrent task overflows instead of stacking`() {
        val day = build(
            listOf(
                task("a", dueAt = at(today, 9, 0), durationMinutes = 60),
                task("b", dueAt = at(today, 9, 5), durationMinutes = 60),
                task("c", dueAt = at(today, 9, 10), durationMinutes = 60),
                task("d", dueAt = at(today, 9, 15), durationMinutes = 60)
            )
        )

        assertEquals("exactly one block overflows", 1, day.overflow.size)
        assertEquals("d", day.overflow.single().taskId)
        assertEquals(-1, day.overflow.single().lane)
        assertEquals("columns stay capped", 3, day.timedBlocks.size)
        day.timedBlocks.forEach { assertEquals(3, it.laneCount) }
    }

    /* --------------------------------- 5 --------------------------------- */

    @Test
    fun `long empty runs fold into gap rows but short ones do not`() {
        val day = build(
            listOf(
                task("early", dueAt = at(today, 8, 0), durationMinutes = 60),   // ends 09:00
                task("late", dueAt = at(today, 22, 0), durationMinutes = 60)
            )
        )

        // Before dawn folds too - the agenda starts with one collapsed row, not eight.
        assertEquals(
            listOf(0 to 8 * 60, 9 * 60 to 22 * 60),
            day.gaps.map { it.fromMinute to it.toMinute }
        )
        assertEquals(
            "no hour row is emitted inside a folded run",
            0,
            hourly(day).count { it.hour in 10..21 }
        )
        assertEquals(
            "the two booked hours, plus 23:00 which is too short a run to fold",
            listOf(8, 22, 23),
            hourly(day).map { it.hour }
        )
    }

    /* --------------------------------- 6 --------------------------------- */

    @Test
    fun `a higher threshold keeps every empty hour`() {
        val day = build(
            tasks = listOf(
                task("early", dueAt = at(today, 8, 0), durationMinutes = 60),
                task("late", dueAt = at(today, 22, 0), durationMinutes = 60)
            ),
            gapThresholdHours = 20
        )

        assertTrue("13 empty hours should not fold at a threshold of 20", day.gaps.isEmpty())
        assertEquals(24, hourly(day).size)
        assertEquals(
            "only the two booked hours carry blocks; the other 22 are drawn empty",
            22,
            hourly(day).count { it.blocks.isEmpty() }
        )
    }

    /* --------------------------------- 7 --------------------------------- */

    @Test
    fun `a task without a duration gets the fallback block`() {
        val day = build(listOf(task("a", dueAt = at(today, 9, 0))))

        val block = blockFor(day, "a")
        assertEquals(30, TimelineLayout.DEFAULT_DURATION_MINUTES)
        assertEquals(30, block.durationMinutes)
        assertEquals(9 * 60 + 30, block.endMinute)
    }

    /* --------------------------------- 8 --------------------------------- */

    @Test
    fun `out-of-range durations are clamped`() {
        val day = build(
            listOf(
                task("zero", dueAt = at(today, 9, 0), durationMinutes = 0),
                task("negative", dueAt = at(today, 11, 0), durationMinutes = -5),
                task("huge", dueAt = at(today, 13, 0), durationMinutes = 9999)
            )
        )

        assertEquals(5, blockFor(day, "zero").durationMinutes)
        assertEquals(5, blockFor(day, "negative").durationMinutes)
        assertEquals(
            "9999 clamps to 720, then gives up 60 to the midnight cut and stays 660 long",
            660,
            blockFor(day, "huge").durationMinutes
        )
        assertEquals(24 * 60, blockFor(day, "huge").endMinute)
    }

    /* --------------------------------- 9 --------------------------------- */

    /**
     * Europe/Berlin springs forward on 2026-03-29 at 02:00, so that day is 23 real hours
     * long. The grid is nonetheless drawn as 24 wall-clock hours, because that is what an
     * agenda shows: 09:00 must still be at minute 540 and still occupy the "09" row. A
     * naive "epoch millis / 60_000 % 1440" calculation puts it at 480 on this date.
     */
    @Test
    fun `a daylight-saving transition does not shift a wall-clock label`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val dstDay = LocalDate.of(2026, 3, 29)
        val nineAm = LocalDateTime.of(dstDay, java.time.LocalTime.of(9, 0))
            .atZone(berlin)
            .toInstant()
            .toEpochMilli()

        val day = TimelineLayout.build(
            tasks = listOf(task("a", dueAt = nineAm, durationMinutes = 60)),
            day = dstDay,
            zone = berlin,
            today = dstDay,
            gapThresholdHours = TimelineLayout.HOURS_PER_DAY // keep every hour row
        )

        val block = blockFor(day, "a")
        assertEquals(9 * 60, block.startMinute)
        assertEquals(10 * 60, block.endMinute)
        assertEquals(24, hourly(day).size)
        assertEquals((0 until 24).toList(), hourly(day).map { it.hour })
        assertTrue(day.gaps.isEmpty())
    }

    /* -------------------------------- 10 --------------------------------- */

    @Test
    fun `tasks from another day or zone are excluded`() {
        val otherDay = today.plusDays(1)
        val day = build(
            listOf(
                task("todayTask", dueAt = at(today, 9, 0)),
                task("tomorrowTask", dueAt = at(otherDay, 9, 0)),
                task("tokyoTask", dueAt = at(otherDay, 23, 0))
            )
        )

        assertEquals(listOf("todayTask"), day.timedBlocks.map { it.taskId })
    }

    /* -------------------------------- 11 --------------------------------- */

    @Test
    fun `a completed task still appears, flagged as done`() {
        val day = build(
            listOf(task("done", dueAt = at(today, 9, 0), isCompleted = true))
        )

        val block = blockFor(day, "done")
        assertTrue(block.isCompleted)
        assertEquals(9 * 60, block.startMinute)
    }

    /* -------------------------------- 12 --------------------------------- */

    @Test
    fun `a habit occurrence is placed like any other timed task`() {
        val day = build(
            listOf(
                task(
                    "habit",
                    title = "Morning Study",
                    dueAt = at(today, 9, 0),
                    durationMinutes = 45,
                    seriesId = "series-1",
                    subTasks = listOf(
                        SubTask(id = "s1", title = "Read", isDone = true),
                        SubTask(id = "s2", title = "Notes", isDone = false)
                    )
                )
            )
        )

        val block = blockFor(day, "habit")
        assertEquals(45, block.durationMinutes)
        assertEquals(1, block.subTasksDone)
        assertEquals(2, block.subTasksTotal)
    }

    /* -------------------------------- 13 --------------------------------- */

    @Test
    fun `an empty day still draws the grid`() {
        val day = build(emptyList())

        assertTrue(day.isEmpty)
        assertEquals(24, hourly(day).size)
        assertTrue("nothing to fold around, so no gap row", day.gaps.isEmpty())
        assertNull(day.firstTimedMinute)
        assertFalse("the grid is still there to draw", hourly(day).any { it.blocks.isNotEmpty() })
    }

    /* -------------------------------- 14 --------------------------------- */

    /**
     * Regression guard for the reason Anytime exists: an overdue task must not vanish
     * at midnight, and must not appear on every other day either.
     */
    @Test
    fun `overdue work joins Anytime for today only`() {
        val overdue = task("overdue", dueAt = at(today.minusDays(2), 9, 0))
        val undated = task("undated")

        val forToday = build(listOf(overdue, undated))
        assertEquals(listOf("overdue", "undated"), forToday.anytime.map { it.id }.sorted())

        val forYesterday = build(listOf(overdue, undated), day = today.minusDays(1))
        assertTrue(
            "undated work belongs to today, not to whichever day is open",
            forYesterday.anytime.isEmpty()
        )
        assertTrue(forYesterday.timedBlocks.isEmpty())
    }
}
