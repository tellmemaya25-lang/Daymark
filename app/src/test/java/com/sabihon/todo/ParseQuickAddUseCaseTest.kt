package com.sabihon.todo

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.QuickAddParser
import com.sabihon.todo.domain.ai.AiTaskDraft
import com.sabihon.todo.domain.ai.AiTaskService
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.usecase.ParseQuickAddUseCase
import com.sabihon.todo.domain.usecase.QuickAddResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trust rules around a cloud answer: what survives sanitising, what the offline
 * fallback is allowed to fill, and what may never reach the editor.
 *
 * Times are not pinned here on purpose - these cases are about fields, not clocks.
 */
class ParseQuickAddUseCaseTest {

    private val service = mockk<AiTaskService>()
    private val useCase = ParseQuickAddUseCase(service, QuickAddParser())

    private val work = Category(id = "c1", name = "Work")

    private fun cloud(draft: AiTaskDraft) {
        coEvery { service.parseQuickAdd(any(), any()) } returns Result.Success(draft)
    }

    @Test
    fun `a reminder without a due date is dropped, not scheduled`() = runTest {
        cloud(AiTaskDraft(title = "Call mom", reminder = true, reminderLeadMinutes = 30))

        val result = useCase("call mom", emptyList())

        assertNull(result.draft.dueAt)
        assertNull(result.draft.reminder)
        assertNull(result.draft.reminderLeadMinutes)
    }

    @Test
    fun `a reminder with a due date keeps its lead time`() = runTest {
        val due = System.currentTimeMillis() + 3_600_000
        cloud(AiTaskDraft(title = "Standup", dueAt = due, reminder = true, reminderLeadMinutes = 15))

        val result = useCase("standup at 9", emptyList())

        assertEquals(true, result.draft.reminder)
        assertEquals(15, result.draft.reminderLeadMinutes)
    }

    @Test
    fun `an absurd lead time is clamped to a week`() = runTest {
        val due = System.currentTimeMillis() + 3_600_000
        cloud(AiTaskDraft(title = "Meeting", dueAt = due, reminder = true, reminderLeadMinutes = 999_999))

        val result = useCase("meeting", emptyList())

        assertEquals(7 * 24 * 60, result.draft.reminderLeadMinutes)
    }

    @Test
    fun `sub-tasks are trimmed, de-duplicated and capped`() = runTest {
        cloud(
            AiTaskDraft(
                title = "Move house",
                subTasks = listOf("Pack", "  pack  ", "", "Book van", "1", "2", "3", "4", "5", "6")
            )
        )

        val result = useCase("move house", emptyList())

        // Eight survive the cap; the blank is dropped and "pack" is de-duplicated.
        assertEquals(
            listOf("Pack", "Book van", "1", "2", "3", "4", "5", "6"),
            result.draft.subTasks
        )
    }

    @Test
    fun `an invented category id is discarded`() = runTest {
        cloud(AiTaskDraft(title = "Deck", categoryId = "not-a-real-list"))

        assertNull(useCase("finish the deck", listOf(work)).draft.categoryId)
    }

    @Test
    fun `a real category id survives`() = runTest {
        cloud(AiTaskDraft(title = "Deck", categoryId = "c1"))

        assertEquals("c1", useCase("finish the deck", listOf(work)).draft.categoryId)
    }

    @Test
    fun `an offline detail the model missed is still filled in`() = runTest {
        // The model returns a title and nothing else; "p1" is plainly in the line.
        cloud(AiTaskDraft(title = "Pay rent"))

        val result = useCase("pay rent p1", emptyList())

        assertEquals(Priority.URGENT, result.draft.priority)
    }

    @Test
    fun `weekly with no weekdays collapses to no repeat`() = runTest {
        cloud(AiTaskDraft(title = "Gym", repeat = RepeatRule.WEEKLY, repeatDays = listOf(9, 0)))

        assertEquals(RepeatRule.NONE, useCase("gym", emptyList()).draft.repeat)
    }

    @Test
    fun `a blank cloud title falls back to the on-device reading`() = runTest {
        cloud(AiTaskDraft(title = ""))

        val result = useCase("call mom friday 5pm", emptyList())

        assertTrue(result.draft.title.isNotBlank())
        assertEquals(QuickAddResult.Source.LOCAL, result.source)
    }
}
