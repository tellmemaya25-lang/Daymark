package com.sabihon.todo

import com.sabihon.todo.domain.model.SubTask
import com.sabihon.todo.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Task.withSubTaskChecked` - the one place that decides what ticking a sub-task does
 * to the task's own completion flag.
 *
 * This matters twice over: `HomeViewModel` writes its result, and Home reads the same
 * result to decide whether the completion splash plays. If the two ever disagreed, the
 * splash would be celebrating a completion that was never saved - which is the bug this
 * whole path exists to prevent.
 */
class SubTaskCompletionTest {

    private fun task(vararg subTasks: SubTask, completed: Boolean = false) = Task(
        id = "t",
        title = "Workshop prep",
        subTasks = subTasks.toList(),
        isCompleted = completed
    )

    @Test
    fun `ticking the last sub-task completes the task`() {
        val half = task(SubTask("a", "slides", isDone = true), SubTask("b", "room set-up"))

        val done = half.withSubTaskChecked("b", true)

        assertTrue(done.subTasksDone)
        assertTrue(done.isCompleted)
    }

    @Test
    fun `ticking one of several leaves the task open`() {
        val untouched = task(SubTask("a", "slides"), SubTask("b", "room set-up"))

        val partly = untouched.withSubTaskChecked("a", true)

        assertTrue(!partly.isCompleted)
        assertTrue(!partly.subTasksDone)
    }

    @Test
    fun `unticking a sub-task reopens a completed task`() {
        val done = task(
            SubTask("a", "slides", isDone = true),
            SubTask("b", "room set-up", isDone = true),
            completed = true
        )

        val reopened = done.withSubTaskChecked("a", false)

        assertTrue(!reopened.isCompleted)
        assertTrue(!reopened.subTasksDone)
    }

    @Test
    fun `only the touched sub-task changes`() {
        val original = task(SubTask("a", "slides"), SubTask("b", "room set-up"))

        val updated = original.withSubTaskChecked("b", true)

        assertEquals(SubTask("a", "slides", isDone = false), updated.subTasks[0])
        assertEquals(SubTask("b", "room set-up", isDone = true), updated.subTasks[1])
        assertEquals(original.id, updated.id)
        assertEquals(original.title, updated.title)
    }

    @Test
    fun `an unknown sub-task id leaves the task alone`() {
        // A stale row can outlive a sub-task deleted on another device.
        val done = task(
            SubTask("a", "slides", isDone = true),
            completed = true
        )

        assertEquals(done, done.withSubTaskChecked("ghost", true))
    }

    @Test
    fun `a task with no sub-tasks keeps its own flag`() {
        // Nothing to tick, so nothing to infer - the parent's checkbox stays in charge.
        assertTrue(!task().withSubTaskChecked("ghost", true).isCompleted)
    }
}
