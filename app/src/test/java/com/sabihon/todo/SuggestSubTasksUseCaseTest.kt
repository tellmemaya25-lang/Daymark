package com.sabihon.todo

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.LocalSubTaskHeuristics
import com.sabihon.todo.domain.ai.AiTaskService
import com.sabihon.todo.domain.usecase.SubTaskSuggestions
import com.sabihon.todo.domain.usecase.SuggestSubTasksUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that hold whatever answered the question (`docs/ai-task-prioritization-and-
 * suggestions-plan.md` Phase 3).
 *
 * These wire only the service and the offline splitter, so there is no quota guard, no
 * circuit breaker and no clock: the questions here are about cleaning an answer, not
 * about rate limiting, which `AiGuardrailsAndCacheTest` already covers.
 */
class SuggestSubTasksUseCaseTest {

    private val service = mockk<AiTaskService>()
    private val heuristics = LocalSubTaskHeuristics()
    private val useCase = SuggestSubTasksUseCase(service, heuristics)

    private fun cloudReturns(vararg steps: String) {
        coEvery { service.suggestSubTasks(any(), any(), any()) } returns Result.Success(steps.toList())
    }

    private fun cloudFails() {
        coEvery { service.suggestSubTasks(any(), any(), any()) } returns
            Result.Error(IllegalStateException("network down"))
    }

    @Test
    fun `bullet and number prefixes are stripped`() = runTest {
        useCase.clearCache()
        cloudReturns("1. Buy milk", "- charge the camera", "• Pack bags", "Step 2: print tickets")

        val result = useCase("trip prep", null, emptyList())

        assertEquals(
            listOf("Buy milk", "charge the camera", "Pack bags", "print tickets"),
            result.steps
        )
    }

    @Test
    fun `steps the user already has are dropped, case-insensitively`() = runTest {
        useCase.clearCache()
        cloudReturns("Buy milk", "Charge camera")

        val result = useCase("trip prep", null, listOf("buy MILK"))

        assertEquals(listOf("Charge camera"), result.steps)
    }

    @Test
    fun `duplicates inside one answer collapse`() = runTest {
        useCase.clearCache()
        cloudReturns("Pack bags", "pack bags", "PACK BAGS")

        val result = useCase("trip prep", null, emptyList())

        assertEquals(listOf("Pack bags"), result.steps)
    }

    @Test
    fun `no more than six steps survive`() = runTest {
        useCase.clearCache()
        cloudReturns("one", "two", "three", "four", "five", "six", "seven", "eight")

        val result = useCase("big job", null, emptyList())

        assertEquals(6, result.steps.size)
    }

    @Test
    fun `a step longer than a line is cut, not dropped`() = runTest {
        useCase.clearCache()
        cloudReturns("x".repeat(200) + " and then some")

        val result = useCase("long step", null, emptyList())

        assertEquals(80, result.steps.single().length)
    }

    @Test
    fun `a paraphrased title is answered from the cache without a second call`() = runTest {
        useCase.clearCache()
        cloudReturns("Buy milk", "Charge camera")

        useCase("buy milk and camera", null, emptyList())
        // Reordered words: same question, so the answer is reused rather than re-asked.
        val second = useCase("camera buy milk", null, emptyList())

        assertEquals(SubTaskSuggestions.Source.CACHE, second.source)
        coVerify(exactly = 1) { service.suggestSubTasks(any(), any(), any()) }
    }

    @Test
    fun `an unrelated title is a new question`() = runTest {
        useCase.clearCache()
        cloudReturns("Buy milk")

        useCase("buy milk", null, emptyList())
        useCase("call the dentist", null, emptyList())

        coVerify(exactly = 2) { service.suggestSubTasks(any(), any(), any()) }
    }

    @Test
    fun `a cached answer is re-filtered against steps added since`() = runTest {
        useCase.clearCache()
        cloudReturns("Buy milk", "Charge camera")

        useCase("buy milk", null, emptyList())
        val second = useCase("buy milk", null, listOf("Buy milk"))

        assertEquals(listOf("Charge camera"), second.steps)
    }

    @Test
    fun `a failed call falls back to the offline split, labelled`() = runTest {
        useCase.clearCache()
        cloudFails()

        val result = useCase(
            "pack, print the tickets and charge the camera",
            null,
            emptyList()
        )

        assertEquals(SubTaskSuggestions.Source.LOCAL, result.source)
        assertEquals(
            listOf("pack", "print the tickets", "charge the camera"),
            result.steps
        )
        assertNull(result.message)
    }

    @Test
    fun `a failed call with nothing to split says so instead of inventing steps`() = runTest {
        useCase.clearCache()
        cloudFails()

        val result = useCase("call the dentist", null, emptyList())

        assertTrue(result.steps.isEmpty())
        assertEquals(SubTaskSuggestions.Source.LOCAL, result.source)
        assertEquals("AI didn't answer - try again", result.message)
    }

    @Test
    fun `a blank title never reaches the service`() = runTest {
        useCase.clearCache()

        val result = useCase("   ", null, emptyList())

        assertTrue(result.steps.isEmpty())
        assertEquals("Give the task a name first", result.message)
        coVerify(exactly = 0) { service.suggestSubTasks(any(), any(), any()) }
    }

    @Test
    fun `a bare conjunction is not split into fragments`() = runTest {
        // "buy milk and bread" is one errand. Splitting it would offer "bread" as a step,
        // which is a worse answer than no answer at all.
        val steps = heuristics.split("buy milk and bread")

        assertTrue(steps.isEmpty())
    }

    @Test
    fun `a comma list is split`() = runTest {
        val steps = heuristics.split("pack, print the tickets and charge the camera")

        assertEquals(listOf("pack", "print the tickets", "charge the camera"), steps)
    }

    @Test
    fun `the offline splitter respects steps the user already has`() = runTest {
        val steps = heuristics.split(
            title = "pack, print the tickets and charge the camera",
            existingSubTasks = listOf("Pack")
        )

        assertEquals(listOf("print the tickets", "charge the camera"), steps)
    }
}
