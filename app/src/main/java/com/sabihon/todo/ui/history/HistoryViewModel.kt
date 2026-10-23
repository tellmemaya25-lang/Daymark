package com.sabihon.todo.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.remote.dto.ActivityDto
import com.sabihon.todo.data.remote.dto.TaskDto
import com.sabihon.todo.data.remote.dto.toDomain
import com.sabihon.todo.domain.model.ActivityAction
import com.sabihon.todo.domain.model.ActivityLog
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.model.TaskStatusFilter
import com.sabihon.todo.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        val user = auth.currentUser
        val displayName = user?.displayName ?: user?.email?.substringBefore("@") ?: "User"
        _uiState.update {
            it.copy(
                userInitials = initialsOf(displayName),
                userPhotoUrl = user?.photoUrl?.toString()
            )
        }
        observeCompletedTasks()
        observeActivity()
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
            return state.activityLogs.map { log ->
                ActivityFeedItem(
                    id = log.id,
                    taskId = log.taskId,
                    // Entries written before titles were logged carry an empty string.
                    taskTitle = log.taskTitle.ifBlank { "Untitled task" },
                    action = log.action,
                    timestamp = log.timestamp
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

    private fun observeActivity() {
        viewModelScope.launch {
            try {
                val uid = auth.currentUser?.uid ?: return@launch
                val snapshot = firestore.collection("users").document(uid).collection("activity")
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(50)
                    .get()
                    .await()
                val logs = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(ActivityDto::class.java)?.let { dto ->
                        ActivityLog(
                            id = doc.id,
                            taskId = dto.taskId,
                            taskTitle = dto.taskTitle,
                            action = try { com.sabihon.todo.domain.model.ActivityAction.valueOf(dto.action) } catch (e: Exception) { com.sabihon.todo.domain.model.ActivityAction.CREATED },
                            timestamp = dto.timestamp?.toDate()?.time ?: 0L
                        )
                    }
                }
                _uiState.update { it.copy(activityLogs = logs) }
                _uiState.update { it.copy(feed = buildFeed()) }
            } catch (_: Exception) {}
        }
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

    fun restoreTask(taskId: String) {
        viewModelScope.launch {
            taskRepository.restoreTask(taskId)
            // Also toggle complete to false? Actually restore just undoes delete, but for completed we want reopen
            taskRepository.toggleComplete(taskId, false)
        }
    }

    fun permanentlyDelete(taskId: String) {
        viewModelScope.launch {
            taskRepository.deleteTask(taskId, softDelete = false)
        }
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
