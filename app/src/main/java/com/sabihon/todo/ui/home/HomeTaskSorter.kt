package com.sabihon.todo.ui.home

import com.sabihon.todo.data.ai.LocalPriorityScorer
import com.sabihon.todo.domain.model.Task
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Home list's filtering and ordering, kept out of the ViewModel so it can be tested.
 *
 * Everything is passed in - including today and the zone - so the tests can pin a date
 * instead of depending on when they run.
 */
object HomeTaskSorter {

    private val localScorer = LocalPriorityScorer()

    fun split(
        tasks: List<Task>,
        filter: HomeFilter,
        sort: HomeSort,
        direction: HomeSortDirection,
        selectedDate: LocalDate,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        aiFocusRanks: Map<String, Int> = emptyMap(),
        nowMillis: Long? = null
    ): Pair<List<Task>, List<Task>> {
        val start = startOfDay(selectedDate, zone)
        val end = endOfDay(selectedDate, zone)
        val startOfToday = startOfDay(today, zone)
        val endOfToday = endOfDay(today, zone)
        val isTodaySelected = selectedDate == today

        val effectiveNow = nowMillis ?: if (today == LocalDate.now(zone)) {
            System.currentTimeMillis()
        } else {
            // Pin clock to 09:00 on `today` when unit tests supply a synthetic date.
            startOfToday + 9 * 3_600_000L
        }

        val matching = when (filter) {
            HomeFilter.TODAY -> tasks.filter { task ->
                (task.dueAt == null && isTodaySelected) ||
                    (task.dueAt != null && task.dueAt in start..end) ||
                    // Open overdue tasks stay visible at the top of Today instead of
                    // disappearing into All tasks after midnight.
                    (isTodaySelected && !task.isCompleted && task.dueAt != null && task.dueAt < startOfToday)
            }
            HomeFilter.UPCOMING -> tasks.filter { it.dueAt != null && it.dueAt > endOfToday }
            HomeFilter.ALL -> tasks
        }

        val pending = orderTasks(
            list = matching.filter { !it.isCompleted },
            sort = sort,
            direction = direction,
            effectiveNow = effectiveNow,
            today = today,
            zone = zone,
            aiFocusRanks = aiFocusRanks
        )

        val completed = orderTasks(
            list = matching.filter { it.isCompleted },
            sort = sort,
            direction = direction,
            effectiveNow = effectiveNow,
            today = today,
            zone = zone,
            aiFocusRanks = aiFocusRanks
        )

        return pending to completed
    }

    private fun orderTasks(
        list: List<Task>,
        sort: HomeSort,
        direction: HomeSortDirection,
        effectiveNow: Long,
        today: LocalDate,
        zone: ZoneId,
        aiFocusRanks: Map<String, Int>
    ): List<Task> = when (sort) {
        HomeSort.SMART -> {
            val ranked = localScorer.rankTasks(
                openTasks = list,
                nowMillis = effectiveNow,
                today = today,
                zone = zone,
                aiFocusRanks = aiFocusRanks
            )
            if (direction == HomeSortDirection.ASCENDING) ranked else ranked.reversed()
        }
        HomeSort.DUE_DATE -> list.sortedWith(dueComparator(direction))
        HomeSort.PRIORITY -> list.sortedWith(
            compareBy<Task> {
                if (direction == HomeSortDirection.ASCENDING) it.priority.ordinal else -it.priority.ordinal
            }.then(dueComparator(HomeSortDirection.ASCENDING))
        )
    }

    /**
     * Due-date ordering with one rule that must not flip: a task with no date sinks to
     * the bottom in both directions.
     */
    private fun dueComparator(direction: HomeSortDirection): Comparator<Task> =
        Comparator { a, b ->
            val left = a.dueAt
            val right = b.dueAt
            when {
                left == null && right == null -> 0
                left == null -> 1
                right == null -> -1
                direction == HomeSortDirection.ASCENDING -> left.compareTo(right)
                else -> right.compareTo(left)
            }
        }

    private fun startOfDay(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun endOfDay(date: LocalDate, zone: ZoneId): Long =
        date.atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()
}
