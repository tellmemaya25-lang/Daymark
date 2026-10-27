package com.sabihon.todo

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.AiInputGuard
import com.sabihon.todo.data.ai.AiQuotaGuard
import com.sabihon.todo.data.ai.AiRouter
import com.sabihon.todo.data.ai.AiSnapshotCache
import com.sabihon.todo.data.ai.LocalSuggestionHeuristics
import com.sabihon.todo.domain.ai.AiModelTier
import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.ai.AiTaskService
import com.sabihon.todo.domain.usecase.SuggestSubTasksUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Phase 3 — Sub-task breakdown sanitization, semantic Jaccard caching, and offline fallback tests.
 */
class SuggestSubTasksUseCaseTest {

    private val today = LocalDate.of(2026, 10, 27)

    private fun buildUseCase(
        service: AiTaskService,
        cloudEnabled: Boolean = true,
        clockMs: () -> Long = { 1_000_000L }
    ): Pair<SuggestSubTasksUseCase, AiSnapshotCache> {
        val prefs = FakeAiPreferenceStore(cloudEnabledInitial = cloudEnabled)
        val quota = AiQuotaGuard(prefs = prefs, clock = clockMs, todayProvider = { today })
        val cache = AiSnapshotCache(clock = clockMs)
        val useCase = SuggestSubTasksUseCase(
            aiTaskService = service,
            prefs = prefs,
            inputGuard = AiInputGuard(),
            quotaGuard = quota,
            snapshotCache = cache,
            router = AiRouter(),
            localHeuristics = LocalSuggestionHeuristics(),
            clock = clockMs
        )
        return useCase to cache
    }

    @Test
    fun `strips bullets, markdown, and URLs, deduplicates existing steps, drops title echo, and caps at 6`() = runTest {
        val service = mockk<AiTaskService>()
        val (useCase, _) = buildUseCase(service)

        coEvery { service.suggestSubTasks(any(), any()) } returns Result.Success(
            listOf(
                "1. **Outline** talking points https://phish.example",
                "- book meeting room",
                "Step 3:Host Q4 retro", // exact echo of task title -> dropped
                "  • Collect sprint wins  ",
                "4) Review action items",
                "5. Draft summary doc",
                "6. Share slides with team",
                "7. Schedule follow-up check-in",
                "8. Extra step beyond cap"
            )
        )

        val result = useCase(
            uid = "u1",
            title = "Host Q4 retro",
            description = "Team review",
            existingSubTasks = listOf("Book meeting room")
        )

        assertEquals(AiSource.CLOUD, result.source)
        assertEquals(AiModelTier.FLASH_LITE, result.modelTier)
        assertEquals(6, result.steps.size)
        assertEquals("Outline talking points", result.steps.first())
        assertFalse(result.steps.any { it.equals("Book meeting room", ignoreCase = true) })
        assertFalse(result.steps.any { it.equals("Host Q4 retro", ignoreCase = true) })
        assertFalse(result.steps.any { it.contains("https://") || it.contains("*") })
    }

    @Test
    fun `semantic Jaccard cache hits on reordered words for same uid and isolates across users`() = runTest {
        val service = mockk<AiTaskService>()
        var now = 2_000_000L
        val (useCase, _) = buildUseCase(service, clockMs = { now })

        coEvery { service.suggestSubTasks(any(), any()) } returns Result.Success(
            listOf("Outline slides", "Gather Q4 metrics", "Rehearse pitch")
        )

        val first = useCase(uid = "user-a", title = "Prepare slides for Q4 pitch")
        assertEquals(AiSource.CLOUD, first.source)

        // Reordered & inflected wording ("Q4 pitch slides preparation") -> semantic cache hit!
        now += 30_000L
        val second = useCase(
            uid = "user-a",
            title = "Q4 pitch slides preparation",
            existingSubTasks = listOf("Outline slides")
        )
        assertEquals(AiSource.CACHE, second.source)
        // Cached steps are still filtered against the live existingSubTasks!
        assertEquals(listOf("Gather Q4 metrics", "Rehearse pitch"), second.steps)
        coVerify(exactly = 1) { service.suggestSubTasks(any(), any()) }

        // Different uid must not read user-a's cached breakdown.
        now += 30_000L
        val otherUser = useCase(uid = "user-b", title = "Prepare slides for Q4 pitch")
        assertEquals(AiSource.CLOUD, otherUser.source)
        coVerify(exactly = 2) { service.suggestSubTasks(any(), any()) }
    }

    @Test
    fun `offline or cloud-disabled mode returns deterministic local steps without calling Gemini`() = runTest {
        val service = mockk<AiTaskService>()
        val (useCase, _) = buildUseCase(service, cloudEnabled = false)

        val result = useCase(
            uid = "u1",
            title = "Prepare client presentation deck",
            existingSubTasks = listOf("Run a timed rehearsal")
        )

        assertEquals(AiSource.LOCAL_FALLBACK, result.source)
        assertTrue(result.steps.isNotEmpty())
        assertFalse(result.steps.any { it.equals("Run a timed rehearsal", ignoreCase = true) })
        coVerify(exactly = 0) { service.suggestSubTasks(any(), any()) }
    }
}
