package com.sabihon.todo

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.LocalSuggestionHeuristics
import com.sabihon.todo.domain.ai.AiTaskService
import com.sabihon.todo.domain.ai.SuggestedTaskCandidate
import com.sabihon.todo.domain.ai.SuggestionContext
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.usecase.SuggestNextTasksUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The rules that apply to a suggestion whoever produced it
 * (`docs/ai-task-prioritization-and-suggestions-plan.md` Phase 4).
 *
 * The four the plan calls out by name: an invented list id becomes null, a duplicate of
 * an open task is dropped, a muted id stays hidden, and the due offset clamps.
 */
class SuggestNextTasksUseCaseTest {

    private val service = mockk<AiTaskService>()
    private val heuristics = LocalSuggestionHeuristics()
    private val useCase = SuggestNextTasksUseCase(service, heuristics)

    private val work = Category(id = "c1", name = "Work")
    private val home = Category(id = "c2", name = "Home")

    private fun candidate(
        title: String,
        categoryId: String? = null,
        dueOffsetHours: Int? = null,
        priority: Priority = Priority.MEDIUM
    ) = SuggestedTaskCandidate(
        suggestionId = SuggestedTaskCandidate.idFor(title, categoryId),
        title = title,
        categoryId = categoryId,
        priority = priority,
        dueOffsetHours = dueOffsetHours,
        reason = "because"
    )

    private fun cloudReturns(vararg candidates: SuggestedTaskCandidate) {
        coEvery { service.suggestNextTasks(any()) } returns Result.Success(candidates.toList())
    }

    private fun openTask(title: String) = Task(id = title, title = title, isCompleted = false)

    @Test
    fun `an invented list id becomes null`() = runTest {
        cloudReturns(candidate("Email the client", categoryId = "not-a-real-list"))

        val result = useCase(
            openTasks = listOf(openTask("Existing")),
            recentCompleted = emptyList(),
            categories = listOf(work, home)
        )

        assertNull(result.candidates.single().categoryId)
    }

    @Test
    fun `a suggestion duplicating an open task is dropped`() = runTest {
        cloudReturns(
            candidate("Buy milk"),
            candidate("Call the bank")
        )

        val result = useCase(
            openTasks = listOf(openTask("buy MILK")),
            recentCompleted = emptyList(),
            categories = listOf(home)
        )

        assertEquals(listOf("Call the bank"), result.candidates.map { it.title })
    }

    @Test
    fun `a muted id stays hidden`() = runTest {
        val muted = candidate("Review budget")
        // The use case filters against the store; wiring a fake with that id is the same
        // as the user having tapped × on it last week.
        val filtered = useCase.clean(
            raw = listOf(muted, candidate("Plan the week")),
            open = emptyList(),
            categories = listOf(work),
            dismissed = setOf(muted.suggestionId)
        )

        assertEquals(listOf("Plan the week"), filtered.map { it.title })
    }

    @Test
    fun `a due offset is clamped into a sane range and never negative`() = runTest {
        cloudReturns(
            candidate("Overdue already", dueOffsetHours = -40),
            candidate("Next year", dueOffsetHours = 9_000)
        )

        val result = useCase(
            openTasks = listOf(openTask("Existing")),
            recentCompleted = emptyList(),
            categories = listOf(work)
        )

        assertEquals(0, result.candidates[0].dueOffsetHours)
        assertEquals(14 * 24, result.candidates[1].dueOffsetHours)
    }

    @Test
    fun `an undated suggestion stays undated`() = runTest {
        cloudReturns(candidate("Think about the launch"))

        val result = useCase(
            openTasks = listOf(openTask("Existing")),
            recentCompleted = emptyList(),
            categories = listOf(work)
        )

        assertNull(result.candidates.single().dueOffsetHours)
    }

    @Test
    fun `no more than three suggestions survive`() = runTest {
        cloudReturns(
            candidate("One"), candidate("Two"), candidate("Three"),
            candidate("Four"), candidate("Five")
        )

        val result = useCase(
            openTasks = listOf(openTask("Existing")),
            recentCompleted = emptyList(),
            categories = listOf(work)
        )

        assertEquals(3, result.candidates.size)
    }

    @Test
    fun `duplicates inside one answer collapse`() = runTest {
        cloudReturns(candidate("Plan the week"), candidate("plan the WEEK"))

        val result = useCase(
            openTasks = listOf(openTask("Existing")),
            recentCompleted = emptyList(),
            categories = listOf(work)
        )

        assertEquals(1, result.candidates.size)
    }

    @Test
    fun `a failed call falls back to the local heuristics`() = runTest {
        coEvery { service.suggestNextTasks(any()) } returns
            Result.Error(IllegalStateException("no network"))

        val today = LocalDate.of(2026, 10, 28)
        val slipped = Task(
            id = "t1",
            title = "File the receipts",
            dueAt = today.minusDays(5).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            isCompleted = false
        )

        val result = useCase(
            openTasks = listOf(slipped),
            recentCompleted = emptyList(),
            categories = emptyList()
        )

        assertEquals(com.sabihon.todo.domain.usecase.NextTaskSuggestions.Source.LOCAL, result.source)
        assertTrue(result.candidates.isNotEmpty())
        assertTrue(result.candidates.first().title.startsWith("Reschedule"))
    }

    @Test
    fun `an empty list with nothing completed gets a message, not a card`() = runTest {
        val result = useCase(
            openTasks = emptyList(),
            recentCompleted = emptyList(),
            categories = emptyList()
        )

        assertTrue(result.candidates.isEmpty())
        assertEquals("Nothing to suggest from yet - add a task or two", result.message)
    }

    /* ---------------- local heuristics ---------------- */

    @Test
    fun `a fresh habit with a streak is offered before the day ends`() {
        val today = LocalDate.of(2026, 10, 28)
        val habit = Task(
            id = "h1",
            title = "Walk",
            repeat = RepeatRule.DAILY,
            streak = 4,
            lastCompletedDay = today.minusDays(1).toEpochDay()
        )

        val suggestions = heuristics.suggest(
            openTasks = listOf(habit),
            recentCompleted = emptyList(),
            categories = emptyList(),
            today = today
        )

        val streak = suggestions.first()
        assertTrue(streak.title.contains("Walk"))
        assertEquals(Priority.HIGH, streak.priority)
    }

    @Test
    fun `a habit already completed today is not suggested`() {
        val today = LocalDate.of(2026, 10, 28)
        val habit = Task(
            id = "h1",
            title = "Walk",
            repeat = RepeatRule.DAILY,
            streak = 9,
            lastCompletedDay = today.toEpochDay()
        )

        val suggestions = heuristics.suggest(
            openTasks = listOf(habit),
            recentCompleted = emptyList(),
            categories = emptyList(),
            today = today
        )

        assertTrue(suggestions.none { it.title.contains("Walk") })
    }

    @Test
    fun `a project finished but left unplanned is suggested`() {
        val done = (1..3).map { idx ->
            Task(id = "d$idx", title = "Task $idx", categoryId = "c1", isCompleted = true)
        }

        val suggestions = heuristics.suggest(
            openTasks = emptyList(),
            recentCompleted = done,
            categories = listOf(work)
        )

        assertEquals(1, suggestions.size)
        assertEquals("c1", suggestions.single().categoryId)
    }

    @Test
    fun `a project with something still open is not suggested`() {
        val done = (1..3).map { idx ->
            Task(id = "d$idx", title = "Task $idx", categoryId = "c1", isCompleted = true)
        }

        val suggestions = heuristics.suggest(
            openTasks = listOf(Task(id = "o1", title = "Still going", categoryId = "c1")),
            recentCompleted = done,
            categories = listOf(work)
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `work that slipped is offered as a decision, not as urgent`() {
        val today = LocalDate.of(2026, 10, 28)
        val zone = ZoneId.of("UTC")
        val stale = Task(
            id = "s1",
            title = "Pay the invoice",
            dueAt = today.minusDays(3).atStartOfDay(zone).toInstant().toEpochMilli(),
            priority = Priority.LOW
        )

        val suggestions = heuristics.suggest(
            openTasks = listOf(stale),
            recentCompleted = emptyList(),
            categories = emptyList(),
            today = today,
            zone = zone
        )

        val candidate = suggestions.single()
        assertEquals("Reschedule Pay the invoice", candidate.title)
        assertEquals(Priority.MEDIUM, candidate.priority)
        assertTrue(candidate.reason.contains("3 days"))
    }

    @Test
    fun `something due tomorrow is not stale yet`() {
        val today = LocalDate.of(2026, 10, 28)
        val zone = ZoneId.of("UTC")
        val tomorrow = Task(
            id = "s1",
            title = "Pay the invoice",
            dueAt = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        )

        val suggestions = heuristics.suggest(
            openTasks = listOf(tomorrow),
            recentCompleted = emptyList(),
            categories = emptyList(),
            today = today,
            zone = zone
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `the suggestion id is stable and does not contain the title`() {
        val first = SuggestedTaskCandidate.idFor("Review budget", "c1")
        val second = SuggestedTaskCandidate.idFor("  review BUDGET  ", "c1")

        assertEquals(first, second)
        assertTrue(!first.contains("budget", ignoreCase = true))
    }

    @Test
    fun `the context carries titles only, capped at ten and eight`() = runTest {
        var seen: SuggestionContext? = null
        coEvery { service.suggestNextTasks(any()) } answers {
            seen = firstArg()
            Result.Success(emptyList<SuggestedTaskCandidate>())
        }

        useCase(
            openTasks = (1..15).map { openTask("Open $it") },
            recentCompleted = (1..12).map {
                Task(id = "c$it", title = "Done $it", isCompleted = true)
            },
            categories = listOf(work)
        )

        assertEquals(10, seen!!.openTitles.size)
        assertEquals(8, seen!!.recentCompletedTitles.size)
    }

    @Test
    fun `interactive fallback offers calm starter suggestions even on a fresh list`() = runTest {
        val result = useCase(
            openTasks = emptyList(),
            recentCompleted = emptyList(),
            categories = listOf(work),
            forceInteractive = true
        )

        assertTrue(result.candidates.isNotEmpty())
        assertTrue(result.candidates.size <= 3)
    }
}
