package com.sabihon.todo

import com.sabihon.todo.data.ai.LocalPriorityScorer
import com.sabihon.todo.domain.ai.AiModelTier
import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.SubTask
import com.sabihon.todo.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Deterministic Rung-0 scoring invariants (`LocalPriorityScorer`).
 */
class LocalPriorityScorerTest {

    private val scorer = LocalPriorityScorer()
    private val today = LocalDate.of(2026, 10, 27)
    private val utc = ZoneId.of("UTC")
    private val nowNineAm = at(today, hour = 9)

    private fun at(date: LocalDate, hour: Int = 9, minute: Int = 0): Long =
        date.atTime(LocalTime.of(hour, minute)).atZone(utc).toInstant().toEpochMilli()

    @Test
    fun `deadline urgency strictly dominates distant low-priority work`() {
        val overdue = Task(id = "overdue", title = "Pay bill", dueAt = at(today.minusDays(1)), priority = Priority.LOW)
        val dueSoon = Task(id = "soon", title = "Standup", dueAt = at(today, hour = 10), priority = Priority.MEDIUM)
        val dueToday = Task(id = "today", title = "Review PR", dueAt = at(today, hour = 17), priority = Priority.MEDIUM)
        val nextWeek = Task(id = "later", title = "Clean garage", dueAt = at(today.plusDays(9)), priority = Priority.MEDIUM)

        val overdueScore = scorer.score(overdue, nowNineAm, today, utc)
        val soonScore = scorer.score(dueSoon, nowNineAm, today, utc)
        val todayScore = scorer.score(dueToday, nowNineAm, today, utc)
        val laterScore = scorer.score(nextWeek, nowNineAm, today, utc)

        assertTrue(overdueScore > todayScore)
        assertTrue(soonScore > todayScore)
        assertTrue(todayScore > laterScore)
    }

    @Test
    fun `active habit streak due today boosts score and upgrades low priority`() {
        val plain = Task(
            id = "plain",
            title = "Read",
            dueAt = at(today, hour = 20),
            priority = Priority.LOW,
            repeat = RepeatRule.NONE,
            streak = 0
        )
        val habitStreak = plain.copy(
            id = "habit",
            repeat = RepeatRule.DAILY,
            streak = 12
        )

        assertTrue(
            scorer.score(habitStreak, nowNineAm, today, utc) >
                scorer.score(plain, nowNineAm, today, utc)
        )

        val plan = scorer.buildLocalPlan(listOf(plain, habitStreak), nowNineAm, today, utc)
        val habitRec = plan.recommendations.first { it.taskId == "habit" }
        assertTrue(habitRec.suggestedPriority.ordinal < Priority.LOW.ordinal)
        assertTrue(habitRec.hasPriorityChange)
    }

    @Test
    fun `checklist momentum rewards tasks that are almost finished`() {
        val untouched = Task(
            id = "untouched",
            title = "Quarterly report",
            subTasks = listOf(
                SubTask(id = "1", title = "Data", isDone = false),
                SubTask(id = "2", title = "Charts", isDone = false),
                SubTask(id = "3", title = "Summary", isDone = false),
                SubTask(id = "4", title = "Send", isDone = false)
            )
        )
        val almostDone = untouched.copy(
            id = "almost",
            subTasks = untouched.subTasks.mapIndexed { idx, sub -> sub.copy(isDone = idx < 3) }
        )

        assertTrue(
            scorer.score(almostDone, nowNineAm, today, utc) >
                scorer.score(untouched, nowNineAm, today, utc)
        )
    }

    @Test
    fun `anti-starvation age boost lifts long-standing undated tasks`() {
        val brandNew = Task(id = "new", title = "File receipts", createdAt = nowNineAm)
        val twoWeeksOld = Task(
            id = "stale",
            title = "File receipts",
            createdAt = nowNineAm - 15L * 24 * 3_600_000L
        )

        assertTrue(
            scorer.score(twoWeeksOld, nowNineAm, today, utc) >
                scorer.score(brandNew, nowNineAm, today, utc)
        )
    }

    @Test
    fun `buildLocalPlan caps top focus at 3, assigns sequential ranks, and upgrades overdue low task`() {
        val tasks = listOf(
            Task(id = "t1", title = "Overdue tax form", dueAt = at(today.minusDays(2)), priority = Priority.LOW),
            Task(id = "t2", title = "Client call", dueAt = at(today, hour = 10), priority = Priority.HIGH),
            Task(id = "t3", title = "Ship release", dueAt = at(today, hour = 16), priority = Priority.MEDIUM),
            Task(id = "t4", title = "Organize desk", dueAt = at(today.plusDays(4)), priority = Priority.LOW)
        )

        val plan = scorer.buildLocalPlan(tasks, nowNineAm, today, utc)

        assertEquals(AiSource.LOCAL_FALLBACK, plan.source)
        assertEquals(AiModelTier.LOCAL, plan.modelTier)
        assertEquals(listOf(1, 2, 3, 4), plan.recommendations.map { it.focusRank })
        assertEquals(3, plan.recommendations.count { it.isTopFocus })

        val overdueRec = plan.recommendations.first { it.taskId == "t1" }
        assertTrue(overdueRec.suggestedPriority == Priority.URGENT || overdueRec.suggestedPriority == Priority.HIGH)
        assertTrue(overdueRec.hasPriorityChange)
    }
}
