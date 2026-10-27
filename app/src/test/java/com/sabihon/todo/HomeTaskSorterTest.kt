package com.sabihon.todo

import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.ui.home.HomeFilter
import com.sabihon.todo.ui.home.HomeSort
import com.sabihon.todo.ui.home.HomeSortDirection
import com.sabihon.todo.ui.home.HomeTaskSorter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The Home list's filter and order.
 *
 * Everything is pinned: "today" is Tuesday 2026-10-27, the zone is UTC. Two of these
 * cases are regressions for real bugs - Upcoming measured from the week strip instead of
 * from today, and a sort menu with no direction.
 */
class HomeTaskSorterTest {

    private val today = LocalDate.of(2026, 10, 27) // Tuesday
    private val utc = ZoneId.of("UTC")

    private fun at(date: LocalDate, hour: Int = 9): Long =
        date.atTime(LocalTime.of(hour, 0)).atZone(utc).toInstant().toEpochMilli()

    private fun task(
        id: String,
        dueAt: Long? = null,
        priority: Priority = Priority.MEDIUM,
        isCompleted: Boolean = false,
        repeat: RepeatRule = RepeatRule.NONE,
        streak: Int = 0
    ) = Task(
        id = id,
        title = id,
        dueAt = dueAt,
        priority = priority,
        isCompleted = isCompleted,
        repeat = repeat,
        streak = streak
    )

    private fun split(
        tasks: List<Task>,
        filter: HomeFilter,
        sort: HomeSort = HomeSort.DUE_DATE,
        direction: HomeSortDirection = HomeSortDirection.ASCENDING,
        selectedDate: LocalDate = today,
        aiFocusRanks: Map<String, Int> = emptyMap(),
        nowMillis: Long? = at(today, hour = 9)
    ) = HomeTaskSorter.split(
        tasks = tasks,
        filter = filter,
        sort = sort,
        direction = direction,
        selectedDate = selectedDate,
        today = today,
        zone = utc,
        aiFocusRanks = aiFocusRanks,
        nowMillis = nowMillis
    )

    private fun ids(tasks: List<Task>): List<String> = tasks.map { it.id }

    // ---- Upcoming ----

    @Test
    fun `upcoming shows a task due tomorrow`() {
        val guitar = task("guitar", at(today.plusDays(1)))

        assertEquals(listOf("guitar"), ids(split(listOf(guitar), HomeFilter.UPCOMING).first))
    }

    @Test
    fun `upcoming ignores the day picked in the week strip`() {
        // The regression: with Thursday selected, a task due tomorrow used to disappear.
        val guitar = task("guitar", at(today.plusDays(1)))
        val thursday = today.plusDays(2)

        assertEquals(
            listOf("guitar"),
            ids(split(listOf(guitar), HomeFilter.UPCOMING, selectedDate = thursday).first)
        )
    }

    @Test
    fun `upcoming leaves today's work to the today tab`() {
        val now = task("now", at(today, hour = 20))

        assertTrue(split(listOf(now), HomeFilter.UPCOMING).first.isEmpty())
        assertEquals(listOf("now"), ids(split(listOf(now), HomeFilter.TODAY).first))
    }

    @Test
    fun `upcoming skips completed tasks but keeps them in the completed list`() {
        val done = task("done", at(today.plusDays(1)), isCompleted = true)

        val (visible, completed) = split(listOf(done), HomeFilter.UPCOMING)

        assertTrue(visible.isEmpty())
        assertEquals(listOf("done"), ids(completed))
    }

    // ---- Today ----

    @Test
    fun `today holds undated tasks only while today is the selected day`() {
        val undated = task("undated")

        assertEquals(listOf("undated"), ids(split(listOf(undated), HomeFilter.TODAY).first))
        assertTrue(
            split(listOf(undated), HomeFilter.TODAY, selectedDate = today.plusDays(1)).first.isEmpty()
        )
    }

    @Test
    fun `today surfaces open overdue tasks while today is selected, and hides them on other days`() {
        val overdue = task("overdue", at(today.minusDays(2)))
        val doneOverdue = task("done-overdue", at(today.minusDays(1)), isCompleted = true)

        val (visibleToday, completedToday) = split(listOf(overdue, doneOverdue), HomeFilter.TODAY)
        assertEquals(listOf("overdue"), ids(visibleToday))
        assertTrue(completedToday.isEmpty())

        // Picking Wednesday in the week strip inspects Wednesday only.
        assertTrue(
            split(listOf(overdue), HomeFilter.TODAY, selectedDate = today.plusDays(1)).first.isEmpty()
        )
    }

    // ---- ordering ----

    @Test
    fun `due date ascending is soonest first`() {
        val late = task("late", at(today.plusDays(3)))
        val soon = task("soon", at(today.plusDays(1)))

        assertEquals(
            listOf("soon", "late"),
            ids(split(listOf(late, soon), HomeFilter.ALL, HomeSort.DUE_DATE, HomeSortDirection.ASCENDING).first)
        )
    }

    @Test
    fun `due date descending is latest first, and undated still sinks`() {
        val late = task("late", at(today.plusDays(3)))
        val soon = task("soon", at(today.plusDays(1)))
        val undated = task("undated")

        assertEquals(
            listOf("late", "soon", "undated"),
            ids(split(listOf(undated, soon, late), HomeFilter.ALL, HomeSort.DUE_DATE, HomeSortDirection.DESCENDING).first)
        )
    }

    @Test
    fun `priority descending puts urgent first`() {
        val low = task("low", priority = Priority.LOW)
        val urgent = task("urgent", priority = Priority.URGENT)
        val medium = task("medium", priority = Priority.MEDIUM)

        assertEquals(
            listOf("urgent", "medium", "low"),
            ids(split(listOf(low, urgent, medium), HomeFilter.ALL, HomeSort.PRIORITY, HomeSortDirection.DESCENDING).first)
        )
    }

    @Test
    fun `priority ascending puts low first`() {
        val low = task("low", priority = Priority.LOW)
        val urgent = task("urgent", priority = Priority.URGENT)

        assertEquals(
            listOf("low", "urgent"),
            ids(split(listOf(urgent, low), HomeFilter.ALL, HomeSort.PRIORITY, HomeSortDirection.ASCENDING).first)
        )
    }

    @Test
    fun `equal priority falls back to the soonest due date`() {
        val later = task("later", at(today.plusDays(4)), priority = Priority.HIGH)
        val sooner = task("sooner", at(today.plusDays(2)), priority = Priority.HIGH)

        assertEquals(
            listOf("sooner", "later"),
            ids(split(listOf(later, sooner), HomeFilter.ALL, HomeSort.PRIORITY, HomeSortDirection.DESCENDING).first)
        )
    }

    @Test
    fun `completed tasks stay separated from visible tasks and follow selected sort and direction`() {
        val open = task("open", at(today.plusDays(1)))
        val doneLow = task("done-low", at(today.plusDays(3)), priority = Priority.LOW, isCompleted = true)
        val doneUrgent = task("done-urgent", at(today.plusDays(1)), priority = Priority.URGENT, isCompleted = true)

        val (visibleDueAsc, completedDueAsc) = split(
            listOf(open, doneLow, doneUrgent),
            HomeFilter.ALL,
            HomeSort.DUE_DATE,
            HomeSortDirection.ASCENDING
        )
        assertEquals(listOf("open"), ids(visibleDueAsc))
        assertEquals(listOf("done-urgent", "done-low"), ids(completedDueAsc))

        val (_, completedDueDesc) = split(
            listOf(open, doneLow, doneUrgent),
            HomeFilter.ALL,
            HomeSort.DUE_DATE,
            HomeSortDirection.DESCENDING
        )
        assertEquals(listOf("done-low", "done-urgent"), ids(completedDueDesc))

        val (_, completedPrioAsc) = split(
            listOf(doneUrgent, doneLow),
            HomeFilter.ALL,
            HomeSort.PRIORITY,
            HomeSortDirection.ASCENDING
        )
        assertEquals(listOf("done-low", "done-urgent"), ids(completedPrioAsc))

        val (_, completedPrioDesc) = split(
            listOf(doneLow, doneUrgent),
            HomeFilter.ALL,
            HomeSort.PRIORITY,
            HomeSortDirection.DESCENDING
        )
        assertEquals(listOf("done-urgent", "done-low"), ids(completedPrioDesc))

        val (_, completedSmartAsc) = split(
            listOf(doneLow, doneUrgent),
            HomeFilter.ALL,
            HomeSort.SMART,
            HomeSortDirection.ASCENDING
        )
        assertEquals(listOf("done-urgent", "done-low"), ids(completedSmartAsc))

        val (_, completedSmartDesc) = split(
            listOf(doneLow, doneUrgent),
            HomeFilter.ALL,
            HomeSort.SMART,
            HomeSortDirection.DESCENDING
        )
        assertEquals(listOf("done-low", "done-urgent"), ids(completedSmartDesc))
    }

    // ---- Smart focus ordering ----

    @Test
    fun `smart sort ascending places overdue and streak-at-risk tasks ahead of distant work`() {
        val distant = task("distant", at(today.plusDays(10)), priority = Priority.LOW)
        val habit = task(
            id = "habit",
            dueAt = at(today, hour = 18),
            priority = Priority.MEDIUM,
            repeat = RepeatRule.DAILY,
            streak = 6
        )
        val overdue = task("overdue", at(today.minusDays(1)), priority = Priority.HIGH)

        assertEquals(
            listOf("overdue", "habit", "distant"),
            ids(split(listOf(distant, habit, overdue), HomeFilter.ALL, HomeSort.SMART, HomeSortDirection.ASCENDING).first)
        )
    }

    @Test
    fun `smart sort honours validated AI focus ranks and reverses cleanly on descending`() {
        val first = task("a", at(today.plusDays(2)))
        val second = task("b", at(today.plusDays(1)))
        val ranks = mapOf("a" to 1, "b" to 2)

        assertEquals(
            listOf("a", "b"),
            ids(split(listOf(second, first), HomeFilter.ALL, HomeSort.SMART, HomeSortDirection.ASCENDING, aiFocusRanks = ranks).first)
        )
        assertEquals(
            listOf("b", "a"),
            ids(split(listOf(second, first), HomeFilter.ALL, HomeSort.SMART, HomeSortDirection.DESCENDING, aiFocusRanks = ranks).first)
        )
    }
}
