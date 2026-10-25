package com.sabihon.todo

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.SubTask
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.repository.TaskRepository
import com.sabihon.todo.domain.usecase.ToggleCompleteUseCase
import io.mockk.capture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ToggleCompleteUseCaseTest {
    private val repo = mockk<TaskRepository>(relaxed = true)
    private val useCase = ToggleCompleteUseCase(repo)

    private fun task(
        id: String = "1",
        repeat: RepeatRule = RepeatRule.NONE,
        streak: Int = 0,
        lastCompletedDay: Long? = null,
        dueAt: Long? = null,
        subTasks: List<SubTask> = emptyList(),
        repeatDays: List<Int> = emptyList(),
        seriesId: String? = null
    ) = Task(
        id = id,
        title = "Meditate",
        repeat = repeat,
        repeatDays = repeatDays,
        streak = streak,
        lastCompletedDay = lastCompletedDay,
        dueAt = dueAt,
        subTasks = subTasks,
        seriesId = seriesId
    )

    /** Emits the way the real repository does: Loading first, then the document. */
    private fun emitting(vararg emissions: Result<Task>) {
        coEvery { repo.observeTaskById(any()) } returns flowOf(*emissions)
    }

    @Test
    fun `toggle complete success`() = runTest {
        emitting(Result.Success(task()))
        coEvery { repo.toggleComplete("1", true) } returns Result.Success(Unit)
        val result = useCase("1", true)
        assertTrue(result is Result.Success)
    }

    @Test
    fun `toggle complete error`() = runTest {
        coEvery { repo.toggleComplete("1", false) } returns Result.Error(Exception("fail"))
        val result = useCase("1", false)
        assertTrue(result is Result.Error)
    }

    @Test
    fun `a task with unfinished sub-tasks cannot be completed`() = runTest {
        emitting(
            Result.Success(
                task(subTasks = listOf(SubTask("a", "one", isDone = true), SubTask("b", "two")))
            )
        )
        val result = useCase("1", true)
        assertTrue(result is Result.Error)
        coVerify(exactly = 0) { repo.toggleComplete(any(), any()) }
    }

    @Test
    fun `the loading emission does not bypass the sub-task guard`() = runTest {
        // Regression: taking .first() used to read Result.Loading and allow anything.
        emitting(
            Result.Loading,
            Result.Success(task(subTasks = listOf(SubTask("a", "one", isDone = false))))
        )
        assertTrue(useCase("1", true) is Result.Error)
    }

    @Test
    fun `a completed one-off task does not spawn anything`() = runTest {
        emitting(Result.Success(task(repeat = RepeatRule.NONE)))
        coEvery { repo.toggleComplete("1", true) } returns Result.Success(Unit)
        useCase("1", true)
        coVerify(exactly = 0) { repo.spawnOccurrence(any()) }
        coVerify(exactly = 0) { repo.updateStreak(any(), any(), any()) }
    }

    @Test
    fun `a completed daily habit starts a streak and spawns tomorrow`() = runTest {
        val today = LocalDate.now()
        emitting(Result.Success(task(id = "habit", repeat = RepeatRule.DAILY)))
        coEvery { repo.toggleComplete("habit", true) } returns Result.Success(Unit)
        coEvery { repo.updateStreak(any(), any(), any()) } returns Result.Success(Unit)
        coEvery { repo.spawnOccurrence(any()) } returns Result.Success("next")

        useCase("habit", true)

        coVerify(exactly = 1) { repo.updateStreak("habit", 1, today.toEpochDay()) }
        val spawned = mutableListOf<Task>()
        coVerify(exactly = 1) { repo.spawnOccurrence(capture(spawned)) }

        val next = spawned.single()
        assertEquals("habit_${today.plusDays(1).toEpochDay()}", next.id)
        assertEquals("habit", next.seriesId)
        assertEquals(1, next.streak)
        assertEquals(today.toEpochDay(), next.lastCompletedDay)
        assertEquals(RepeatRule.DAILY, next.repeat)
        assertTrue(!next.isCompleted)
        assertNotNull(next.dueAt)
    }

    @Test
    fun `completing a habit reports where the next occurrence landed`() = runTest {
        val today = LocalDate.now()
        emitting(Result.Success(task(id = "habit", repeat = RepeatRule.DAILY)))
        coEvery { repo.toggleComplete("habit", true) } returns Result.Success(Unit)
        coEvery { repo.updateStreak(any(), any(), any()) } returns Result.Success(Unit)
        coEvery { repo.spawnOccurrence(any()) } returns Result.Success("next")

        val result = useCase("habit", true)

        val advance = (result as Result.Success).data
        assertEquals(1, advance.streak)
        assertNotNull(advance.nextDueAt)
        // Tomorrow, not today: the confirmation message is built from this.
        assertEquals(
            today.plusDays(1).toEpochDay(),
            java.time.Instant.ofEpochMilli(advance.nextDueAt!!)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
        )
    }

    @Test
    fun `a one-off task reports no next occurrence`() = runTest {
        emitting(Result.Success(task()))
        coEvery { repo.toggleComplete("1", true) } returns Result.Success(Unit)

        val advance = (useCase("1", true) as Result.Success).data

        org.junit.Assert.assertNull(advance.nextDueAt)
    }

    @Test
    fun `reopening a habit does not advance the series`() = runTest {
        coEvery { repo.toggleComplete("habit", false) } returns Result.Success(Unit)
        useCase("habit", false)
        coVerify(exactly = 0) { repo.spawnOccurrence(any()) }
        coVerify(exactly = 0) { repo.updateStreak(any(), any(), any()) }
    }

    @Test
    fun `a streak failure does not fail the completion`() = runTest {
        emitting(Result.Success(task(id = "habit", repeat = RepeatRule.DAILY)))
        coEvery { repo.toggleComplete("habit", true) } returns Result.Success(Unit)
        coEvery { repo.updateStreak(any(), any(), any()) } throws IllegalStateException("offline")

        assertTrue(useCase("habit", true) is Result.Success)
    }

    @Test
    fun `spawned sub-tasks start unchecked`() = runTest {
        emitting(
            Result.Success(
                task(
                    id = "habit",
                    repeat = RepeatRule.DAILY,
                    subTasks = listOf(SubTask("a", "warm up", isDone = true))
                )
            )
        )
        coEvery { repo.toggleComplete("habit", true) } returns Result.Success(Unit)
        coEvery { repo.updateStreak(any(), any(), any()) } returns Result.Success(Unit)
        coEvery { repo.spawnOccurrence(any()) } returns Result.Success("next")

        useCase("habit", true)

        val spawned = mutableListOf<Task>()
        coVerify { repo.spawnOccurrence(capture(spawned)) }
        assertTrue(spawned.single().subTasks.all { !it.isDone })
    }
}
