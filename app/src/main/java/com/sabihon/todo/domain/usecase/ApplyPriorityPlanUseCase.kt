package com.sabihon.todo.domain.usecase

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.repository.TaskRepository
import javax.inject.Inject

/**
 * Reversible snapshot captured right before a confirmed priority batch is written,
 * mirroring `QuickAddSnapshot` in `AddEditViewModel`.
 */
data class PrioritizationUndoSnapshot(
    val previousPriorities: Map<String, Priority>
) {
    val count: Int get() = previousPriorities.size
}

/**
 * Applies only the user-confirmed priority adjustments that genuinely change a task's
 * stored [Priority], and hands back a [PrioritizationUndoSnapshot] for one-tap Undo.
 */
class ApplyPriorityPlanUseCase @Inject constructor(
    private val taskRepository: TaskRepository
) {

    suspend operator fun invoke(
        currentTasks: List<Task>,
        selectedPriorities: Map<String, Priority>
    ): Result<PrioritizationUndoSnapshot> {
        val tasksById = currentTasks.associateBy { it.id }

        val effectiveUpdates = LinkedHashMap<String, Priority>()
        val undoMap = LinkedHashMap<String, Priority>()

        for ((taskId, targetPriority) in selectedPriorities) {
            val task = tasksById[taskId] ?: continue
            if (task.isDeleted || task.priority == targetPriority) continue
            effectiveUpdates[taskId] = targetPriority
            undoMap[taskId] = task.priority
        }

        if (effectiveUpdates.isEmpty()) {
            return Result.Success(PrioritizationUndoSnapshot(emptyMap()))
        }

        return when (val writeResult = taskRepository.batchUpdatePriorities(effectiveUpdates)) {
            is Result.Success -> Result.Success(PrioritizationUndoSnapshot(undoMap))
            is Result.Error -> Result.Error(writeResult.exception, writeResult.message)
            Result.Loading -> Result.Error(IllegalStateException("Unexpected loading state"))
        }
    }

    suspend fun undo(snapshot: PrioritizationUndoSnapshot): Result<Unit> {
        if (snapshot.previousPriorities.isEmpty()) return Result.Success(Unit)
        return taskRepository.batchUpdatePriorities(snapshot.previousPriorities)
    }
}
