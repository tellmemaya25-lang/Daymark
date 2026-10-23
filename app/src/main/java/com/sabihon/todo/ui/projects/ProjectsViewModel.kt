package com.sabihon.todo.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.repository.CategoryRepository
import com.sabihon.todo.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProjectsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val projects: List<Category> = emptyList(),
    val userInitials: String = "DM",
    val userPhotoUrl: String? = null,
    val error: String? = null,
    val showAddDialog: Boolean = false,
    val editingProject: Category? = null
)

/**
 * Backs the Projects tab – category lists with live open/completed counts.
 */
@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val taskRepository: TaskRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectsUiState())
    val uiState: StateFlow<ProjectsUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null

    init {
        val user = auth.currentUser
        val displayName = user?.displayName ?: user?.email?.substringBefore("@") ?: "User"
        _uiState.update {
            it.copy(
                userInitials = initialsOf(displayName),
                userPhotoUrl = user?.photoUrl?.toString()
            )
        }
        observeProjects()
    }

    private fun initialsOf(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "" + parts.first().first() + parts.last().first()
            parts.size == 1 -> parts.first().take(2)
            else -> "DM"
        }.uppercase()
    }

    /** Pull-to-refresh – re-subscribes to the category/task streams. */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            observeJob?.cancel()
            observeProjects()
            delay(650)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun observeProjects() {
        observeJob = viewModelScope.launch {
            combine(
                categoryRepository.observeCategories(),
                taskRepository.observeTasks(TaskFilter())
            ) { catResult, taskResult -> Pair(catResult, taskResult) }
                .collectLatest { (catResult, taskResult) ->
                    when {
                        catResult is Result.Error -> _uiState.update {
                            it.copy(isLoading = false, error = catResult.message)
                        }
                        catResult is Result.Success && taskResult is Result.Success -> {
                            val tasks = taskResult.data.filter { !it.isDeleted }
                            val withCounts = catResult.data.map { cat ->
                                val catTasks = tasks.filter { it.categoryId == cat.id }
                                cat.copy(
                                    taskCount = catTasks.size,
                                    completedCount = catTasks.count { task -> task.isCompleted }
                                )
                            }.sortedBy { it.order }
                            _uiState.update {
                                it.copy(isLoading = false, projects = withCounts, error = null)
                            }
                        }
                        catResult is Result.Loading -> _uiState.update {
                            if (it.projects.isEmpty()) it.copy(isLoading = true) else it
                        }
                    }
                }
        }
    }

    fun showAddDialog(show: Boolean) {
        _uiState.update { it.copy(showAddDialog = show, editingProject = null) }
    }

    fun editProject(project: Category) {
        _uiState.update { it.copy(editingProject = project, showAddDialog = true) }
    }

    fun addOrUpdateProject(name: String, colorHex: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val editing = _uiState.value.editingProject
            if (editing != null) {
                categoryRepository.updateCategory(editing.copy(name = name.trim(), colorHex = colorHex))
            } else {
                categoryRepository.addCategory(
                    Category(name = name.trim(), colorHex = colorHex, order = _uiState.value.projects.size)
                )
            }
            _uiState.update { it.copy(showAddDialog = false, editingProject = null) }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch { categoryRepository.deleteCategory(projectId) }
    }
}
