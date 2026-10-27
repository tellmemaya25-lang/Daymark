package com.sabihon.todo.domain.usecase

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.repository.TaskRepository
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.model.TaskStatusFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Dashboard counts – Today / Scheduled / All / Overdue live from Firestore.
 */
data class DashboardCounts(
    val today: Int = 0,
    val scheduled: Int = 0,
    val all: Int = 0,
    val overdue: Int = 0
)

class GetDashboardCountsUseCase @Inject constructor(
    private val repository: TaskRepository
) {
    operator fun invoke(): Flow<Result<DashboardCounts>> {
        return repository.observeTasks(TaskFilter()).map { result ->
            when (result) {
                is Result.Success -> {
                    val tasks = result.data.filter { !it.isDeleted }
                    val now = System.currentTimeMillis()
                    val startOfDay = getStartOfDay(now)
                    val endOfDay = getEndOfDay(now)

                    // Matches HomeTaskSorter's Today view: undated, due today, and open overdue work.
                    val todayCount = tasks.count {
                        !it.isCompleted && (it.dueAt == null || it.dueAt <= endOfDay)
                    }
                    val scheduledCount = tasks.count { it.dueAt != null && it.dueAt > endOfDay && !it.isCompleted }
                    val allCount = tasks.count { !it.isCompleted }
                    val overdueCount = tasks.count { it.dueAt != null && it.dueAt < now && !it.isCompleted }

                    Result.Success(DashboardCounts(todayCount, scheduledCount, allCount, overdueCount))
                }
                is Result.Error -> result
                is Result.Loading -> result
            }
        }
    }

    private fun getStartOfDay(timeMillis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getEndOfDay(timeMillis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }
}
