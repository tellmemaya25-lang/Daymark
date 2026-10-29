package com.sabihon.todo

import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.usecase.DueDateConflictPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DueDateConflictPolicyTest {

    private val dueAt = 1_800_000_000_000L

    @Test
    fun `same displayed minute conflicts even if seconds differ`() {
        val task = Task(id = "one", title = "Call", dueAt = dueAt + 35_000L)

        assertEquals(task, DueDateConflictPolicy.findConflict(listOf(task), dueAt))
    }

    @Test
    fun `a different minute does not conflict`() {
        val task = Task(id = "one", title = "Call", dueAt = dueAt + 60_000L)

        assertNull(DueDateConflictPolicy.findConflict(listOf(task), dueAt))
    }

    @Test
    fun `the task being edited is ignored`() {
        val task = Task(id = "one", title = "Call", dueAt = dueAt)

        assertNull(DueDateConflictPolicy.findConflict(listOf(task), dueAt, editingTaskId = "one"))
    }

    @Test
    fun `completed tasks still reserve the time but deleted tasks do not`() {
        val completed = Task(id = "done", title = "Finished", dueAt = dueAt, isCompleted = true)
        val deleted = Task(id = "deleted", title = "Removed", dueAt = dueAt, isDeleted = true)

        assertEquals(completed, DueDateConflictPolicy.findConflict(listOf(completed, deleted), dueAt))
        assertNull(DueDateConflictPolicy.findConflict(listOf(deleted), dueAt))
    }

    @Test
    fun `a null due time cannot conflict`() {
        val task = Task(id = "one", title = "Call", dueAt = dueAt)

        assertNull(DueDateConflictPolicy.findConflict(listOf(task), null))
    }
}
