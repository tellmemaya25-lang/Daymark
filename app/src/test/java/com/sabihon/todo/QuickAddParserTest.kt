package com.sabihon.todo

import com.sabihon.todo.data.ai.QuickAddParser
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The offline half of quick add.
 *
 * Everything is pinned: 2026-10-26 is a Monday, and the zone is UTC, so these never
 * depend on when or where they run. This is the part of the feature that must keep
 * working with the network down and the model switched off, which makes it the part
 * worth testing hardest.
 */
class QuickAddParserTest {

    private val parser = QuickAddParser()
    private val monday = LocalDate.of(2026, 10, 26)
    private val utc = ZoneId.of("UTC")

    private fun parse(input: String, categories: List<Category> = emptyList()) =
        parser.parse(input, categories, today = monday, zone = utc)

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(LocalTime.of(hour, minute)).atZone(utc).toInstant().toEpochMilli()

    @Test
    fun `a weekday and a time land on the right instant`() {
        val draft = parse("call mom friday 5pm")

        assertEquals("Call mom", draft.title)
        assertEquals(millis(LocalDate.of(2026, 10, 30), 17), draft.dueAt)
    }

    @Test
    fun `a date with no time lands at nine in the morning`() {
        val draft = parse("file the report tomorrow")

        assertEquals("File the report", draft.title)
        assertEquals(millis(monday.plusDays(1), 9), draft.dueAt)
    }

    @Test
    fun `a time with no date means today`() {
        val draft = parse("call the dentist at 15:30")

        assertEquals("Call the dentist", draft.title)
        assertEquals(millis(monday, 15, 30), draft.dueAt)
    }

    @Test
    fun `priority words are read and removed from the title`() {
        val urgent = parse("pay rent p1")
        assertEquals(Priority.URGENT, urgent.priority)
        assertEquals("Pay rent", urgent.title)

        assertEquals(Priority.HIGH, parse("pay rent high").priority)
        assertEquals(Priority.LOW, parse("pay rent p4").priority)
        assertNull(parse("pay rent").priority)
    }

    @Test
    fun `an at-mention picks a list that exists and leaves an unknown one alone`() {
        val work = Category(id = "c1", name = "Work")
        val gym = Category(id = "c2", name = "Gym")

        val matched = parse("finish the deck @work", listOf(work, gym))
        assertEquals("c1", matched.categoryId)
        assertEquals("Finish the deck", matched.title)

        val unknown = parse("finish the deck @garage", listOf(work, gym))
        assertNull(unknown.categoryId)
        assertEquals("Finish the deck @garage", unknown.title)
    }

    @Test
    fun `every plus weekdays becomes a weekly habit on those days`() {
        val draft = parse("gym every mon wed fri")

        assertEquals(RepeatRule.WEEKLY, draft.repeat)
        assertEquals(listOf(1, 3, 5), draft.repeatDays)
        assertEquals("Gym", draft.title)
    }

    @Test
    fun `every day becomes a daily habit`() {
        val draft = parse("stretch every day")

        assertEquals(RepeatRule.DAILY, draft.repeat)
        assertTrue(draft.repeatDays.isEmpty())
        assertEquals("Stretch", draft.title)
    }

    @Test
    fun `a dotted p m after the hour makes it evening`() {
        // The regression: "8:00 p.m." matched the time pattern before the p.m. was ever
        // read, so it landed at 08:00 in the morning with "p.m." left in the title.
        val draft = parse("practice guitar around 8:00 p.m.")

        assertEquals("Practice guitar", draft.title)
        assertEquals(millis(monday, 20), draft.dueAt)
    }

    @Test
    fun `a meridiem written as its own word attaches to the hour`() {
        val dotted = parse("call mom at 8 p.m. later")
        assertEquals("Call mom later", dotted.title)
        assertEquals(millis(monday, 20), dotted.dueAt)

        val bare = parse("call mom at 8 pm")
        assertEquals("Call mom", bare.title)
        assertEquals(millis(monday, 20), bare.dueAt)
    }

    @Test
    fun `an am written with dots stays in the morning`() {
        val draft = parse("standup 9 a.m.")

        assertEquals("Standup", draft.title)
        assertEquals(millis(monday, 9), draft.dueAt)
    }

    @Test
    fun `noon and half past midnight land on the right hours`() {
        assertEquals(millis(monday, 12), parse("lunch 12 pm").dueAt)
        assertEquals(millis(monday, 0, 30), parse("bed 12:30 a.m.").dueAt)
    }

    @Test
    fun `a number followed by an ordinary word is still not a time`() {
        // The guard the meridiem change could have broken.
        val draft = parse("buy 2 apples tomorrow")

        assertEquals("Buy 2 apples", draft.title)
        assertEquals(millis(monday.plusDays(1), 9), draft.dueAt)
    }

    @Test
    fun `an ordinary number is not a time`() {
        val draft = parse("buy 2 apples")

        assertNull(draft.dueAt)
        assertEquals("Buy 2 apples", draft.title)
    }

    @Test
    fun `next monday on a monday means a week away`() {
        val draft = parse("review goals next monday")

        assertEquals(millis(monday.plusDays(7), 9), draft.dueAt)
    }

    @Test
    fun `a sentence with nothing to extract is all title`() {
        val draft = parse("think about the workshop")

        assertEquals("Think about the workshop", draft.title)
        assertNull(draft.dueAt)
        assertNull(draft.priority)
        assertNull(draft.categoryId)
        assertTrue(!draft.hasDetails)
    }

    @Test
    fun `an empty sentence produces an empty draft rather than an exception`() {
        val draft = parse("   ")

        assertEquals("", draft.title)
        assertNull(draft.dueAt)
        assertTrue(!draft.hasDetails)
    }
}
