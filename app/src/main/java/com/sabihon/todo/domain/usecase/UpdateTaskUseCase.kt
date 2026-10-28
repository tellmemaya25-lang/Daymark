package com.sabihon.todo.domain.usecase

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.repository.TaskRepository
import com.sabihon.todo.domain.security.InputSecurityValidator
import javax.inject.Inject

/**
 * Use case to update existing task.
 *
 * Enforces safe Firestore document IDs on `task.id` and `task.categoryId`,
 * strips control/null bytes, and bounds user text fields.
 */
class UpdateTaskUseCase @Inject constructor(
    private val repository: TaskRepository
) {
    suspend operator fun invoke(task: Task): Result<Unit> {
        if (task.id.isBlank()) return Result.Error(IllegalArgumentException("Task id required"))
        if (!InputSecurityValidator.isValidFirestoreDocId(task.id)) {
            return Result.Error(IllegalArgumentException("Invalid task id"))
        }
        val safeTitle = InputSecurityValidator.sanitizeUserText(
            task.title,
            maxLength = InputSecurityValidator.MAX_TITLE_LENGTH
        )
        if (safeTitle.isBlank()) return Result.Error(IllegalArgumentException("Title cannot be empty"))
        if (task.categoryId != null && !InputSecurityValidator.isValidFirestoreDocId(task.categoryId)) {
            return Result.Error(IllegalArgumentException("Invalid category id"))
        }
        // `Task.description` is non-null; see AddTaskUseCase for why this is not `?.let`.
        val safeDescription = InputSecurityValidator.sanitizeUserText(
            task.description,
            maxLength = InputSecurityValidator.MAX_DESCRIPTION_LENGTH,
            allowMultiline = true
        )
        val safeSubTasks = task.subTasks.take(50).mapNotNull { sub ->
            val subTitle = InputSecurityValidator.sanitizeUserText(
                sub.title,
                maxLength = InputSecurityValidator.MAX_SUBTASK_TITLE_LENGTH
            )
            if (subTitle.isBlank()) null else sub.copy(title = subTitle)
        }
        return repository.updateTask(
            task.copy(
                title = safeTitle,
                description = safeDescription,
                subTasks = safeSubTasks,
                durationMinutes = InputSecurityValidator.clampDurationMinutes(task.durationMinutes)
            )
        )
    }
}
