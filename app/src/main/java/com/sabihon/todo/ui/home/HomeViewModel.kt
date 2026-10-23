package com.sabihon.todo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.usecase.DeleteTaskUseCase
import com.sabihon.todo.domain.usecase.GetDashboardCountsUseCase
import com.sabihon.todo.domain.usecase.ObserveCategoriesUseCase
import com.sabihon.todo.domain.usecase.ObserveTasksUseCase
import com.sabihon.todo.domain.usecase.RestoreTaskUseCase
import com.sabihon.todo.domain.usecase.ToggleCompleteUseCase
import com.sabihon.todo.domain.usecase.UpdateTaskUseCase
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
class HomeViewModel @Inject constructor(
    private val observeTasksUseCase: ObserveTasksUseCase,
    private val getDashboardCountsUseCase: GetDashboardCountsUseCase,
    private val toggleCompleteUseCase: ToggleCompleteUseCase,
    private val deleteTaskUseCase: DeleteTaskUseCase,
    private val restoreTaskUseCase: RestoreTaskUseCase,
    private val updateTaskUseCase: UpdateTaskUseCase,
    private val observeCategoriesUseCase: ObserveCategoriesUseCase,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        val displayName = auth.currentUser?.displayName ?: auth.currentUser?.email?.substringBefore("@") ?: "User"
        _uiState.update { it.copy(greetingName = displayName.split(" ").firstOrNull() ?: displayName) }
        observeDashboardCounts()
        observeAllTasks()
        observeCategoryNames()
    }

    private fun observeDashboardCounts() {
        viewModelScope.launch {
            getDashboardCountsUseCase().collectLatest { result ->
                when (result) {
                    is Result.Success -> _uiState.update { it.copy(counts = result.data) }
                    is Result.Error -> _uiState.update { it.copy(errorMessage = result.message) }
                    else -> {}
                }
            }
        }
    }

    private fun observeCategoryNames() {
        viewModelScope.launch {
            observeCategoriesUseCase().collectLatest { result ->
                if (result is Result.Success) {
                    val names = result.data.associate { it.id to it.name }
                    _uiState.update { it.copy(categoryNames = names) }
                }
            }
        }
    }

    private fun observeAllTasks() {
        viewModelScope.launch {
            observeTasksUseCase(TaskFilter()).collectLatest { result ->
                when (result) {
                    is Result.Success -> {
                        val now = System.currentTimeMillis()
                        val start = getStartOfDay(now)
                        val end = getEndOfDay(now)
                        val allTasks = result.data.filter { !it.isDeleted }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
                        val todayTasks = allTasks.filter { task ->
                            task.dueAt == null || task.dueAt in start..end
                        }
                        val momentum = Momentum(
                            total = todayTasks.size,
                            done = todayTasks.count { it.isCompleted }
                        )
                        _uiState.update { current ->
                            val (visible, completed) = splitTasks(allTasks, current.selectedFilter, current.selectedSort)
                            current.copy(
                                isLoading = false,
                                allTasks = allTasks,
                                todayTasks = todayTasks,
                                visibleTasks = visible,
                                completedTasks = completed,
                                momentum = momentum
                            )
                        }
                    }
                    is Result.Loading -> _uiState.update { it.copy(isLoading = true) }
                    is Result.Error -> _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                }
            }
        }
    }

    /**
     * Applies the segmented tab + sort, then splits active work from the
     * collapsible "Completed" section.
     */
    private fun splitTasks(
        tasks: List<Task>,
        filter: HomeFilter,
        sort: HomeSort
    ): Pair<List<Task>, List<Task>> {
        val now = System.currentTimeMillis()
        val start = getStartOfDay(now)
        val end = getEndOfDay(now)
        val matching = when (filter) {
            HomeFilter.TODAY -> tasks.filter { it.dueAt == null || it.dueAt in start..end }
            HomeFilter.UPCOMING -> tasks.filter { it.dueAt != null && it.dueAt > end }
            HomeFilter.ALL -> tasks
        }
        val sorted = when (sort) {
            HomeSort.DEFAULT -> matching.sortedBy { it.dueAt ?: Long.MAX_VALUE }
            HomeSort.PRIORITY -> matching.sortedWith(
                compareByDescending<Task> { it.priority.ordinal }.thenBy { it.dueAt ?: Long.MAX_VALUE }
            )
            HomeSort.DUE_DATE -> matching.sortedBy { it.dueAt ?: Long.MAX_VALUE }
        }
        val visible = sorted.filter { !it.isCompleted }
        val completed = sorted.filter { it.isCompleted }
            .sortedByDescending { it.completedAt ?: it.updatedAt }
        return visible to completed
    }

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.ToggleComplete -> toggleComplete(action.taskId, action.isCompleted)
            is HomeAction.DeleteTask -> deleteTask(action.task)
            is HomeAction.UndoDelete -> undoDelete()
            is HomeAction.AddSubTask -> addSubTask(action.taskId, action.subTaskTitle)
            is HomeAction.ToggleSubTask -> toggleSubTask(action.taskId, action.subTaskId, action.isDone)
            is HomeAction.SetFilter -> {
                _uiState.update { current ->
                    val (visible, completed) = splitTasks(current.allTasks, action.filter, current.selectedSort)
                    current.copy(
                        selectedFilter = action.filter,
                        visibleTasks = visible,
                        completedTasks = completed
                    )
                }
            }
            is HomeAction.SetSort -> {
                _uiState.update { current ->
                    val (visible, completed) = splitTasks(current.allTasks, current.selectedFilter, action.sort)
                    current.copy(
                        selectedSort = action.sort,
                        visibleTasks = visible,
                        completedTasks = completed
                    )
                }
            }
        }
    }

    private fun toggleComplete(taskId: String, isCompleted: Boolean) {
        viewModelScope.launch { toggleCompleteUseCase(taskId, isCompleted) }
    }

    private fun deleteTask(task: Task) {
        viewModelScope.launch {
            _uiState.update { it.copy(lastDeletedTask = task, showUndo = true) }
            deleteTaskUseCase(task.id, softDelete = true)
        }
    }

    private fun undoDelete() {
        viewModelScope.launch {
            _uiState.value.lastDeletedTask?.let { restoreTaskUseCase(it.id) }
            _uiState.update { it.copy(showUndo = false, lastDeletedTask = null) }
        }
    }

    fun dismissUndo() {
        _uiState.update { it.copy(showUndo = false, lastDeletedTask = null) }
    }

    private fun addSubTask(taskId: String, title: String) {
        viewModelScope.launch {
            val task = _uiState.value.allTasks.find { it.id == taskId } ?: return@launch
            val newSub = com.sabihon.todo.domain.model.SubTask(
                id = System.currentTimeMillis().toString(),
                title = title,
                isDone = false
            )
            val updated = task.copy(subTasks = task.subTasks + newSub)
            updateTaskUseCase(updated)
        }
    }

    private fun toggleSubTask(taskId: String, subTaskId: String, isDone: Boolean) {
        viewModelScope.launch {
            val task = _uiState.value.allTasks.find { it.id == taskId } ?: return@launch
            val updatedSubs = task.subTasks.map { if (it.id == subTaskId) it.copy(isDone = isDone) else it }
            // Dont mark as complete when all task are not done – auto-complete only if all sub-tasks done
            val allDone = updatedSubs.isNotEmpty() && updatedSubs.all { it.isDone }
            val shouldBeCompleted = if (updatedSubs.isEmpty()) task.isCompleted else allDone
            val updated = task.copy(subTasks = updatedSubs, isCompleted = shouldBeCompleted)
            updateTaskUseCase(updated)
        }
    }

    private fun getStartOfDay(timeMillis: Long): Long {
        val cal = Calendar.getInstance().apply {
            this.timeInMillis = timeMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun getEndOfDay(timeMillis: Long): Long {
        val cal = Calendar.getInstance().apply {
            this.timeInMillis = timeMillis
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }
        return cal.timeInMillis
    }
}
