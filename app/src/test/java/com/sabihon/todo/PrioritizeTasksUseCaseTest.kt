package com.sabihon.todo

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.AiInputGuard
import com.sabihon.todo.data.ai.AiQuotaGuard
import com.sabihon.todo.data.ai.AiRouter
import com.sabihon.todo.data.ai.AiSnapshotCache
import com.sabihon.todo.data.ai.LocalPriorityScorer
import com.sabihon.todo.domain.ai.AiModelTier
import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.ai.AiTaskService
import com.sabihon.todo.domain.ai.CloudPrioritizationDraft
import com.sabihon.todo.domain.ai.CloudPriorityItem
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.repository.TaskRepository
import com.sabihon.todo.domain.usecase.ApplyPriorityPlanUseCase
import com.sabihon.todo.domain.usecase.PrioritizeTasksUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * End-to-end orchestration, closed-world sanitization, retry/fallback resilience,
 * and atomic diff-only apply + undo tests.
 */
class PrioritizeTasksUseCaseTest {

    private val today = LocalDate.of(2026, 10, 27)
    private val utc = ZoneId.of("UTC")
    private val nowMs = today.atTime(9, 0).atZone(utc).toInstant().toEpochMilli()

    private fun buildUseCase(
        service: AiTaskService,
        cloudEnabled: Boolean = true,
        clockMs: () -> Long = { nowMs }
    ): Triple<PrioritizeTasksUseCase, AiQuotaGuard, AiSnapshotCache> {
        val prefs = FakeAiPreferenceStore(cloudEnabledInitial = cloudEnabled)
        val quota = AiQuotaGuard(prefs = prefs, clock = clockMs, todayProvider = { today })
        val cache = AiSnapshotCache(clock = clockMs)
        val useCase = PrioritizeTasksUseCase(
            aiTaskService = service,
            prefs = prefs,
            scorer = LocalPriorityScorer(),
            inputGuard = AiInputGuard(),
            quotaGuard = quota,
            snapshotCache = cache,
            router = AiRouter(),
            clock = clockMs,
            todayProvider = { today },
            zoneProvider = { utc }
        )
        return Triple(useCase, quota, cache)
    }

    @Test
    fun `closed-world sanitizer drops hallucinated taskIds, strips markdown and URLs, and appends missing open tasks`() = runTest {
        val service = mockk<AiTaskService>()
        val (useCase, _, _) = buildUseCase(service)

        val tasks = listOf(
            Task(id = "real-1", title = "Prepare deck", priority = Priority.LOW),
            Task(id = "real-2", title = "Review budget", priority = Priority.MEDIUM),
            Task(id = "real-3", title = "Send invoice", priority = Priority.HIGH)
        )

        coEvery { service.prioritizeTasks(any(), any()) } returns Result.Success(
            CloudPrioritizationDraft(
                summaryHeadline = "**Focus** on the deck first https://evil.example/click",
                items = listOf(
                    CloudPriorityItem(
                        taskId = "invented-id-999",
                        suggestedPriority = Priority.URGENT,
                        focusRank = 1,
                        isTopFocus = true,
                        reason = "Hallucinated task"
                    ),
                    CloudPriorityItem(
                        taskId = "real-2",
                        suggestedPriority = Priority.URGENT,
                        focusRank = 1,
                        isTopFocus = true,
                        reason = "`Budget` blocks Q4 https://phish.example"
                    ),
                    // Duplicate entry for real-2 should be ignored
                    CloudPriorityItem(
                        taskId = "real-2",
                        suggestedPriority = Priority.LOW,
                        focusRank = 2,
                        isTopFocus = false,
                        reason = "Duplicate"
                    )
                )
            )
        )

        val plan = useCase(uid = "u1", tasks = tasks, categories = emptyList())

        assertEquals(AiSource.CLOUD, plan.source)
        assertEquals(AiModelTier.FLASH_LITE, plan.modelTier)
        // All 3 open tasks are present with sequential ranks 1..3; hallucinated ID is gone.
        assertEquals(3, plan.recommendations.size)
        assertEquals(listOf(1, 2, 3), plan.recommendations.map { it.focusRank })
        assertEquals("real-2", plan.recommendations.first().taskId)
        assertEquals(Priority.URGENT, plan.recommendations.first().suggestedPriority)
        assertFalse(plan.recommendations.first().reason.contains("https://"))
        assertFalse(plan.recommendations.first().reason.contains("`"))
        assertFalse(plan.summaryHeadline.contains("https://"))
        assertFalse(plan.recommendations.any { it.taskId == "invented-id-999" })
    }

    @Test
    fun `transient 503 retries once and succeeds, while second identical call hits snapshot cache`() = runTest {
        val service = mockk<AiTaskService>()
        var now = nowMs
        val (useCase, _, _) = buildUseCase(service, clockMs = { now })

        val tasks = listOf(
            Task(id = "a", title = "Write spec", priority = Priority.MEDIUM),
            Task(id = "b", title = "Fix flaky test", priority = Priority.HIGH)
        )

        var attempts = 0
        coEvery { service.prioritizeTasks(any(), any()) } answers {
            attempts += 1
            if (attempts == 1) {
                Result.Error(RuntimeException("503 Service Unavailable"))
            } else {
                Result.Success(
                    CloudPrioritizationDraft(
                        summaryHeadline = "Fix the test first.",
                        items = listOf(
                            CloudPriorityItem("b", Priority.URGENT, 1, true, "Unblocks CI"),
                            CloudPriorityItem("a", Priority.MEDIUM, 2, true, "Next up")
                        )
                    )
                )
            }
        }

        val firstPlan = useCase(uid = "u1", tasks = tasks, categories = emptyList())
        assertEquals(AiSource.CLOUD, firstPlan.source)
        assertEquals(2, attempts)

        // Second call with unchanged tasks returns CACHE without touching AiTaskService again.
        now += 20_000L
        val secondPlan = useCase(uid = "u1", tasks = tasks, categories = emptyList())
        assertEquals(AiSource.CACHE, secondPlan.source)
        assertEquals(2, attempts)
    }

    @Test
    fun `429 failure trips circuit breaker and falls back to deterministic local plan without throwing`() = runTest {
        val service = mockk<AiTaskService>()
        val (useCase, quota, _) = buildUseCase(service)

        val tasks = listOf(
            Task(id = "a", title = "Overdue rent", dueAt = nowMs - 3_600_000L, priority = Priority.LOW),
            Task(id = "b", title = "Someday book", priority = Priority.LOW)
        )

        coEvery { service.prioritizeTasks(any(), any()) } returns
            Result.Error(RuntimeException("429 RESOURCE_EXHAUSTED"))

        val fallback = useCase(uid = "u1", tasks = tasks, categories = emptyList())

        assertEquals(AiSource.LOCAL_FALLBACK, fallback.source)
        assertTrue(quota.isCircuitOpen())
        assertEquals("a", fallback.recommendations.first().taskId)
        coVerify(exactly = 1) { service.prioritizeTasks(any(), any()) }
    }

    @Test
    fun `ApplyPriorityPlanUseCase writes only genuine diffs and restores exact prior priorities on undo`() = runTest {
        val repo = mockk<TaskRepository>()
        coEvery { repo.batchUpdatePriorities(any()) } returns Result.Success(Unit)

        val applyUseCase = ApplyPriorityPlanUseCase(repo)
        val tasks = listOf(
            Task(id = "t1", title = "One", priority = Priority.LOW),
            Task(id = "t2", title = "Two", priority = Priority.HIGH),
            Task(id = "t3", title = "Three", priority = Priority.MEDIUM, isDeleted = true)
        )

        val selected = mapOf(
            "t1" to Priority.URGENT, // genuine diff (LOW -> URGENT)
            "t2" to Priority.HIGH,   // unchanged -> skipped
            "t3" to Priority.URGENT, // deleted -> skipped
            "ghost" to Priority.HIGH // missing -> skipped
        )

        val result = applyUseCase(tasks, selected)
        assertTrue(result is Result.Success)
        val snapshot = (result as Result.Success).data
        assertEquals(mapOf("t1" to Priority.LOW), snapshot.previousPriorities)

        coVerify(exactly = 1) { repo.batchUpdatePriorities(mapOf("t1" to Priority.URGENT)) }

        // Undo restores t1 -> Priority.LOW
        val undoResult = applyUseCase.undo(snapshot)
        assertTrue(undoResult is Result.Success)
        coVerify(exactly = 1) { repo.batchUpdatePriorities(mapOf("t1" to Priority.LOW)) }
    }
}
