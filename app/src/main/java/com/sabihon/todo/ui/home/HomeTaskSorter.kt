package com.sabihon.todo.ui.home

import com.sabihon.todo.domain.model.Task
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Home list's filtering and ordering, kept out of the ViewModel so it can be tested.
 *
 * Two rules here used to be wrong or missing:
 *
 *  - **Upcoming** was measured from the day picked in the week strip. Tap a future day,
 *    open Upcoming, and everything due before that day vanished - which reads as "my
 *    upcoming task is missing". It is now measured from today, always.
 *  - **There was no direction.** "Default order" and "Due date" ran the same
 *    `sortedBy { dueAt }`, so one of the two menu rows did nothing at all.
 *
 * Everything is passed in - including today and the zone - so the tests can pin a date
 * instead of depending on when they run.
 */
object HomeTaskSorter {

    fun split(
        tasks: List<Task>,
        filter: HomeFilter,
        sort: HomeSort,
        direction: HomeSortDirection,
        selectedDate: LocalDate,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Pair<List<Task>, List<Task>> {
        val start = startOfDay(selectedDate, zone)
        val end = endOfDay(selectedDate, zone)
        // Upcoming is about today, not about whichever day the strip is showing.
        val endOfToday = endOfDay(today, zone)
        // Undated tasks belong to "today", not to whatever day is selected.
        val isTodaySelected = selectedDate == today

        val matching = when (filter) {
            HomeFilter.TODAY -> tasks.filter { task ->
                (task.dueAt == null && isTodaySelected) ||
                    (task.dueAt != null && task.dueAt in start..end)
            }
            HomeFilter.UPCOMING -> tasks.filter { it.dueAt != null && it.dueAt > endOfToday }
            HomeFilter.ALL -> tasks
        }

        val sorted = when (sort) {
            HomeSort.DUE_DATE -> matching.sortedWith(dueComparator(direction))
            HomeSort.PRIORITY -> matching.sortedWith(
                // Ascending means LOW first, so "Ascending" and "Descending" keep the
                // same meaning whether you are sorting by date or by priority.
                compareBy<Task> {
                    if (direction == HomeSortDirection.ASCENDING) it.priority.ordinal else -it.priority.ordinal
                }.then(dueComparator(HomeSortDirection.ASCENDING))
            )
        }

        val visible = sorted.filter { !it.isCompleted }
        val completed = sorted
            .filter { it.isCompleted }
            .sortedByDescending { it.completedAt ?: it.updatedAt }

        return visible to completed
    }

    /**
     * Due-date ordering with one rule that must not flip: a task with no date sinks to
     * the bottom in both directions. Reversing the whole list would otherwise float
     * every undated task to the top the moment you asked for Descending.
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
