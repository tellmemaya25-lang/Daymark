package com.sabihon.todo.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.telemetry.AiTelemetry
import com.sabihon.todo.core.telemetry.FirebaseAiTelemetry
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.ai.SuggestedTaskCandidate
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.repository.CategoryRepository
import com.sabihon.todo.domain.repository.TaskRepository
import com.sabihon.todo.domain.usecase.AddTaskUseCase
import com.sabihon.todo.domain.usecase.DeleteTaskUseCase
import com.sabihon.todo.domain.usecase.RestoreTaskUseCase
import com.sabihon.todo.domain.usecase.SuggestNextTasksUseCase
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
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
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
                categoryRepository.addCategory(
                    Category(
                        name = name,
                        colorHex = colorHex,
                        order = _uiState.value.categories.size
                    )
                )
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
    private val taskRepository: TaskRepository,
    private val toggleCompleteUseCase: ToggleCompleteUseCase,
    private val deleteTaskUseCase: DeleteTaskUseCase,
    private val restoreTaskUseCase: RestoreTaskUseCase,
    private val suggestNextTasksUseCase: SuggestNextTasksUseCase,
    private val addTaskUseCase: AddTaskUseCase,
    private val aiPreferences: AiPreferenceStore,
    private val telemetry: AiTelemetry,
    private val auth: FirebaseAuth
) : ViewModel() {
    private val _uiState = MutableStateFlow(CategoryDetailUiState())
    val uiState: StateFlow<CategoryDetailUiState> = _uiState.asStateFlow()

    private var activeCategoryId: String? = null
    private var rawTasks: List<Task> = emptyList()
    private var observeJob: Job? = null
    private var suggestionsRequested = false

    /**
     * Suggestions for this project only, asked for once per visit.
     *
     * Scoped to the project on purpose: inside a list, "what next?" means what next
     * *here*, and a suggestion belonging to another list would be an invitation to
     * leave the screen the user just opened.
     */
    fun loadSuggestions(force: Boolean = false) {
        if (_uiState.value.isSuggesting) return
        if (suggestionsRequested && !force) return
        suggestionsRequested = true
        _uiState.update {
            it.copy(
                isSuggesting = true,
                suggestionsExpanded = if (force) true else it.suggestionsExpanded
            )
        }

        viewModelScope.launch {
            try {
                val state = _uiState.value
                val result = suggestNextTasksUseCase(
                    openTasks = state.tasks.filter { !it.isCompleted },
                    recentCompleted = state.tasks.filter { it.isCompleted },
                    categories = listOfNotNull(state.category),
                    uid = auth.currentUser?.uid,
                    forceInteractive = force
                )
                // A candidate for another project is dropped rather than re-homed: the
                // card lives inside one project and must not suggest work elsewhere.
                val scoped = result.candidates.filter {
                    it.categoryId == null || it.categoryId == state.category?.id
                }
                _uiState.update {
                    it.copy(
                        isSuggesting = false,
                        suggestions = scoped,
                        suggestionsExpanded = if (force) true else it.suggestionsExpanded
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSuggesting = false) }
            }
        }
    }

    fun acceptSuggestion(suggestion: SuggestedTaskCandidate) {
        val categoryId = _uiState.value.category?.id
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                addTaskUseCase(
                    Task(
                        title = suggestion.title,
                        categoryId = suggestion.categoryId ?: categoryId,
                        priority = suggestion.priority,
                        dueAt = suggestion.dueOffsetHours?.let { hours ->
                            now + hours.toLong() * 60L * 60L * 1000L
                        }
                    )
                )
                telemetry.logOutcome(
                    FirebaseAiTelemetry.FEATURE_SUGGEST_NEXT,
                    FirebaseAiTelemetry.ACTION_ACCEPTED
                )
            } catch (e: Exception) {
                android.util.Log.w("CategoryDetailVM", "suggestion add failed: ${e.message}")
            }
            _uiState.update { state ->
                state.copy(
                    suggestions = state.suggestions.filterNot {
                        it.suggestionId == suggestion.suggestionId
                    }
                )
            }
        }
    }

    fun dismissSuggestion(suggestion: SuggestedTaskCandidate) {
        viewModelScope.launch {
            try {
                aiPreferences.dismissSuggestion(suggestion.suggestionId)
                telemetry.logOutcome(
                    FirebaseAiTelemetry.FEATURE_SUGGEST_NEXT,
                    FirebaseAiTelemetry.ACTION_DISMISSED
                )
            } catch (e: Exception) {
                android.util.Log.w("CategoryDetailVM", "suggestion dismiss failed: ${e.message}")
            }
            _uiState.update { state ->
                state.copy(
                    suggestions = state.suggestions.filterNot {
                        it.suggestionId == suggestion.suggestionId
                    }
                )
            }
        }
    }

    fun toggleSuggestionsExpanded() {
        _uiState.update { it.copy(suggestionsExpanded = !it.suggestionsExpanded) }
    }

    private fun recomputeSortedLists(
        category: Category? = _uiState.value.category,
        sort: HomeSort = _uiState.value.sort,
        direction: HomeSortDirection = _uiState.value.sortDirection
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
                category = category,
                tasks = rawTasks,
                openTasks = open,
                completedTasks = completed,
                sort = sort,
                sortDirection = direction
            )
        }
    }

    fun setSort(sort: HomeSort) {
        recomputeSortedLists(sort = sort)
    }

    fun setSortDirection(direction: HomeSortDirection) {
        recomputeSortedLists(direction = direction)
    }

    fun toggleComplete(taskId: String, isCompleted: Boolean) {
        viewModelScope.launch {
            when (val res = toggleCompleteUseCase(taskId, isCompleted)) {
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            snackbarMessage = res.message ?: "Complete all sub-tasks first",
                            deletedTaskIdForUndo = null
                        )
                    }
                }
                is Result.Success -> {
                    val nextDue = res.data.nextDueAt
                    if (nextDue != null) {
                        val formatted = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
                            .format(Date(nextDue))
                        _uiState.update {
                            it.copy(
                                snackbarMessage = "Next: $formatted",
                                deletedTaskIdForUndo = null
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * Ticking a sub-task updates the in-memory task immediately via [Task.withSubTaskChecked],
     * so finishing the last sub-task marks the parent task completed as well.
     */
    fun toggleSubTask(taskId: String, subTaskId: String, isDone: Boolean) {
        viewModelScope.launch {
            try {
                val task = rawTasks.find { it.id == taskId }
                    ?: _uiState.value.tasks.find { it.id == taskId }
                    ?: return@launch
                taskRepository.updateTask(task.withSubTaskChecked(subTaskId, isDone))
            } catch (e: Exception) {
                android.util.Log.w("CategoryDetailVM", "sub-task toggle failed: ${e.message}")
            }
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

    fun refresh() {
        val catId = activeCategoryId ?: return
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            loadCategory(catId)
            delay(500)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun loadCategory(categoryId: String) {
        activeCategoryId = categoryId
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(
                categoryRepository.observeCategories(),
                taskRepository.observeTasks(TaskFilter(categoryId = categoryId))
            ) { catRes, taskRes -> Pair(catRes, taskRes) }
                .collectLatest { (catRes, taskRes) ->
                    if (catRes is Result.Success && taskRes is Result.Success) {
                        val cat = catRes.data.find { it.id == categoryId }
                        rawTasks = taskRes.data.filter { !it.isDeleted }
                        recomputeSortedLists(category = cat)
                        loadSuggestions()
                    } else if (taskRes is Result.Error) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                snackbarMessage = taskRes.message ?: "Couldn't load tasks"
                            )
                        }
                    }
                }
        }
    }
}
