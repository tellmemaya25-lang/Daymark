package com.sabihon.todo

import com.google.firebase.Timestamp
import com.sabihon.todo.data.remote.dto.CategoryDto
import com.sabihon.todo.data.remote.dto.SubTaskDto
import com.sabihon.todo.data.remote.dto.TaskDto
import com.sabihon.todo.data.remote.dto.generateSearchKeywords
import com.sabihon.todo.data.remote.dto.toDomain
import com.sabihon.todo.data.remote.dto.toDto
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.SubTask
import com.sabihon.todo.domain.model.Task
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class MappersTest {

    @Test
    fun `task dto to domain maps correctly`() {
        val dto = TaskDto(
            id = "123",
            title = "Test Task",
            description = "Desc",
            categoryId = "cat1",
            priority = "HIGH",
            dueAt = Timestamp(Date(1000L)),
            isCompleted = false,
            subTasks = listOf(SubTaskDto("s1", "Sub", false)),
            searchKeywords = listOf("test")
        )
        val domain = dto.toDomain()
        assertEquals("123", domain.id)
        assertEquals("Test Task", domain.title)
        assertEquals(Priority.HIGH, domain.priority)
        assertEquals(1, domain.subTasks.size)
        assertEquals("cat1", domain.categoryId)
    }

    @Test
    fun `task domain to dto generates search keywords`() {
        val task = Task(
            id = "123",
            title = "Buy Milk",
            priority = Priority.LOW
        )
        val dto = task.toDto()
        assertTrue(dto.searchKeywords.isNotEmpty())
        assertTrue(dto.searchKeywords.contains("buy"))
        assertEquals("LOW", dto.priority)
    }

    @Test
    fun `generateSearchKeywords creates prefixes`() {
        val keywords = generateSearchKeywords("Buy Milk")
        assertTrue(keywords.contains("buy"))
        assertTrue(keywords.contains("b"))
        assertTrue(keywords.contains("bu"))
        assertTrue(keywords.contains("milk"))
        assertTrue(keywords.contains("m"))
    }

    @Test
    fun `category dto to domain`() {
        val dto = CategoryDto(id = "c1", name = "Grocery", colorHex = "#D6E4FF", order = 0)
        val domain = dto.toDomain(taskCount = 5, completedCount = 2)
        assertEquals("c1", domain.id)
        assertEquals("Grocery", domain.name)
        assertEquals(5, domain.taskCount)
        assertEquals(2, domain.completedCount)
    }

    @Test
    fun `subtask mapping`() {
        val dto = SubTaskDto("id1", "Title", true)
        val domain = dto.toDomain()
        assertEquals("id1", domain.id)
        assertTrue(domain.isDone)
        val backToDto = domain.toDto()
        assertEquals(dto, backToDto)
    }

    /* ------------------------- timeline block length ------------------------- */

    @Test
    fun `duration round-trips through dto and back`() {
        val dto = TaskDto(id = "t1", title = "Answer Emails", durationMinutes = 30)

        val domain = dto.toDomain()
        assertEquals(30, domain.durationMinutes)

        val back = domain.toDto()
        assertEquals(30, back.durationMinutes)
    }

    /**
     * "No duration given" must survive as null in both directions. Coercing it to a
     * number anywhere in the pipeline would make an unscheduled task look scheduled.
     */
    @Test
    fun `absent duration stays null in both directions`() {
        val dto = TaskDto(id = "t2", title = "Wind Down")
        assertNull(dto.toDomain().durationMinutes)

        val task = Task(id = "t3", title = "Rise and Shine")
        assertNull(task.toDto().durationMinutes)
    }

    /** Documents written before this field existed carry no key at all. */
    @Test
    fun `legacy dto without the field decodes as null`() {
        val legacy = TaskDto(id = "old", title = "Imported", dueAt = Timestamp(Date(1000L)))
        assertNull(legacy.durationMinutes)
        assertNull(legacy.toDomain().durationMinutes)
    }

    @Test
    fun `out-of-range duration is clamped on write`() {
        assertEquals(5, Task(title = "a", durationMinutes = 0).toDto().durationMinutes)
        assertEquals(5, Task(title = "b", durationMinutes = -90).toDto().durationMinutes)
        assertEquals(720, Task(title = "c", durationMinutes = 10_000).toDto().durationMinutes)
        assertEquals(720, Task(title = "d", durationMinutes = Int.MAX_VALUE).toDto().durationMinutes)
    }

    @Test
    fun `in-range duration is written unchanged`() {
        val boundaries = listOf(5, 15, 30, 45, 60, 90, 720)
        for (minutes in boundaries) {
            assertEquals(
                "duration $minutes should not be altered",
                minutes,
                Task(title = "t", durationMinutes = minutes).toDto().durationMinutes
            )
        }
    }
}
