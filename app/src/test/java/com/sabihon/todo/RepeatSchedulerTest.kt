package com.sabihon.todo

import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.usecase.RepeatScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Date maths for repeating tasks.
 *
 * Anchors are fixed dates so the tests never depend on when they run:
 * 2026-10-19 is a Monday, 2026-10-22 a Thursday.
 */
class RepeatSchedulerTest {

    private val mon = LocalDate.of(2026, 10, 19)
    private val tue = LocalDate.of(2026, 10, 20)
    private val wed = LocalDate.of(2026, 10, 21)
    private val thu = LocalDate.of(2026, 10, 22)
    private val fri = LocalDate.of(2026, 10, 23)
    private val sat = LocalDate.of(2026, 10, 24)
    private val sun = LocalDate.of(2026, 10, 25)
    private val nextMon = LocalDate.of(2026, 10, 26)

    private val monThu = listOf(1, 4)

    // ---- next occurrence ----

    @Test
    fun `daily next occurrence is tomorrow`() {
        assertEquals(tue, RepeatScheduler.nextOccurrence(RepeatRule.DAILY, emptyList(), mon))
    }

    @Test
    fun `weekly steps to the next selected day`() {
        assertEquals(thu, RepeatScheduler.nextOccurrence(RepeatRule.WEEKLY, monThu, mon))
    }

    @Test
    fun `weekly wraps across the weekend`() {
        assertEquals(nextMon, RepeatScheduler.nextOccurrence(RepeatRule.WEEKLY, monThu, thu))
    }

    @Test
    fun `weekly with no days selected has no next occurrence`() {
        assertNull(RepeatScheduler.nextOccurrence(RepeatRule.WEEKLY, emptyList(), mon))
    }

    @Test
    fun `one-off tasks never repeat`() {
        assertNull(RepeatScheduler.nextOccurrence(RepeatRule.NONE, emptyList(), mon))
    }

    @Test
    fun `invalid weekday numbers are ignored`() {
        assertNull(RepeatScheduler.nextOccurrence(RepeatRule.WEEKLY, listOf(0, 8, -3), mon))
    }

    // ---- missed occurrences ----

    @Test
    fun `daily gap of three days has missed two`() {
        // Tuesday and Wednesday went by; Thursday is not over yet.
        assertEquals(2, RepeatScheduler.missedOccurrences(RepeatRule.DAILY, emptyList(), mon, thu))
    }

    @Test
    fun `next day has missed nothing`() {
        assertEquals(0, RepeatScheduler.missedOccurrences(RepeatRule.DAILY, emptyList(), mon, tue))
    }

    @Test
    fun `the same day has missed nothing`() {
        assertEquals(0, RepeatScheduler.missedOccurrences(RepeatRule.DAILY, emptyList(), mon, mon))
    }

    @Test
    fun `weekly monday to thursday misses nothing`() {
        // This is the case calendar-day maths gets wrong: a 3-day gap, zero misses.
        assertEquals(0, RepeatScheduler.missedOccurrences(RepeatRule.WEEKLY, monThu, mon, thu))
    }

    @Test
    fun `weekly skipping thursday misses exactly one`() {
        assertEquals(1, RepeatScheduler.missedOccurrences(RepeatRule.WEEKLY, monThu, mon, nextMon))
    }

    @Test
    fun `weekly skipping two occurrences misses two`() {
        // Monday 19th completed, then nothing until Thursday 29th: the 22nd and the
        // 26th went by.
        val laterThu = nextMon.plusDays(3)
        assertEquals(2, RepeatScheduler.missedOccurrences(RepeatRule.WEEKLY, monThu, mon, laterThu))
    }

    // ---- streaks ----

    @Test
    fun `first completion starts a streak at one`() {
        assertEquals(1, RepeatScheduler.streakAfter(RepeatRule.DAILY, emptyList(), 0, null, mon))
    }

    @Test
    fun `completing twice in one day does not double count`() {
        assertEquals(4, RepeatScheduler.streakAfter(RepeatRule.DAILY, emptyList(), 4, mon, mon))
    }

    @Test
    fun `consecutive daily completions extend the streak`() {
        assertEquals(5, RepeatScheduler.streakAfter(RepeatRule.DAILY, emptyList(), 4, mon, tue))
    }

    @Test
    fun `one missed day is forgiven`() {
        // Last done Monday, skipped Tuesday, completed Wednesday.
        assertEquals(5, RepeatScheduler.streakAfter(RepeatRule.DAILY, emptyList(), 4, mon, wed))
    }

    @Test
    fun `two missed days reset the streak`() {
        // Last done Monday, skipped Tuesday and Wednesday, completed Thursday.
        assertEquals(1, RepeatScheduler.streakAfter(RepeatRule.DAILY, emptyList(), 9, mon, thu))
    }

    @Test
    fun `weekly streak survives the gap between selected days`() {
        // Monday then Thursday is a normal week, not a lapse.
        assertEquals(6, RepeatScheduler.streakAfter(RepeatRule.WEEKLY, monThu, 5, mon, thu))
    }

    @Test
    fun `weekly streak forgives one skipped day`() {
        assertEquals(6, RepeatScheduler.streakAfter(RepeatRule.WEEKLY, monThu, 5, mon, nextMon))
    }

    @Test
    fun `weekly streak resets after two skipped days`() {
        val laterThu = nextMon.plusDays(3)
        assertEquals(1, RepeatScheduler.streakAfter(RepeatRule.WEEKLY, monThu, 5, mon, laterThu))
    }

    @Test
    fun `completing early does not inflate the streak`() {
        // Tuesday is not a Monday/Thursday day: this is an early tick.
        assertEquals(5, RepeatScheduler.streakAfter(RepeatRule.WEEKLY, monThu, 5, mon, tue))
    }

    @Test
    fun `streak never goes below one`() {
        assertTrue(RepeatScheduler.streakAfter(RepeatRule.DAILY, emptyList(), -3, mon, tue) >= 1)
    }

    // ---- where the series goes next ----

    @Test
    fun `completing on time schedules the following occurrence`() {
        assertEquals(nextMon, RepeatScheduler.nextOccurrenceAfterCompletion(RepeatRule.WEEKLY, monThu, thu, thu))
    }

    @Test
    fun `a stale due date is not used as the anchor`() {
        // Due in March, completed today: tomorrow, not the day after March.
        val stale = LocalDate.of(2026, 3, 2)
        assertEquals(tue, RepeatScheduler.nextOccurrenceAfterCompletion(RepeatRule.DAILY, emptyList(), stale, mon))
    }

    @Test
    fun `completing early schedules from the due date, not from today`() {
        // Due Thursday, ticked off on Tuesday -> the series still advances to next Monday.
        assertEquals(nextMon, RepeatScheduler.nextOccurrenceAfterCompletion(RepeatRule.WEEKLY, monThu, thu, tue))
    }

    @Test
    fun `no due date anchors on today`() {
        assertEquals(tue, RepeatScheduler.nextOccurrenceAfterCompletion(RepeatRule.DAILY, emptyList(), null, mon))
    }

    // ---- time of day ----

    @Test
    fun `due millis keep the requested local time`() {
        val zone = ZoneId.of("Asia/Singapore")
        val millis = RepeatScheduler.dueAtMillis(tue, LocalTime.of(7, 30), zone)
        assertEquals(LocalTime.of(7, 30), RepeatScheduler.timeOfDayOf(millis, zone))
        assertEquals(tue, RepeatScheduler.dayOf(millis, zone))
    }

    @Test
    fun `missing due time falls back to nine in the morning`() {
        assertEquals(LocalTime.of(9, 0), RepeatScheduler.timeOfDayOf(null))
        assertNull(RepeatScheduler.dayOf(null))
    }

    // ---- labels ----

    @Test
    fun `describe names the rule`() {
        assertEquals("Every day", RepeatScheduler.describe(RepeatRule.DAILY, emptyList()))
        assertEquals("Mon, Thu", RepeatScheduler.describe(RepeatRule.WEEKLY, listOf(4, 1)))
        assertEquals("Does not repeat", RepeatScheduler.describe(RepeatRule.NONE, emptyList()))
    }

    @Test
    fun `every anchor resolves to the weekday the test assumes`() {
        // Keeps the rest of the file honest if these constants are ever edited.
        assertEquals(1, mon.dayOfWeek.value)
        assertEquals(4, thu.dayOfWeek.value)
        assertEquals(7, sun.dayOfWeek.value)
        assertEquals(7L, java.time.temporal.ChronoUnit.DAYS.between(mon, nextMon))
        assertTrue(fri.isAfter(thu) && sat.isAfter(fri))
    }
}
