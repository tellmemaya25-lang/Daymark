package com.sabihon.todo.ui.activity

import com.sabihon.todo.ui.home.WeekDay
import java.time.LocalDate

/**
 * State for the Activity tab's Calendar segment.
 *
 * [day] is already laid out — the screen does no date maths of its own, so the same
 * [TimelineLayout] that the tests pin is the one the user sees.
 */
data class CalendarUiState(
    /** Midnight-aligned "today", captured once per state update, never re-read in a composable. */
    val today: LocalDate = LocalDate.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    /** Monday → Sunday containing [selectedDate]. */
    val weekDays: List<WeekDay> = emptyList(),
    val day: TimelineDay = TimelineDay(),
    val isLoading: Boolean = true,
    val userInitials: String = "DM",
    val userPhotoUrl: String? = null,
    val message: String? = null
) {
    /** True when the week strip is not the current one, so the "Today" chip is worth showing. */
    val isAnotherDay: Boolean get() = selectedDate != today
}
