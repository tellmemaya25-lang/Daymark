package com.sabihon.todo.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.SortBy
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.model.TaskStatusFilter
import com.sabihon.todo.domain.repository.CategoryRepository
import com.sabihon.todo.domain.repository.TaskRepository
import com.sabihon.todo.domain.usecase.ObserveTasksUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val observeTasksUseCase: ObserveTasksUseCase,
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        observeCategories()
        observeDebouncedSearch()
        observeFilteredTasks()
    }

    private fun observeCategories() {
        viewModelScope.launch {
            categoryRepository.observeCategories().collectLatest { result ->
                if (result is Result.Success) {
                    _uiState.update { it.copy(categories = result.data) }
                }
            }
        }
    }

    private fun observeDebouncedSearch() {
        viewModelScope.launch {
            queryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { query ->
                    _uiState.update { it.copy(query = query) }
                    // Update filter with search query
                    _uiState.update { current ->
                        current.copy(filter = current.filter.copy(searchQuery = query.ifBlank { null }))
                    }
                }
        }
    }

    private fun observeFilteredTasks() {
        viewModelScope.launch {
            // Observe tasks whenever filter changes – we collect uiState filter
            _uiState.collectLatest { state ->
                observeTasksUseCase(state.filter).collectLatest { result ->
                    when (result) {
                        is Result.Success -> _uiState.update { it.copy(tasks = result.data, isLoading = false) }
                        is Result.Loading -> _uiState.update { it.copy(isLoading = true) }
                        is Result.Error -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                    }
                }
            }
        }
    }

    fun onQueryChange(query: String) {
        queryFlow.value = query
        _uiState.update { it.copy(query = query) }
    }

    fun onStatusFilterChange(status: TaskStatusFilter) {
        _uiState.update { it.copy(filter = it.filter.copy(status = status)) }
    }

    fun onPriorityFilterChange(priority: Priority?) {
        _uiState.update { it.copy(filter = it.filter.copy(priority = priority)) }
    }

    fun onCategoryFilterChange(categoryId: String?) {
        _uiState.update { it.copy(filter = it.filter.copy(categoryId = categoryId)) }
    }

    fun onSortChange(sortBy: SortBy, ascending: Boolean) {
        _uiState.update { it.copy(filter = it.filter.copy(sortBy = sortBy, sortAscending = ascending)) }
    }

    fun clearFilters() {
        _uiState.update { it.copy(filter = TaskFilter(), query = "") }
        queryFlow.value = ""
    }

    /** Ticking a sub-task in an expanded search result writes through immediately. */
    fun toggleSubTask(taskId: String, subTaskId: String, isDone: Boolean) {
        viewModelScope.launch {
            try {
                val stored = taskRepository.observeTaskById(taskId).first()
                if (stored is Result.Success) {
                    val task = stored.data
                    val updated = task.subTasks.map {
                        if (it.id == subTaskId) it.copy(isDone = isDone) else it
                    }
                    taskRepository.updateTask(task.copy(subTasks = updated))
                }
            } catch (e: Exception) {
                android.util.Log.w("SearchVM", "sub-task toggle failed: ${e.message}")
            }
        }
    }

    fun saveRecentSearch(query: String) {
        if (query.isBlank()) return
        val current = _uiState.value.recentSearches.toMutableList()
        current.remove(query)
        current.add(0, query)
        if (current.size > 10) current.removeAt(current.lastIndex)
        _uiState.update { it.copy(recentSearches = current) }
    }
}
