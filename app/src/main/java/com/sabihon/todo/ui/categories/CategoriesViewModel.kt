package com.sabihon.todo.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.sabihon.todo.domain.usecase.SuggestNextTasksUseCase
import com.google.firebase.auth.FirebaseAuth
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
    private val taskRepository: TaskRepository,
    private val suggestNextTasksUseCase: SuggestNextTasksUseCase,
    private val addTaskUseCase: AddTaskUseCase,
    private val aiPreferences: AiPreferenceStore,
    private val telemetry: AiTelemetry,
    private val auth: FirebaseAuth
) : ViewModel() {
    private val _uiState = MutableStateFlow(CategoryDetailUiState())
    val uiState: StateFlow<CategoryDetailUiState> = _uiState.asStateFlow()

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
        _uiState.update { it.copy(isSuggesting = true) }

        viewModelScope.launch {
            try {
                val state = _uiState.value
                val result = suggestNextTasksUseCase(
                    openTasks = state.tasks.filter { !it.isCompleted },
                    recentCompleted = state.tasks.filter { it.isCompleted },
                    categories = listOfNotNull(state.category),
                    uid = auth.currentUser?.uid
                )
                // A candidate for another project is dropped rather than re-homed: the
                // card lives inside one project and must not suggest work elsewhere.
                val scoped = result.candidates.filter {
                    it.categoryId == null || it.categoryId == state.category?.id
                }
                _uiState.update { it.copy(isSuggesting = false, suggestions = scoped) }
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
                state.copy(suggestions = state.suggestions.filterNot {
                    it.suggestionId == suggestion.suggestionId
                })
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
                state.copy(suggestions = state.suggestions.filterNot {
                    it.suggestionId == suggestion.suggestionId
                })
            }
        }
    }

    fun toggleSuggestionsExpanded() {
        _uiState.update { it.copy(suggestionsExpanded = !it.suggestionsExpanded) }
    }

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
                        loadSuggestions()
                    }
                }
        }
    }
}
