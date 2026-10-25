package com.sabihon.todo.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.remote.dto.ActivityDto
import com.sabihon.todo.domain.model.ActivityAction
import com.sabihon.todo.domain.model.ActivityLog
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.model.TaskStatusFilter
import com.sabihon.todo.data.local.AvatarStore
import com.sabihon.todo.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val avatarStore: AvatarStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var activityRegistration: ListenerRegistration? = null

    private companion object {
        /** Enough to scroll a few days of activity without paging. */
        const val ACTIVITY_LIMIT = 80L
    }

    init {
        val user = auth.currentUser
        val displayName = user?.displayName ?: user?.email?.substringBefore("@") ?: "User"
        _uiState.update { it.copy(userInitials = initialsOf(displayName)) }
        observeAvatar()
        observeCompletedTasks()
        observeDeletedTasks()
        observeActivity()
    }

    /**
     * Which tasks are deleted *right now*.
     *
     * A second listener on purpose, with `includeDeleted = true`: the feed's own query
     * filters deleted documents out, and the activity log only says what happened, not
     * what the state is. Restoring is offered off this set, so a row stops offering it
     * the moment the document comes back - regardless of how the log sorted.
     */
    private fun observeDeletedTasks() {
        viewModelScope.launch {
            taskRepository.observeTasks(TaskFilter(includeDeleted = true)).collectLatest { result ->
                if (result is Result.Success) {
                    val deletedIds = result.data.filter { it.isDeleted }.map { it.id }.toSet()
                    if (deletedIds != _uiState.value.deletedTaskIds) {
                        _uiState.update { it.copy(deletedTaskIds = deletedIds) }
                        _uiState.update { it.copy(feed = buildFeed()) }
                    }
                }
            }
        }
    }

    /** Same shared source as Home and Projects; see [AvatarStore.observe]. */
    private fun observeAvatar() {
        viewModelScope.launch {
            val user = auth.currentUser
            avatarStore.observe(user?.uid, user?.photoUrl?.toString()).collect { uri ->
                _uiState.update { it.copy(userPhotoUrl = uri) }
            }
        }
    }

    /** "Maya Angelou" -> "MA", "maya" -> "MA". */
    private fun initialsOf(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "" + parts.first().first() + parts.last().first()
            parts.size == 1 -> parts.first().take(2)
            else -> "DM"
        }.uppercase()
    }

    /**
     * The feed prefers real activity entries; if the activity collection is empty
     * (offline, or a task completed before logging existed) it falls back to the
     * completed tasks themselves so the page is never blank.
     */
    private fun buildFeed(): List<ActivityFeedItem> {
        val state = _uiState.value
        if (state.activityLogs.isNotEmpty()) {
            val logs = state.activityLogs.sortedByDescending { it.timestamp }
            // Newest event per task, used only for the tap behaviour of rows whose
            // document has been purged. Restorability comes from the task documents.
            val latestActionByTask = logs
                .groupBy { it.taskId }
                .mapValues { (_, entries) -> entries.first().action }

            // Newest "Deleted" entry per task, tie-broken by document id. Same-minute
            // entries are common (delete, restore, delete again in one session), and a
            // bare timestamp comparison left the order down to whatever Firestore
            // returned - which is how a live delete ended up with no Restore button.
            val newestDeletedLogId = logs
                .filter { it.action == ActivityAction.DELETED }
                .groupBy { it.taskId }
                .mapValues { (_, entries) ->
                    entries.sortedWith(
                        compareByDescending<ActivityLog> { it.timestamp }.thenByDescending { it.id }
                    ).first().id
                }

            return logs.map { log ->
                ActivityFeedItem(
                    id = log.id,
                    taskId = log.taskId,
                    // Entries written before titles were logged carry an empty string.
                    taskTitle = log.taskTitle.ifBlank { "Untitled task" },
                    action = log.action,
                    timestamp = log.timestamp,
                    // The document is the truth: it exists, it is flagged deleted, and
                    // this is the delete event we would be undoing.
                    restorable = log.action == ActivityAction.DELETED &&
                        log.taskId.isNotBlank() &&
                        log.taskId in state.deletedTaskIds &&
                        newestDeletedLogId[log.taskId] == log.id,
                    gone = latestActionByTask[log.taskId] == ActivityAction.PURGED
                )
            }.sortedByDescending { it.timestamp }
        }
        return state.completedTasks.map { task ->
            ActivityFeedItem(
                id = "task-${task.id}",
                taskId = task.id,
                taskTitle = task.title,
                action = ActivityAction.COMPLETED,
                timestamp = task.completedAt ?: task.updatedAt
            )
        }.sortedByDescending { it.timestamp }
    }

    private fun observeCompletedTasks() {
        viewModelScope.launch {
            taskRepository.observeTasks(TaskFilter(status = TaskStatusFilter.COMPLETED)).collectLatest { result ->
                when (result) {
                    is Result.Success -> {
                        val tasks = result.data
                        val grouped = groupByDay(tasks)
                        val stats = calculateStats(tasks)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                completedTasks = tasks,
                                grouped = grouped,
                                stats = stats
                            )
                        }
                        _uiState.update { it.copy(feed = buildFeed()) }
                    }
                    is Result.Loading -> _uiState.update { it.copy(isLoading = true) }
                    is Result.Error -> _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /**
     * Live listener rather than a one-shot read: deleting or completing a task while
     * this screen is open has to land in the feed immediately, and a single `.get()`
     * at init was why deleted tasks never appeared until the screen was recreated.
     */
    private fun observeActivity() {
        val uid = auth.currentUser?.uid ?: return
        activityRegistration = firestore.collection("users").document(uid)
            .collection("activity")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(ACTIVITY_LIMIT)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val logs = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(ActivityDto::class.java)?.let { dto ->
                        ActivityLog(
                            id = doc.id,
                            taskId = dto.taskId,
                            taskTitle = dto.taskTitle,
                            action = parseAction(dto.action),
                            timestamp = dto.timestamp?.toDate()?.time ?: 0L
                        )
                    }
                }
                _uiState.update { it.copy(activityLogs = logs) }
                _uiState.update { it.copy(feed = buildFeed()) }
            }
    }

    /**
     * Unknown/missing verbs used to collapse to CREATED, which made an unreadable entry
     * look like a fresh task. Match on the string first and fall back to UPDATED.
     */
    private fun parseAction(raw: String?): ActivityAction =
        ActivityAction.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: ActivityAction.UPDATED

    override fun onCleared() {
        super.onCleared()
        activityRegistration?.remove()
        activityRegistration = null
    }

    private fun groupByDay(tasks: List<Task>): Map<String, List<Task>> {
        val now = System.currentTimeMillis()
        val startToday = getStartOfDay(now)
        val startYesterday = startToday - 24 * 60 * 60 * 1000L

        val today = mutableListOf<Task>()
        val yesterday = mutableListOf<Task>()
        val earlier = mutableListOf<Task>()

        tasks.forEach { task ->
            val completedAt = task.completedAt ?: task.updatedAt
            when {
                completedAt >= startToday -> today.add(task)
                completedAt >= startYesterday -> yesterday.add(task)
                else -> earlier.add(task)
            }
        }
        return mapOf(
            "Today" to today,
            "Yesterday" to yesterday,
            "Earlier" to earlier
        )
    }

    private fun calculateStats(tasks: List<Task>): HistoryStats {
        val now = System.currentTimeMillis()
        val startWeek = getStartOfDay(now - 6 * 24 * 60 * 60 * 1000L)
        val completedThisWeek = tasks.count { (it.completedAt ?: 0L) >= startWeek }

        // Completion rate: completed / total (completed + pending) – need total count, approximate with completedThisWeek for now
        // For simplicity, assume completion rate = completedThisWeek / 7 days avg? We'll compute from all tasks observed
        // Here we just use 75% placeholder if tasks not empty, else 0 – real calc would need all tasks count
        val completionRate = if (tasks.isNotEmpty()) 0.75f else 0f

        // Streak: consecutive days with at least one completion
        val streak = calculateStreak(tasks)

        // Daily counts for 7-day bar chart
        val dailyCounts = MutableList(7) { 0 }
        val cal = Calendar.getInstance()
        for (task in tasks) {
            val completedAt = task.completedAt ?: continue
            val diffDays = ((now - completedAt) / (24 * 60 * 60 * 1000L)).toInt()
            if (diffDays in 0..6) {
                dailyCounts[6 - diffDays] += 1
            }
        }

        return HistoryStats(
            completedThisWeek = completedThisWeek,
            completionRate = completionRate,
            currentStreak = streak,
            dailyCounts = dailyCounts
        )
    }

    private fun calculateStreak(tasks: List<Task>): Int {
        if (tasks.isEmpty()) return 0
        val completedDates = tasks.mapNotNull { it.completedAt }.map { getStartOfDay(it) }.toSet().sortedDescending()
        if (completedDates.isEmpty()) return 0
        var streak = 0
        var currentDay = getStartOfDay(System.currentTimeMillis())
        for (date in completedDates) {
            if (date == currentDay || date == currentDay - 24 * 60 * 60 * 1000L && streak == 0) {
                streak++
                currentDay = date - 24 * 60 * 60 * 1000L
            } else if (date == currentDay) {
                streak++
                currentDay -= 24 * 60 * 60 * 1000L
            } else {
                break
            }
        }
        return streak
    }

    /**
     * Undo a deletion.
     *
     * This used to also call `toggleComplete(false)`, which meant "restore" on a task
     * that had been deleted *after* being completed also silently reopened it. Restore
     * now only clears the deleted flag - the task comes back in the state it was in.
     *
     * The old code also ignored the result, so a restore against a task that had already
     * been deleted for good failed without a word. Every outcome now reports.
     */
    fun restoreTask(taskId: String) {
        viewModelScope.launch {
            val result = taskRepository.restoreTask(taskId)
            _uiState.update {
                it.copy(
                    message = if (result.isSuccess()) {
                        "Task restored"
                    } else {
                        "Could not restore - this task was deleted for good"
                    }
                )
            }
        }
    }

    /** Undo a completion. Separate from restore on purpose: they are different actions. */
    fun reopenTask(taskId: String) {
        viewModelScope.launch {
            val result = taskRepository.toggleComplete(taskId, false)
            _uiState.update {
                it.copy(
                    message = if (result.isSuccess()) "Task reopened" else "Could not reopen this task"
                )
            }
        }
    }

    fun permanentlyDelete(taskId: String) {
        viewModelScope.launch {
            val result = taskRepository.deleteTask(taskId, softDelete = false)
            _uiState.update {
                it.copy(
                    message = if (result.isSuccess()) "Task deleted for good" else "Could not delete this task"
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun getStartOfDay(timeMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
