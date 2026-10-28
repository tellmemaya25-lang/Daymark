package com.sabihon.todo.data.remote.dto

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

/**
 * Firestore DTO for Task – mirrors users/{uid}/tasks/{taskId}
 * 
 * IMPORTANT: Firestore SDK strips "is" prefix from boolean fields by default,
 * so `isCompleted` is stored as `completed` in Firestore.
 * We use @PropertyName to explicitly map to `completed`/`deleted` to match
 * actual Firestore data (see screenshot where fields are `completed` and `deleted`).
 */
data class TaskDto(
    @DocumentId
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val categoryId: String? = null,
    val priority: String = "MEDIUM",
    val dueAt: Timestamp? = null,
    val reminderAt: Timestamp? = null,

    // Timeline block length. Absent on every document written before this field
    // existed; the default keeps those readable and Firestore omits nulls on write.
    val durationMinutes: Int? = null,

    @get:PropertyName("completed")
    @set:PropertyName("completed")
    @PropertyName("completed")
    var isCompleted: Boolean = false,

    val completedAt: Timestamp? = null,
    val subTasks: List<SubTaskDto> = emptyList(),

    @ServerTimestamp
    val createdAt: Timestamp? = null,

    @ServerTimestamp
    val updatedAt: Timestamp? = null,

    @get:PropertyName("deleted")
    @set:PropertyName("deleted")
    @PropertyName("deleted")
    var isDeleted: Boolean = false,

    val searchKeywords: List<String> = emptyList(),

    // Repeating tasks. Defaults keep documents written before this existed readable:
    // a missing `repeat` reads back as NONE, i.e. an ordinary one-off task.
    val repeat: String = "NONE",
    val repeatDays: List<Int> = emptyList(),
    val streak: Int = 0,
    val lastCompletedDay: Long? = null,
    val seriesId: String? = null
)

data class SubTaskDto(
    val id: String = "",
    val title: String = "",
    @get:PropertyName("done")
    @set:PropertyName("done")
    @PropertyName("done")
    var isDone: Boolean = false
)
