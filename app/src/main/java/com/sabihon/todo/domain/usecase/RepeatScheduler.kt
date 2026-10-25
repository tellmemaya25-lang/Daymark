package com.sabihon.todo.domain.usecase

import com.sabihon.todo.domain.model.RepeatRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Date maths for repeating tasks. No Android, no Firebase, no clock reads – every
 * function takes the "today" it should reason about, so it is testable on the JVM.
 *
 * The one rule that everything else follows from: **streaks count missed
 * occurrences, not calendar days.** A Monday/Thursday habit completed on Monday
 * and again on Thursday has a three-day calendar gap but has missed nothing, and
 * must not be punished for it.
 */
object RepeatScheduler {

    /** Guard against unbounded scans when a weekly rule has no days selected. */
    const val MAX_LOOKAHEAD_DAYS = 366L

    /** Default hour for a repeating task that has no due time yet. */
    val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

    /** True when [rule] would produce an occurrence on [date]. */
    fun isScheduled(
        rule: RepeatRule,
        repeatDays: List<Int>,
        date: LocalDate
    ): Boolean = when (rule) {
        RepeatRule.NONE -> false
        RepeatRule.DAILY -> true
        RepeatRule.WEEKLY -> date.dayOfWeek.value in repeatDays
    }

    /**
     * First scheduled date strictly after [after].
     *
     * "Strictly after" is what keeps a completed occurrence from scheduling itself
     * again. Returns null when the rule is [RepeatRule.NONE] or a weekly rule has no
     * valid days – callers treat that as "this task does not repeat".
     */
    fun nextOccurrence(
        rule: RepeatRule,
        repeatDays: List<Int>,
        after: LocalDate
    ): LocalDate? = when (rule) {
        RepeatRule.NONE -> null
        RepeatRule.DAILY -> after.plusDays(1)
        RepeatRule.WEEKLY -> {
            val days = repeatDays.filter { it in 1..7 }.toSet()
            if (days.isEmpty()) {
                null
            } else {
                // Seven tries is always enough: any valid weekday is within a week.
                (1..7).asSequence()
                    .map { after.plusDays(it.toLong()) }
                    .firstOrNull { it.dayOfWeek.value in days }
            }
        }
    }

    /**
     * How many scheduled occurrences fall in `(after, until)` – both ends excluded.
     *
     * `until` is excluded because today is not over yet: finishing a daily habit
     * this evening must not count as having already missed today.
     */
    fun missedOccurrences(
        rule: RepeatRule,
        repeatDays: List<Int>,
        after: LocalDate,
        until: LocalDate
    ): Int {
        if (rule == RepeatRule.NONE || !until.isAfter(after)) return 0
        val span = ChronoUnit.DAYS.between(after, until)
        if (span > MAX_LOOKAHEAD_DAYS) return Int.MAX_VALUE

        return when (rule) {
            RepeatRule.NONE -> 0
            // Every day is scheduled, so the gap minus the two open ends is the count.
            RepeatRule.DAILY -> (span - 1).coerceAtLeast(0L).toInt()
            RepeatRule.WEEKLY -> {
                val days = repeatDays.filter { it in 1..7 }.toSet()
                if (days.isEmpty()) {
                    0
                } else {
                    (1 until span).count { offset ->
                        after.plusDays(offset).dayOfWeek.value in days
                    }
                }
            }
        }
    }

    /**
     * Streak value after completing an occurrence on [today].
     *
     * - never completed            -> 1
     * - completed again the same day, or before the next one was due (an early
     *   tick, not a new occurrence) -> unchanged
     * - nothing missed since last  -> +1
     * - exactly one missed         -> +1 (the one forgiven occurrence)
     * - two or more missed         -> reset to 1
     */
    fun streakAfter(
        rule: RepeatRule,
        repeatDays: List<Int>,
        previousStreak: Int,
        lastCompletedDay: LocalDate?,
        today: LocalDate
    ): Int {
        if (!rule.isRecurring) return 1
        if (lastCompletedDay == null) return 1
        if (!today.isAfter(lastCompletedDay)) return previousStreak.coerceAtLeast(1)

        val expected = nextOccurrence(rule, repeatDays, lastCompletedDay)
            ?: return 1
        // Completing the next occurrence early does not extend the streak; the
        // occurrence it belongs to has not come around yet.
        if (today.isBefore(expected)) return previousStreak.coerceAtLeast(1)

        val missed = missedOccurrences(rule, repeatDays, lastCompletedDay, today)
        return when {
            missed <= 1 -> previousStreak.coerceAtLeast(0) + 1
            else -> 1
        }
    }

    /**
     * Where the series goes after a completion on [today].
     *
     * The anchor is the later of the task's own due date and today: completing an
     * occurrence early schedules on from that occurrence, and completing an
     * overdue one does not back-fill a backlog of missed rows.
     */
    fun nextOccurrenceAfterCompletion(
        rule: RepeatRule,
        repeatDays: List<Int>,
        dueDay: LocalDate?,
        today: LocalDate
    ): LocalDate? {
        val anchor = when {
            dueDay != null && dueDay.isAfter(today) -> dueDay
            else -> today
        }
        return nextOccurrence(rule, repeatDays, anchor)
    }

    /** Epoch millis for [date] at [time], resolved in [zone] so DST shifts are handled. */
    fun dueAtMillis(
        date: LocalDate,
        time: LocalTime = DEFAULT_TIME,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

    /** Local time of day for an existing due timestamp, or [DEFAULT_TIME]. */
    fun timeOfDayOf(
        epochMillis: Long?,
        zone: ZoneId = ZoneId.systemDefault()
    ): LocalTime = epochMillis
        ?.let { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), zone).toLocalTime() }
        ?: DEFAULT_TIME

    /** Local date for an existing due timestamp, or null. */
    fun dayOf(
        epochMillis: Long?,
        zone: ZoneId = ZoneId.systemDefault()
    ): LocalDate? = epochMillis
        ?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    /** Monday-first weekday labels for the repeat picker. */
    val weekdayLabels: List<String> = listOf("M", "T", "W", "T", "F", "S", "S")

    /** Human label such as "Every day" or "Mon, Thu". */
    fun describe(rule: RepeatRule, repeatDays: List<Int>): String = when (rule) {
        RepeatRule.NONE -> "Does not repeat"
        RepeatRule.DAILY -> "Every day"
        RepeatRule.WEEKLY -> {
            val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            val picked = repeatDays.filter { it in 1..7 }.distinct().sorted()
                .map { names[it - 1] }
            if (picked.isEmpty()) "Pick a day" else picked.joinToString(", ")
        }
    }

    /** Sunday-indexed convenience for callers that already hold a [DayOfWeek]. */
    fun dayValue(dayOfWeek: DayOfWeek): Int = dayOfWeek.value
}
