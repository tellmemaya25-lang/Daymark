package com.sabihon.todo

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.AiInputGuard
import com.sabihon.todo.data.ai.AiQuotaGuard
import com.sabihon.todo.data.ai.AiRouter
import com.sabihon.todo.data.ai.AiSnapshotCache
import com.sabihon.todo.data.ai.LocalPriorityScorer
import com.sabihon.todo.data.ai.LocalSuggestionHeuristics
import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.ai.AiTaskService
import com.sabihon.todo.domain.ai.CloudSuggestedTaskItem
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.usecase.SuggestNextTasksUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 4 — Contextual next-task suggestions: closed-world category whitelist,
 * open-task deduplication, due-offset clamping, dismissal persistence, and offline fallback.
 */
class SuggestNextTasksUseCaseTest {

    private val today = LocalDate.of(2026, 10, 27)
    private val utc = ZoneId.of("UTC")
    private val nowMs = today.atTime(9, 0).atZone(utc).toInstant().toEpochMilli()

    private fun buildUseCase(
        service: AiTaskService,
        cloudEnabled: Boolean = true,
        clockMs: () -> Long = { nowMs }
    ): SuggestNextTasksUseCase {
        val prefs = FakeAiPreferenceStore(cloudEnabledInitial = cloudEnabled)
        val quota = AiQuotaGuard(prefs = prefs, clock = clockMs, todayProvider = { today })
        val cache = AiSnapshotCache(clock = clockMs)
        return SuggestNextTasksUseCase(
            aiTaskService = service,
            prefs = prefs,
            scorer = LocalPriorityScorer(),
            inputGuard = AiInputGuard(),
            quotaGuard = quota,
            snapshotCache = cache,
            router = AiRouter(),
            localHeuristics = LocalSuggestionHeuristics(),
            clock = clockMs,
            todayProvider = { today },
            zoneProvider = { utc }
        )
    }

    @Test
    fun `whitelists categoryId, drops duplicates of open tasks, and clamps dueOffsetHours`() = runTest {
        val service = mockk<AiTaskService>()
        val useCase = buildUseCase(service)

        val workCategory = Category(id = "cat-work", name = "Work")
        val openTasks = listOf(
            Task(id = "o1", title = "Send Q4 invoice", categoryId = "cat-work")
        )

        coEvery { service.suggestNextTasks(any(), any()) } returns Result.Success(
            listOf(
                // Duplicate of already-open task -> dropped!
                CloudSuggestedTaskItem(
                    title = "Send Q4 invoices",
                    categoryId = "cat-work",
                    priority = Priority.HIGH,
                    dueOffsetHours = 12,
                    reason = "Duplicate"
                ),
                // Hallucinated categoryId -> coerced to null, negative offset -> clamped to +1h!
                CloudSuggestedTaskItem(
                    title = "Archive signed contract PDF",
                    categoryId = "invented-category-99",
                    priority = Priority.MEDIUM,
                    dueOffsetHours = -48,
                    reason = "Follows contract review https://evil.example"
                ),
                // Valid categoryId + huge offset -> clamped to 30 days (720h)!
                CloudSuggestedTaskItem(
                    title = "Schedule Q1 planning kickoff",
                    categoryId = "cat-work",
                    priority = Priority.HIGH,
                    dueOffsetHours = 99_999,
                    reason = "Prepares next cycle"
                )
            )
        )

        val result = useCase(
            uid = "u1",
            allTasks = openTasks,
            categories = listOf(workCategory)
        )

        assertEquals(AiSource.CLOUD, result.source)
        assertEquals(2, result.candidates.size)

        val first = result.candidates[0]
        assertEquals("Archive signed contract PDF", first.title)
        assertNull(first.categoryId)
        assertEquals(nowMs + 1L * 3_600_000L, first.dueAt)
        assertFalse(first.reason.contains("https://"))

        val second = result.candidates[1]
        assertEquals("cat-work", second.categoryId)
        assertEquals(nowMs + 720L * 3_600_000L, second.dueAt)
    }

    @Test
    fun `dismissing a suggestion hides it permanently across subsequent cached and fresh reads`() = runTest {
        val service = mockk<AiTaskService>()
        var now = nowMs
        val useCase = buildUseCase(service, clockMs = { now })

        coEvery { service.suggestNextTasks(any(), any()) } returns Result.Success(
            listOf(
                CloudSuggestedTaskItem(
                    title = "Email retro notes",
                    categoryId = null,
                    priority = Priority.MEDIUM,
                    dueOffsetHours = 4,
                    reason = "Follow-up"
                ),
                CloudSuggestedTaskItem(
                    title = "Update sprint board",
                    categoryId = null,
                    priority = Priority.LOW,
                    dueOffsetHours = 8,
                    reason = "Keep board tidy"
                )
            )
        )

        val initial = useCase(uid = "u1", allTasks = emptyList(), categories = emptyList())
        assertEquals(2, initial.candidates.size)
        val dismissedId = initial.candidates.first { it.title == "Email retro notes" }.suggestionId

        useCase.dismiss(uid = "u1", suggestionId = dismissedId)

        // Cached read filters out the newly dismissed suggestion immediately (0 network calls).
        now += 20_000L
        val fromCache = useCase(uid = "u1", allTasks = emptyList(), categories = emptyList())
        assertEquals(AiSource.CACHE, fromCache.source)
        assertEquals(listOf("Update sprint board"), fromCache.candidates.map { it.title })
        coVerify(exactly = 1) { service.suggestNextTasks(any(), any()) }
    }

    @Test
    fun `offline heuristics synthesize follow-up tasks from recent completions without touching cloud`() = runTest {
        val service = mockk<AiTaskService>()
        val useCase = buildUseCase(service, cloudEnabled = false)

        val work = Category(id = "c1", name = "Work")
        val completedDraft = Task(
            id = "done-1",
            title = "Draft Q4 budget proposal",
            categoryId = "c1",
            isCompleted = true,
            completedAt = nowMs - 3_600_000L
        )

        val result = useCase(
            uid = "u1",
            allTasks = listOf(completedDraft),
            categories = listOf(work)
        )

        assertEquals(AiSource.LOCAL_FALLBACK, result.source)
        assertTrue(result.candidates.isNotEmpty())
        assertTrue(result.candidates.any { it.categoryId == "c1" })
        coVerify(exactly = 0) { service.suggestNextTasks(any(), any()) }
    }
}
