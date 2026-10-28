package com.sabihon.todo.domain.usecase

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.repository.TaskRepository
import com.sabihon.todo.domain.security.InputSecurityValidator
import javax.inject.Inject

/**
 * Use case to add a new task.
 *
 * Strips control/null bytes, bounds text fields, and validates optional category IDs
 * against Firestore path/identifier injection.
 */
class AddTaskUseCase @Inject constructor(
    private val repository: TaskRepository
) {
    suspend operator fun invoke(task: Task): Result<String> {
        val safeTitle = InputSecurityValidator.sanitizeUserText(
            task.title,
            maxLength = InputSecurityValidator.MAX_TITLE_LENGTH
        )
        if (safeTitle.isBlank()) {
            return Result.Error(IllegalArgumentException("Title cannot be empty"))
        }
        if (task.categoryId != null && !InputSecurityValidator.isValidFirestoreDocId(task.categoryId)) {
            return Result.Error(IllegalArgumentException("Invalid category id"))
        }
        val safeDescription = task.description?.let {
            InputSecurityValidator.sanitizeUserText(
                it,
                maxLength = InputSecurityValidator.MAX_DESCRIPTION_LENGTH,
                allowMultiline = true
            ).ifBlank { null }
        }
        val safeSubTasks = task.subTasks.take(50).mapNotNull { sub ->
            val subTitle = InputSecurityValidator.sanitizeUserText(
                sub.title,
                maxLength = InputSecurityValidator.MAX_SUBTASK_TITLE_LENGTH
            )
            if (subTitle.isBlank()) null else sub.copy(title = subTitle)
        }
        return repository.addTask(
            task.copy(
                title = safeTitle,
                description = safeDescription,
                subTasks = safeSubTasks
            )
        )
    }
}
