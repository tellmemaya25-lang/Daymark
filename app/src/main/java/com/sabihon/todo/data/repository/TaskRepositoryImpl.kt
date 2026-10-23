package com.sabihon.todo.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.remote.dto.ActivityDto
import com.sabihon.todo.data.remote.dto.TaskDto
import com.sabihon.todo.data.remote.dto.generateSearchKeywords
import com.sabihon.todo.data.remote.dto.toDomain
import com.sabihon.todo.data.remote.dto.toDto
import com.sabihon.todo.di.IoDispatcher
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.model.TaskStatusFilter
import com.sabihon.todo.domain.repository.TaskRepository
import com.google.firebase.Timestamp
import com.sabihon.todo.notifications.ReminderScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore implementation – fixed field names and proper callbackFlow usage.
 * No nested listeners, awaitClose always last.
 */
@Singleton
class TaskRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val reminderScheduler: ReminderScheduler,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : TaskRepository {

    private fun userIdOrThrow(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User not authenticated – please login again")

    private fun tasksCollection() =
        firestore.collection("users").document(userIdOrThrow()).collection("tasks")

    private fun activityCollection() =
        firestore.collection("users").document(userIdOrThrow()).collection("activity")

    /**
     * Outcome of a Firestore write.
     *
     * [Queued] is the important one: with offline persistence enabled a write is
     * committed to the local cache immediately, but the Task returned by
     * `set()`/`add()` only completes once the *server* acknowledges it. Offline, in
     * a flaky network, or behind a slow proxy that ack never arrives, so awaiting it
     * hangs forever and the Save button spins indefinitely. We therefore only wait a
     * bounded time and treat "no answer yet" as queued – the SDK will flush it.
     */
    private sealed interface WriteOutcome {
        data object Acked : WriteOutcome
        data object Queued : WriteOutcome
        data class Failed(val error: Exception) : WriteOutcome
    }

    private suspend fun writeDocument(docRef: DocumentReference, data: Any): WriteOutcome {
        return try {
            val acked = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) {
                docRef.set(data).await()
                true
            }
            if (acked == true) WriteOutcome.Acked else WriteOutcome.Queued
        } catch (e: Exception) {
            WriteOutcome.Failed(e)
        }
    }

    private suspend fun boundedWrite(block: suspend () -> Unit): WriteOutcome {
        return try {
            val acked = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) {
                block()
                true
            }
            if (acked == true) WriteOutcome.Acked else WriteOutcome.Queued
        } catch (e: Exception) {
            WriteOutcome.Failed(e)
        }
    }

    /** Flattened fallback document used when POJO serialisation is rejected. */
    private fun minimalTaskMap(task: Task): Map<String, Any?> = hashMapOf(
        "title" to task.title,
        "description" to task.description,
        "categoryId" to task.categoryId,
        "priority" to task.priority.name,
        "dueAt" to task.dueAt?.let { Timestamp(Date(it)) },
        "reminderAt" to task.reminderAt?.let { Timestamp(Date(it)) },
        "completed" to task.isCompleted,
        "deleted" to task.isDeleted,
        "searchKeywords" to generateSearchKeywords(task.title),
        "createdAt" to Timestamp(Date()),
        "updatedAt" to Timestamp(Date()),
        "subTasks" to task.subTasks.map {
            mapOf("id" to it.id, "title" to it.title, "done" to it.isDone)
        }
    )

    private companion object {
        /** How long we wait for a backend ack before assuming the write is queued. */
        const val WRITE_ACK_TIMEOUT_MS = 8_000L
        /** Activity logging is best-effort and must never block a save. */
        const val ACTIVITY_TIMEOUT_MS = 3_000L
    }

    override fun observeTasks(filter: TaskFilter): Flow<Result<List<Task>>> = callbackFlow {
        trySend(Result.Loading)
        var listener: ListenerRegistration? = null
        try {
            val uid = auth.currentUser?.uid
            if (uid == null) {
                trySend(Result.Error(IllegalStateException("Not authenticated"), "Not authenticated"))
            } else {
                android.util.Log.d("TaskRepo", "observeTasks uid=$uid filter=$filter")
                val baseQuery: Query = if (!filter.includeDeleted) {
                    tasksCollection().whereEqualTo("deleted", false)
                } else {
                    tasksCollection()
                }

                // IMPORTANT: Do NOT use orderBy here – it requires composite index (deleted + dueAt)
                // Sorting is done in-memory in applyInMemoryFilters to avoid FAILED_PRECONDITION
                // If you want server-side ordering, create index via link in logcat or firestore.indexes.json
                val queryForListener: Query = baseQuery

                listener = queryForListener.addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        android.util.Log.e("TaskRepo", "Snapshot error: ${error.message}", error)
                        val msg = error.message ?: ""
                        val friendly = when {
                            msg.contains("PERMISSION_DENIED", true) -> "Firestore permission denied. Deploy firestore.rules"
                            msg.contains("index", true) || msg.contains("FAILED_PRECONDITION", true) ->
                                "Missing index – please create index or check console link in logcat"
                            else -> msg
                        }
                        trySend(Result.Error(error, friendly))
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        android.util.Log.d("TaskRepo", "Snapshot ${snapshot.size()} docs")
                        val tasks = snapshot.documents.mapNotNull { doc ->
                            try {
                                doc.toObject(TaskDto::class.java)?.toDomain()
                            } catch (e: Exception) {
                                android.util.Log.e("TaskRepo", "Parse ${doc.id} failed", e)
                                null
                            }
                        }.let { applyInMemoryFilters(it, filter) }
                        trySend(Result.Success(tasks))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TaskRepo", "observeTasks setup failed", e)
            trySend(Result.Error(e, e.message ?: "Failed to observe"))
        }
        awaitClose {
            try {
                listener?.remove()
            } catch (e: Exception) {
                android.util.Log.w("TaskRepo", "Failed to remove listener", e)
            }
        }
    }

    private fun applyInMemoryFilters(tasks: List<Task>, filter: TaskFilter): List<Task> {
        var result = tasks
        if (!filter.includeDeleted) result = result.filter { !it.isDeleted }
        when (filter.status) {
            TaskStatusFilter.PENDING -> result = result.filter { !it.isCompleted }
            TaskStatusFilter.COMPLETED -> result = result.filter { it.isCompleted }
            else -> {}
        }
        filter.priority?.let { pri -> result = result.filter { it.priority == pri } }
        filter.categoryId?.let { catId -> result = result.filter { it.categoryId == catId } }
        filter.dueDateRange?.let { range ->
            val now = System.currentTimeMillis()
            val start = getStartOfDay(now)
            val end = getEndOfDay(now)
            result = when (range) {
                com.sabihon.todo.domain.model.DueDateRange.TODAY -> result.filter { it.dueAt != null && it.dueAt in start..end }
                com.sabihon.todo.domain.model.DueDateRange.OVERDUE -> result.filter { it.dueAt != null && it.dueAt < now && !it.isCompleted }
                com.sabihon.todo.domain.model.DueDateRange.THIS_WEEK -> result.filter { it.dueAt != null && it.dueAt in start..(start + 7*24*60*60*1000L) }
                else -> result
            }
        }
        filter.searchQuery?.let { q ->
            if (q.isNotBlank()) {
                val lower = q.lowercase()
                result = result.filter { it.title.lowercase().contains(lower) || it.description.lowercase().contains(lower) }
            }
        }
        result = when (filter.sortBy) {
            com.sabihon.todo.domain.model.SortBy.PRIORITY -> result.sortedBy { it.priority.ordinal }
            com.sabihon.todo.domain.model.SortBy.CREATED_AT -> result.sortedBy { it.createdAt }
            com.sabihon.todo.domain.model.SortBy.TITLE -> result.sortedBy { it.title.lowercase() }
            else -> result.sortedBy { it.dueAt ?: Long.MAX_VALUE }
        }
        if (!filter.sortAscending) result = result.reversed()
        return result
    }

    override fun observeTaskById(taskId: String): Flow<Result<Task>> = callbackFlow {
        trySend(Result.Loading)
        var listener: ListenerRegistration? = null
        try {
            val docRef = tasksCollection().document(taskId)
            listener = docRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(Result.Error(error, error.message))
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    try {
                        val dto = snapshot.toObject(TaskDto::class.java)
                        if (dto != null) trySend(Result.Success(dto.toDomain()))
                        else trySend(Result.Error(Exception("Task not found")))
                    } catch (e: Exception) {
                        trySend(Result.Error(e))
                    }
                } else {
                    trySend(Result.Error(Exception("Task not found")))
                }
            }
        } catch (e: Exception) {
            trySend(Result.Error(e))
        }
        awaitClose { 
            try { listener?.remove() } catch (_: Exception) {}
        }
    }

    override suspend fun addTask(task: Task): Result<String> = withContext(ioDispatcher) {
        try {
            val uid = userIdOrThrow()
            android.util.Log.d("TaskRepo", "addTask uid=$uid title=${task.title}")
            val dto = task.copy(
                searchKeywords = if (task.searchKeywords.isEmpty()) generateSearchKeywords(task.title) else task.searchKeywords,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            ).toDto()
            val docRef = if (task.id.isBlank()) tasksCollection().document() else tasksCollection().document(task.id)
            val finalDto = dto.copy(id = docRef.id)

            var outcome = writeDocument(docRef, finalDto)
            if (outcome is WriteOutcome.Failed) {
                android.util.Log.e("TaskRepo", "Full DTO write failed, retrying with minimal map", outcome.error)
                outcome = writeDocument(docRef, minimalTaskMap(task))
            }

            when (outcome) {
                is WriteOutcome.Failed -> {
                    android.util.Log.e("TaskRepo", "addTask FAILED", outcome.error)
                    Result.Error(outcome.error, outcome.error.message ?: "Failed to save task")
                }
                WriteOutcome.Queued -> {
                    android.util.Log.w("TaskRepo", "addTask queued locally (no ack yet) – will sync when online")
                    afterTaskSaved(docRef.id, task)
                    Result.Success(docRef.id)
                }
                WriteOutcome.Acked -> {
                    afterTaskSaved(docRef.id, task)
                    Result.Success(docRef.id)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TaskRepo", "addTask FAILED", e)
            Result.Error(e, e.message ?: "Failed")
        }
    }

    /** Best-effort side effects – each is bounded so it can never hang the save. */
    private suspend fun afterTaskSaved(taskId: String, task: Task) {
        try {
            logActivity(taskId = taskId, taskTitle = task.title, action = "CREATED")
        } catch (_: Exception) {
        }
        try {
            task.reminderAt?.let {
                reminderScheduler.scheduleReminder(taskId, task.title, task.description, it)
            }
        } catch (_: Exception) {
        }
    }

    override suspend fun updateTask(task: Task): Result<Unit> = withContext(ioDispatcher) {
        try {
            val dto = task.copy(updatedAt = System.currentTimeMillis(),
                searchKeywords = if (task.searchKeywords.isEmpty()) generateSearchKeywords(task.title) else task.searchKeywords).toDto()
            when (val outcome = writeDocument(tasksCollection().document(task.id), dto)) {
                is WriteOutcome.Failed -> return@withContext Result.Error(
                    outcome.error,
                    outcome.error.message ?: "Failed to update task"
                )
                WriteOutcome.Queued -> android.util.Log.w("TaskRepo", "update queued locally – will sync")
                WriteOutcome.Acked -> Unit
            }
            logActivity(taskId = task.id, taskTitle = task.title, action = "UPDATED")
            try {
                if (task.reminderAt != null) reminderScheduler.scheduleReminder(task.id, task.title, task.description, task.reminderAt)
                else reminderScheduler.cancelReminder(task.id)
            } catch (_: Exception) {}
            Result.Success(Unit)
        } catch (e: Exception) { Result.Error(e, e.message) }
    }

    override suspend fun deleteTask(taskId: String, softDelete: Boolean): Result<Unit> = withContext(ioDispatcher) {
        try {
            // Grab the title before the document goes away – the feed shows it.
            val title = readTaskTitle(taskId)
            val outcome = boundedWrite {
                if (softDelete) {
                    tasksCollection().document(taskId)
                        .update(mapOf("deleted" to true, "updatedAt" to Timestamp(Date()))).await()
                } else {
                    tasksCollection().document(taskId).delete().await()
                }
            }
            if (outcome is WriteOutcome.Failed) return@withContext Result.Error(outcome.error)
            logActivity(taskId = taskId, taskTitle = title, action = "DELETED")
            try { reminderScheduler.cancelReminder(taskId) } catch (_: Exception) {}
            Result.Success(Unit)
        } catch (e: Exception) { Result.Error(e) }
    }

    override suspend fun restoreTask(taskId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val outcome = boundedWrite {
                tasksCollection().document(taskId)
                    .update(mapOf("deleted" to false, "updatedAt" to Timestamp(Date()))).await()
            }
            if (outcome is WriteOutcome.Failed) Result.Error(outcome.error) else Result.Success(Unit)
        } catch (e: Exception) { Result.Error(e) }
    }

    override suspend fun toggleComplete(taskId: String, isCompleted: Boolean): Result<Unit> = withContext(ioDispatcher) {
        try {
            val title = readTaskTitle(taskId)
            val updates = mutableMapOf<String, Any?>("completed" to isCompleted, "updatedAt" to Timestamp(Date()))
            if (isCompleted) updates["completedAt"] = Timestamp(Date()) else updates["completedAt"] = null
            val outcome = boundedWrite {
                tasksCollection().document(taskId).update(updates).await()
            }
            if (outcome is WriteOutcome.Failed) return@withContext Result.Error(outcome.error)
            logActivity(taskId = taskId, taskTitle = title, action = if (isCompleted) "COMPLETED" else "REOPENED")
            if (isCompleted) try { reminderScheduler.cancelReminder(taskId) } catch (_: Exception) {}
            Result.Success(Unit)
        } catch (e: Exception) { Result.Error(e) }
    }

    override suspend fun searchTasks(query: String): Flow<Result<List<Task>>> = flow {
        emit(Result.Loading)
        try {
            if (query.isBlank()) { emit(Result.Success(emptyList())); return@flow }
            val lower = query.lowercase()
            // Avoid composite index: only filter deleted in Firestore, search filtering in-memory
            val snapshot = tasksCollection().whereEqualTo("deleted", false).get().await()
            val tasks = snapshot.documents.mapNotNull {
                try { it.toObject(TaskDto::class.java)?.toDomain() } catch (_: Exception) { null }
            }.filter { it.title.lowercase().contains(lower) || it.description.lowercase().contains(lower) }
            emit(Result.Success(tasks))
        } catch (e: Exception) { emit(Result.Error(e)) }
    }

    /**
     * Best-effort title lookup for log entries that only carry a task id.
     * Bounded and swallowing failures – a missing title must never fail a write.
     */
    private suspend fun readTaskTitle(taskId: String): String = try {
        withTimeoutOrNull(ACTIVITY_TIMEOUT_MS) {
            tasksCollection().document(taskId).get().await()
                .getString("title")
                ?.trim()
                .orEmpty()
        }.orEmpty()
    } catch (_: Exception) {
        ""
    }

    private suspend fun logActivity(taskId: String, taskTitle: String, action: String) {
        try {
            withTimeoutOrNull(ACTIVITY_TIMEOUT_MS) {
                val dto = ActivityDto(
                    taskId = taskId,
                    taskTitle = taskTitle,
                    action = action,
                    timestamp = Timestamp(Date())
                )
                activityCollection().add(dto).await()
            }
        } catch (_: Exception) {
            // Logging is decorative – never surface or block on it.
        }
    }

    private fun getStartOfDay(t: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = t; set(java.util.Calendar.HOUR_OF_DAY,0); set(java.util.Calendar.MINUTE,0)
            set(java.util.Calendar.SECOND,0); set(java.util.Calendar.MILLISECOND,0)
        }
        return cal.timeInMillis
    }
    private fun getEndOfDay(t: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = t; set(java.util.Calendar.HOUR_OF_DAY,23); set(java.util.Calendar.MINUTE,59)
            set(java.util.Calendar.SECOND,59); set(java.util.Calendar.MILLISECOND,999)
        }
        return cal.timeInMillis
    }
}
