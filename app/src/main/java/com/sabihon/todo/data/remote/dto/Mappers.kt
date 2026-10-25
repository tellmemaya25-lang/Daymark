package com.sabihon.todo.data.remote.dto

import com.google.firebase.Timestamp
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.SubTask
import com.sabihon.todo.domain.model.Task
import java.util.Date

/**
 * DTO ↔ Domain mappers – no Firebase types leak above data layer.
 */

fun TaskDto.toDomain(): Task {
    return Task(
        id = id,
        title = title,
        description = description,
        categoryId = categoryId,
        priority = Priority.fromString(priority),
        dueAt = dueAt?.toDate()?.time,
        reminderAt = reminderAt?.toDate()?.time,
        isCompleted = isCompleted,
        completedAt = completedAt?.toDate()?.time,
        subTasks = subTasks.map { it.toDomain() },
        createdAt = createdAt?.toDate()?.time ?: System.currentTimeMillis(),
        updatedAt = updatedAt?.toDate()?.time ?: System.currentTimeMillis(),
        isDeleted = isDeleted,
        searchKeywords = searchKeywords,
        repeat = RepeatRule.fromString(repeat),
        repeatDays = repeatDays,
        streak = streak,
        lastCompletedDay = lastCompletedDay,
        seriesId = seriesId
    )
}

fun Task.toDto(): TaskDto {
    return TaskDto(
        id = id,
        title = title,
        description = description,
        categoryId = categoryId,
        priority = priority.name,
        dueAt = dueAt?.let { Timestamp(Date(it)) },
        reminderAt = reminderAt?.let { Timestamp(Date(it)) },
        isCompleted = isCompleted,
        completedAt = completedAt?.let { Timestamp(Date(it)) },
        subTasks = subTasks.map { it.toDto() },
        createdAt = Timestamp(Date(createdAt)),
        updatedAt = Timestamp(Date(updatedAt)),
        isDeleted = isDeleted,
        searchKeywords = searchKeywords.ifEmpty { generateSearchKeywords(title) },
        repeat = repeat.name,
        repeatDays = repeatDays,
        streak = streak,
        lastCompletedDay = lastCompletedDay,
        seriesId = seriesId
    )
}

fun SubTaskDto.toDomain(): SubTask = SubTask(id = id, title = title, isDone = isDone)
fun SubTask.toDto(): SubTaskDto = SubTaskDto(id = id, title = title, isDone = isDone)

fun CategoryDto.toDomain(taskCount: Int = 0, completedCount: Int = 0): Category {
    return Category(
        id = id,
        name = name,
        colorHex = colorHex,
        iconKey = iconKey,
        order = order,
        createdAt = createdAt?.toDate()?.time ?: System.currentTimeMillis(),
        taskCount = taskCount,
        completedCount = completedCount
    )
}

fun Category.toDto(): CategoryDto {
    return CategoryDto(
        id = id,
        name = name,
        colorHex = colorHex,
        iconKey = iconKey,
        order = order,
        createdAt = Timestamp(Date(createdAt))
    )
}

/**
 * Auto-generate searchKeywords – lowercased tokens of title for prefix search.
 */
fun generateSearchKeywords(title: String): List<String> {
    val lower = title.lowercase().trim()
    if (lower.isEmpty()) return emptyList()
    val tokens = lower.split("\\s+".toRegex())
    val keywords = mutableSetOf<String>()
    tokens.forEach { token ->
        if (token.length >= 2) {
            // Add prefixes for prefix search
            for (i in 1..token.length) {
                keywords.add(token.substring(0, i))
            }
        }
    }
    keywords.add(lower) // full lowercased title
    return keywords.toList()
}
