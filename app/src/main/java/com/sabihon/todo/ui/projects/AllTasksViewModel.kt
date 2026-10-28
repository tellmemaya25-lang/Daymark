package com.sabihon.todo.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.repository.CategoryRepository
import com.sabihon.todo.domain.repository.TaskRepository
import com.sabihon.todo.domain.usecase.DeleteTaskUseCase
import com.sabihon.todo.domain.usecase.RestoreTaskUseCase
import com.sabihon.todo.domain.usecase.ToggleCompleteUseCase
import com.sabihon.todo.ui.home.HomeFilter
import com.sabihon.todo.ui.home.HomeSort
import com.sabihon.todo.ui.home.HomeSortDirection
import com.sabihon.todo.ui.home.HomeTaskSorter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class AllTasksUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val openTasks: List<Task> = emptyList(),
    val completedTasks: List<Task> = emptyList(),
    val categoryNames: Map<String, String> = emptyMap(),
    val sort: HomeSort = HomeSort.DUE_DATE,
    val sortDirection: HomeSortDirection = HomeSortDirection.ASCENDING,
    val snackbarMessage: String? = null,
    val deletedTaskIdForUndo: String? = null
) {
    val totalCount: Int get() = openTasks.size + completedTasks.size
}

/**
 * Backs `Projects > All tasks` — a flat, neutral list of every created task across
 * all projects without colored category cards.
 */
@HiltViewModel
class AllTasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val toggleCompleteUseCase: ToggleCompleteUseCase,
    private val deleteTaskUseCase: DeleteTaskUseCase,
    private val restoreTaskUseCase: RestoreTaskUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AllTasksUiState())
    val uiState: StateFlow<AllTasksUiState> = _uiState.asStateFlow()

    private var rawTasks: List<Task> = emptyList()
    private var observeJob: Job? = null

    init {
        observeAllTasks()
    }

    private fun observeAllTasks() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(
                taskRepository.observeTasks(TaskFilter()),
                categoryRepository.observeCategories()
            ) { taskRes, catRes -> Pair(taskRes, catRes) }
                .collectLatest { (taskRes, catRes) ->
                    when (taskRes) {
                        is Result.Loading -> {
                            if (rawTasks.isEmpty()) {
                                _uiState.update { it.copy(isLoading = true) }
                            }
                        }
                        is Result.Error -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    snackbarMessage = taskRes.message ?: "Couldn't load tasks"
                                )
                            }
                        }
                        is Result.Success -> {
                            rawTasks = taskRes.data.filter { !it.isDeleted }
                            val catMap = if (catRes is Result.Success) {
                                catRes.data.associate { it.id to it.name }
                            } else {
                                _uiState.value.categoryNames
                            }
                            recomputeList(categoryNames = catMap)
                        }
                    }
                }
        }
    }

    private fun recomputeList(
        sort: HomeSort = _uiState.value.sort,
        direction: HomeSortDirection = _uiState.value.sortDirection,
        categoryNames: Map<String, String> = _uiState.value.categoryNames
    ) {
        val (open, completed) = HomeTaskSorter.split(
            tasks = rawTasks,
            filter = HomeFilter.ALL,
            sort = sort,
            direction = direction,
            selectedDate = LocalDate.now()
        )
        _uiState.update {
            it.copy(
                isLoading = false,
                openTasks = open,
                completedTasks = completed,
                categoryNames = categoryNames,
                sort = sort,
                sortDirection = direction
            )
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            observeAllTasks()
            delay(550)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun setSort(sort: HomeSort) {
        recomputeList(sort = sort)
    }

    fun setSortDirection(direction: HomeSortDirection) {
        recomputeList(direction = direction)
    }

    fun toggleComplete(taskId: String, checked: Boolean) {
        viewModelScope.launch {
            when (val res = toggleCompleteUseCase(taskId, checked)) {
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            snackbarMessage = res.message ?: "Complete all sub-tasks first",
                            deletedTaskIdForUndo = null
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    fun toggleSubTask(task: Task, subTaskId: String, done: Boolean) {
        viewModelScope.launch {
            taskRepository.updateTask(task.withSubTaskChecked(subTaskId, done))
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            val res = deleteTaskUseCase(task.id, softDelete = true)
            if (res is Result.Success) {
                _uiState.update {
                    it.copy(
                        snackbarMessage = "Deleted \"${task.title.take(32)}\"",
                        deletedTaskIdForUndo = task.id
                    )
                }
            }
        }
    }

    fun undoDelete(taskId: String) {
        viewModelScope.launch {
            restoreTaskUseCase(taskId)
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null, deletedTaskIdForUndo = null) }
    }
}
