package com.sabihon.todo.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.repository.CategoryRepository
import com.sabihon.todo.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val taskRepository: TaskRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoriesUiState())
    val uiState: StateFlow<CategoriesUiState> = _uiState.asStateFlow()

    init {
        observeCategoriesWithCounts()
    }

    private fun observeCategoriesWithCounts() {
        viewModelScope.launch {
            combine(
                categoryRepository.observeCategories(),
                taskRepository.observeTasks(TaskFilter())
            ) { catResult, taskResult ->
                Pair(catResult, taskResult)
            }.collectLatest { (catResult, taskResult) ->
                if (catResult is Result.Success && taskResult is Result.Success) {
                    val tasks = taskResult.data.filter { !it.isDeleted }
                    val categoriesWithCounts = catResult.data.map { cat ->
                        val catTasks = tasks.filter { it.categoryId == cat.id }
                        cat.copy(
                            taskCount = catTasks.size,
                            completedCount = catTasks.count { it.isCompleted }
                        )
                    }
                    _uiState.update { it.copy(isLoading = false, categories = categoriesWithCounts) }
                } else if (catResult is Result.Loading) {
                    _uiState.update { it.copy(isLoading = true) }
                } else if (catResult is Result.Error) {
                    _uiState.update { it.copy(isLoading = false, error = catResult.message) }
                }
            }
        }
    }

    fun showAddDialog(show: Boolean) {
        _uiState.update { it.copy(showAddDialog = show, editingCategory = null) }
    }

    fun editCategory(category: Category) {
        _uiState.update { it.copy(editingCategory = category, showAddDialog = true) }
    }

    fun addOrUpdateCategory(name: String, colorHex: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val editing = _uiState.value.editingCategory
            if (editing != null) {
                categoryRepository.updateCategory(editing.copy(name = name, colorHex = colorHex))
            } else {
                categoryRepository.addCategory(Category(name = name, colorHex = colorHex, order = _uiState.value.categories.size))
            }
            _uiState.update { it.copy(showAddDialog = false, editingCategory = null) }
        }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch {
            categoryRepository.deleteCategory(categoryId)
        }
    }
}

@HiltViewModel
class CategoryDetailViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val taskRepository: TaskRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(CategoryDetailUiState())
    val uiState: StateFlow<CategoryDetailUiState> = _uiState.asStateFlow()

    /** Ticking a sub-task in the expanded card writes through immediately. */
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
                android.util.Log.w("CategoryDetailVM", "sub-task toggle failed: ${e.message}")
            }
        }
    }

    fun loadCategory(categoryId: String) {
        viewModelScope.launch {
            combine(
                categoryRepository.observeCategories(),
                taskRepository.observeTasks(TaskFilter(categoryId = categoryId))
            ) { catRes, taskRes -> Pair(catRes, taskRes) }
                .collectLatest { (catRes, taskRes) ->
                    if (catRes is Result.Success && taskRes is Result.Success) {
                        val cat = catRes.data.find { it.id == categoryId }
                        _uiState.update { it.copy(isLoading = false, category = cat, tasks = taskRes.data) }
                    }
                }
        }
    }
}
