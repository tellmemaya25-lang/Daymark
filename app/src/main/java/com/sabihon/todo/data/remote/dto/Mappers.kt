package com.sabihon.todo.data.remote.dto

import com.google.firebase.Timestamp
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.SubTask
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.security.InputSecurityValidator
import java.util.Date
import java.util.Locale

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
        durationMinutes = durationMinutes,
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

private const val MAX_KEYWORD_TOKENS = 15
private const val MAX_PREFIX_LENGTH = 24
private const val MAX_SEARCH_KEYWORDS = 64
private const val MAX_FULL_KEYWORD_LENGTH = 120

fun Task.toDto(): TaskDto {
    val safeTitle = InputSecurityValidator.sanitizeUserText(
        title,
        maxLength = InputSecurityValidator.MAX_TITLE_LENGTH
    )
    // `Task.description` and `TaskDto.description` are both non-null Strings. A safe call
    // here produced a String? that TaskDto(...) rejects, so sanitize it in place: a blank
    // description stays "" (the DTO's own default) instead of becoming null.
    val safeDescription = InputSecurityValidator.sanitizeUserText(
        description,
        maxLength = InputSecurityValidator.MAX_DESCRIPTION_LENGTH,
        allowMultiline = true
    )
    return TaskDto(
        id = id,
        title = safeTitle,
        description = safeDescription,
        categoryId = categoryId,
        priority = priority.name,
        dueAt = dueAt?.let { Timestamp(Date(it)) },
        reminderAt = reminderAt?.let { Timestamp(Date(it)) },
        durationMinutes = InputSecurityValidator.clampDurationMinutes(durationMinutes),
        isCompleted = isCompleted,
        completedAt = completedAt?.let { Timestamp(Date(it)) },
        subTasks = subTasks.take(50).map { it.toDto() },
        createdAt = Timestamp(Date(createdAt)),
        updatedAt = Timestamp(Date(updatedAt)),
        isDeleted = isDeleted,
        searchKeywords = searchKeywords
            .take(MAX_SEARCH_KEYWORDS)
            .ifEmpty { generateSearchKeywords(safeTitle) },
        repeat = repeat.name,
        repeatDays = repeatDays.filter { it in 1..7 }.distinct(),
        streak = streak.coerceAtLeast(0),
        lastCompletedDay = lastCompletedDay,
        seriesId = seriesId
    )
}

fun SubTaskDto.toDomain(): SubTask = SubTask(id = id, title = title, isDone = isDone)
fun SubTask.toDto(): SubTaskDto = SubTaskDto(
    id = id,
    title = InputSecurityValidator.sanitizeUserText(
        title,
        maxLength = InputSecurityValidator.MAX_SUBTASK_TITLE_LENGTH
    ),
    isDone = isDone
)

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
    val safeName = InputSecurityValidator.sanitizeUserText(
        name,
        maxLength = InputSecurityValidator.MAX_CATEGORY_NAME_LENGTH
    )
    val safeColor = if (InputSecurityValidator.isSafeHexColor(colorHex)) colorHex else "#10B981"
    return CategoryDto(
        id = id,
        name = safeName,
        colorHex = safeColor,
        iconKey = InputSecurityValidator.sanitizeFileSegment(iconKey, fallback = "folder"),
        order = order,
        createdAt = Timestamp(Date(createdAt))
    )
}

/**
 * Auto-generate searchKeywords – lowercased tokens of title for prefix search.
 *
 * Hardened against quadratic O(N^2) storage/heap amplification:
 *  - strips control/null bytes and bounds input length
 *  - caps token count ([MAX_KEYWORD_TOKENS]), prefix depth ([MAX_PREFIX_LENGTH]),
 *    and total keyword array size ([MAX_SEARCH_KEYWORDS])
 */
fun generateSearchKeywords(title: String): List<String> {
    val sanitized = InputSecurityValidator.sanitizeUserText(
        title,
        maxLength = InputSecurityValidator.MAX_TITLE_LENGTH
    )
    val lower = sanitized.lowercase(Locale.ROOT).trim()
    if (lower.isEmpty()) return emptyList()
    val tokens = lower.split("\\s+".toRegex()).take(MAX_KEYWORD_TOKENS)
    val keywords = LinkedHashSet<String>()
    for (token in tokens) {
        if (keywords.size >= MAX_SEARCH_KEYWORDS - 1) break
        if (token.length >= 2) {
            val maxPrefix = token.length.coerceAtMost(MAX_PREFIX_LENGTH)
            for (i in 1..maxPrefix) {
                if (keywords.size >= MAX_SEARCH_KEYWORDS - 1) break
                keywords.add(token.substring(0, i))
            }
        }
    }
    keywords.add(lower.take(MAX_FULL_KEYWORD_LENGTH))
    return keywords.toList()
}
