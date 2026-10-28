package com.sabihon.todo.domain.usecase

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.repository.TaskRepository
import com.sabihon.todo.domain.security.InputSecurityValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * Search tasks via prefix search on searchKeywords with sanitized, bounded query input.
 */
class SearchTasksUseCase @Inject constructor(
    private val repository: TaskRepository
) {
    suspend operator fun invoke(query: String): Flow<Result<List<Task>>> {
        val sanitized = InputSecurityValidator.sanitizeSearchQuery(query)
        if (sanitized.isBlank()) return flowOf(Result.Success(emptyList()))
        return repository.searchTasks(sanitized)
    }
}
