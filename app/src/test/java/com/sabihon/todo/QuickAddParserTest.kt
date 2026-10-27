package com.sabihon.todo

import com.sabihon.todo.data.ai.QuickAddParser
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
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

    // ---- fuzzy reading: times that take more than one word ----

    @Test
    fun `half past and quarter to are read as times`() {
        val half = parse("call mom half past five")
        assertEquals("Call mom", half.title)
        assertEquals(millis(monday, 5, 30), half.dueAt)

        val quarter = parse("leave quarter to six")
        assertEquals("Leave", quarter.title)
        assertEquals(millis(monday, 5, 45), quarter.dueAt)
    }

    @Test
    fun `an hour spelled as a word still takes its meridiem`() {
        val draft = parse("gym at five pm")

        assertEquals("Gym", draft.title)
        assertEquals(millis(monday, 17), draft.dueAt)
    }

    @Test
    fun `tonight and this evening land in the evening`() {
        assertEquals(millis(monday, 20), parse("call mom tonight").dueAt)
        assertEquals(millis(monday, 18), parse("call mom this evening").dueAt)
    }

    @Test
    fun `a weekday with a part of day uses both`() {
        val draft = parse("gig friday evening")

        assertEquals("Gig", draft.title)
        assertEquals(millis(LocalDate.of(2026, 10, 30), 18), draft.dueAt)
    }

    @Test
    fun `a part of day with no day spoken is not a time`() {
        // The guard: "movie night" is a title, not 20:00 today.
        val draft = parse("movie night")

        assertNull(draft.dueAt)
        assertEquals("Movie night", draft.title)
    }

    @Test
    fun `in thirty minutes is an offset from now`() {
        val now = Instant.parse("2026-10-26T09:00:00Z")
        val draft = parser.parse(
            "take the pasta off in 30 minutes",
            emptyList(),
            today = monday,
            zone = utc,
            now = now
        )

        assertEquals(millis(monday, 9, 30), draft.dueAt)
        assertEquals("Take the pasta off", draft.title)
    }

    // ---- fuzzy reading: reminders ----

    @Test
    fun `remind me asks for a reminder and leaves the title clean`() {
        val draft = parse("remind me to call mom at 5pm")

        assertEquals("Call mom", draft.title)
        assertEquals(true, draft.reminder)
        assertNull(draft.reminderLeadMinutes)
        assertEquals(millis(monday, 17), draft.dueAt)
    }

    @Test
    fun `a stated lead time becomes minutes before`() {
        val draft = parse("standup at 9am remind me 15 minutes before")

        assertEquals(true, draft.reminder)
        assertEquals(15, draft.reminderLeadMinutes)
        assertEquals(millis(monday, 9), draft.dueAt)
    }

    @Test
    fun `no reminder turns the switch off rather than on`() {
        // Both words are in the sentence; the negation has to win.
        val draft = parse("no reminder, call mom tomorrow")

        assertEquals(false, draft.reminder)
    }

    @Test
    fun `do not forget still asks for a reminder`() {
        val draft = parse("don't forget to call mom at 5pm")

        assertEquals(true, draft.reminder)
        assertEquals("Call mom", draft.title)
    }

    @Test
    fun `a sentence with no reminder words says nothing about reminders`() {
        // null, not false: the editor must not switch off a switch the user set.
        assertNull(parse("call mom friday 5pm").reminder)
    }

    // ---- spoken sub-task separators ----

    @Test
    fun `add subtask starts a sub-task`() {
        val draft = parse("buy groceries add subtask milk")

        assertEquals("Buy groceries", draft.title)
        assertEquals(listOf("milk"), draft.subTasks)
    }

    @Test
    fun `several separators make several sub-tasks`() {
        val draft = parse("call mom add subtask book taxi add subtask pack bag")

        assertEquals("Call mom", draft.title)
        assertEquals(listOf("book taxi", "pack bag"), draft.subTasks)
    }

    @Test
    fun `new line and next are separators too`() {
        val newLine = parse("gym tomorrow new line stretching")
        assertEquals("Gym", newLine.title)
        assertEquals(listOf("stretching"), newLine.subTasks)
        assertEquals(millis(monday.plusDays(1), 9), newLine.dueAt)

        val next = parse("pack next print the tickets next charge the camera")
        assertEquals("Pack", next.title)
        assertEquals(listOf("print the tickets", "charge the camera"), next.subTasks)
    }

    @Test
    fun `next before a weekday is a date, not a separator`() {
        // The guard: this must stay one task due a week out.
        val draft = parse("review goals next monday")

        assertTrue(draft.subTasks.isEmpty())
        assertEquals("Review goals", draft.title)
        assertEquals(millis(monday.plusDays(7), 9), draft.dueAt)
    }

    @Test
    fun `a comma is not a separator`() {
        // "call mom, friday 5pm" is one task with a date - splitting it would invent a
        // sub-task called "friday 5pm".
        val draft = parse("call mom, friday 5pm")

        assertTrue(draft.subTasks.isEmpty())
        assertEquals("Call mom", draft.title)
        assertEquals(millis(LocalDate.of(2026, 10, 30), 17), draft.dueAt)
    }

    @Test
    fun `a spoken separator with a comma still cuts`() {
        val draft = parse("buy groceries add subtask, milk")

        assertEquals("Buy groceries", draft.title)
        assertEquals(listOf("milk"), draft.subTasks)
    }

    @Test
    fun `a trailing separator adds nothing`() {
        val draft = parse("call mom add subtask")

        assertEquals("Call mom", draft.title)
        assertTrue(draft.subTasks.isEmpty())
    }

    @Test
    fun `sub-tasks are capped at eight`() {
        val line = "move house" + (1..12).joinToString("") { " add subtask step $it" }
        val draft = parse(line)

        assertEquals(8, draft.subTasks.size)
        assertEquals("step 1", draft.subTasks.first())
    }

    @Test
    fun `a sentence with no separator has no sub-tasks`() {
        val draft = parse("call mom friday 5pm")

        assertEquals("Call mom", draft.title)
        assertTrue(draft.subTasks.isEmpty())
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
