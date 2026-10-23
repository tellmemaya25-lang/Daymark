package com.sabihon.todo.ui.addedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.SubTask
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.repository.CategoryRepository
import com.sabihon.todo.domain.repository.TaskRepository
import com.sabihon.todo.domain.usecase.AddTaskUseCase
import com.sabihon.todo.domain.usecase.DeleteTaskUseCase
import com.sabihon.todo.domain.usecase.UpdateTaskUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import javax.inject.Inject

/**
 * ViewModel for Add/Edit Task – with debug info and robust error handling for Firestore.
 */
@HiltViewModel
class AddEditViewModel @Inject constructor(
    private val addTaskUseCase: AddTaskUseCase,
    private val updateTaskUseCase: UpdateTaskUseCase,
    private val deleteTaskUseCase: DeleteTaskUseCase,
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    private companion object {
        /** Hard ceiling for one save round-trip; keeps the Save button honest. */
        const val SAVE_TIMEOUT_MS = 20_000L
    }

    private val _uiState = MutableStateFlow(AddEditUiState())
    val uiState: StateFlow<AddEditUiState> = _uiState.asStateFlow()

    /**
     * The form is seeded from Firestore exactly once. Later emissions (including the
     * echo of our own sub-task writes) must not overwrite what the user is typing.
     */
    private var seededTaskId: String? = null

    init {
        observeCategories()
    }

    private fun observeCategories() {
        viewModelScope.launch {
            try {
                categoryRepository.observeCategories().collectLatest { result ->
                    if (result is Result.Success) {
                        _uiState.update { it.copy(categories = result.data) }
                    } else if (result is Result.Error) {
                        _uiState.update { it.copy(errorMessage = "Categories load failed: ${result.message}") }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to load categories: ${e.message}") }
                android.util.Log.e("AddEditVM", "observeCategories error", e)
            }
        }
    }

    fun loadTask(taskId: String?) {
        if (taskId == null) {
            _uiState.update { it.copy(isEditMode = false) }
            return
        }
        _uiState.update { it.copy(isLoading = true, taskId = taskId, isEditMode = true) }
        viewModelScope.launch {
            try {
                taskRepository.observeTaskById(taskId).collectLatest { result ->
                    when (result) {
                        is Result.Success -> {
                            val task = result.data
                            if (seededTaskId != task.id) seededTaskId = task.id else return@collectLatest
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    title = task.title,
                                    description = task.description,
                                    categoryId = task.categoryId,
                                    priority = task.priority,
                                    dueAt = task.dueAt,
                                    dueDateMillis = task.dueAt,
                                    dueTimeMillis = task.dueAt,
                                    reminderEnabled = task.reminderAt != null,
                                    reminderAt = task.reminderAt,
                                    subTasks = task.subTasks
                                )
                            }
                        }
                        is Result.Error -> _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                        is Result.Loading -> _uiState.update { it.copy(isLoading = true) }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to load task: ${e.message}") }
            }
        }
    }

    fun onTitleChange(title: String) {
        _uiState.update { it.copy(title = title, titleError = null) }
    }

    fun onDescriptionChange(desc: String) {
        _uiState.update { it.copy(description = desc) }
    }

    fun onCategorySelected(categoryId: String?) {
        _uiState.update { it.copy(categoryId = categoryId) }
    }

    fun onPrioritySelected(priority: Priority) {
        _uiState.update { it.copy(priority = priority) }
    }

    fun onDueDateSelected(millis: Long?) {
        if (millis == null) return
        _uiState.update { current ->
            try {
                val newDueAt = combineDateAndTime(millis, current.dueTimeMillis ?: current.dueAt)
                current.copy(dueDateMillis = millis, dueAt = newDueAt, errorMessage = null)
            } catch (e: Exception) {
                current.copy(errorMessage = "Invalid date: ${e.message}")
            }
        }
    }

    fun onDueTimeSelected(hour: Int, minute: Int) {
        _uiState.update { current ->
            try {
                val baseMillis = current.dueDateMillis ?: current.dueAt ?: System.currentTimeMillis()
                val cal = Calendar.getInstance().apply {
                    timeInMillis = baseMillis
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val timeMillis = cal.timeInMillis
                val combined = combineDateAndTime(current.dueDateMillis ?: System.currentTimeMillis(), timeMillis)
                current.copy(dueTimeMillis = timeMillis, dueAt = combined, dueDateMillis = current.dueDateMillis ?: combined, errorMessage = null)
            } catch (e: Exception) {
                current.copy(errorMessage = "Invalid time: ${e.message}")
            }
        }
    }

    private fun combineDateAndTime(dateMillis: Long?, timeMillis: Long?): Long? {
        if (dateMillis == null && timeMillis == null) return null
        return try {
            val calDate = Calendar.getInstance()
            val calTime = Calendar.getInstance()
            if (dateMillis != null) calDate.timeInMillis = dateMillis
            if (timeMillis != null) calTime.timeInMillis = timeMillis

            val result = Calendar.getInstance()
            if (dateMillis != null) {
                result.set(Calendar.YEAR, calDate.get(Calendar.YEAR))
                result.set(Calendar.MONTH, calDate.get(Calendar.MONTH))
                result.set(Calendar.DAY_OF_MONTH, calDate.get(Calendar.DAY_OF_MONTH))
            }
            if (timeMillis != null) {
                result.set(Calendar.HOUR_OF_DAY, calTime.get(Calendar.HOUR_OF_DAY))
                result.set(Calendar.MINUTE, calTime.get(Calendar.MINUTE))
            } else {
                result.set(Calendar.HOUR_OF_DAY, 9)
                result.set(Calendar.MINUTE, 0)
            }
            result.set(Calendar.SECOND, 0)
            result.set(Calendar.MILLISECOND, 0)
            result.timeInMillis
        } catch (e: Exception) {
            dateMillis ?: timeMillis ?: System.currentTimeMillis()
        }
    }

    fun onReminderToggle(enabled: Boolean) {
        _uiState.update {
            it.copy(
                reminderEnabled = enabled,
                reminderAt = if (enabled) it.dueAt ?: it.dueDateMillis ?: System.currentTimeMillis() + 3600000 else null
            )
        }
    }

    fun onNewSubTaskTitleChange(title: String) {
        _uiState.update { it.copy(newSubTaskTitle = title) }
    }

    fun addSubTask() {
        val title = _uiState.value.newSubTaskTitle.trim()
        if (title.isBlank()) return
        val newSub = SubTask(id = System.currentTimeMillis().toString() + "_" + (0..999).random(), title = title, isDone = false)
        _uiState.update { it.copy(subTasks = it.subTasks + newSub, newSubTaskTitle = "") }
        persistSubTasks()
    }

    fun removeSubTask(subTaskId: String) {
        _uiState.update { it.copy(subTasks = it.subTasks.filter { st -> st.id != subTaskId }) }
        persistSubTasks()
    }

    fun toggleSubTask(subTaskId: String) {
        _uiState.update { current ->
            val updated = current.subTasks.map { if (it.id == subTaskId) it.copy(isDone = !it.isDone) else it }
            current.copy(subTasks = updated)
        }
        persistSubTasks()
    }

    fun editSubTask(subTaskId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        _uiState.update { current ->
            val updated = current.subTasks.map { if (it.id == subTaskId) it.copy(title = newTitle.trim()) else it }
            current.copy(subTasks = updated)
        }
        persistSubTasks()
    }

    /**
     * Writes the checklist on its own, so ticking a sub-task sticks without having to
     * press Update. Reads the stored task first so no other field is clobbered by
     * unsaved form state. Best effort – a failure just leaves the change in memory.
     */
    private fun persistSubTasks() {
        val snapshot = _uiState.value
        val id = snapshot.taskId ?: return
        if (!snapshot.isEditMode) return
        viewModelScope.launch {
            try {
                val stored = taskRepository.observeTaskById(id).first()
                if (stored is Result.Success) {
                    taskRepository.updateTask(stored.data.copy(subTasks = snapshot.subTasks))
                }
            } catch (e: Exception) {
                android.util.Log.w("AddEditVM", "sub-task save skipped: ${e.message}")
            }
        }
    }

    fun saveTask(onSuccess: () -> Unit) {
        // Ignore double taps – a second save while one is in flight causes ghost docs.
        if (_uiState.value.isSaving) return

        val currentUser = auth.currentUser
        if (currentUser == null) {
            _uiState.update { it.copy(errorMessage = "Not authenticated. Please log in again.") }
            return
        }
        if (_uiState.value.title.isBlank()) {
            _uiState.update { it.copy(titleError = "Title required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val state = _uiState.value
                android.util.Log.d(
                    "AddEditVM",
                    "Saving task: title=${state.title} uid=${currentUser.uid} dueAt=${state.dueAt} cat=${state.categoryId}"
                )

                val task = Task(
                    id = state.taskId ?: "",
                    title = state.title.trim(),
                    description = state.description.trim(),
                    categoryId = state.categoryId,
                    priority = state.priority,
                    dueAt = state.dueAt,
                    reminderAt = if (state.reminderEnabled) state.reminderAt ?: state.dueAt else null,
                    subTasks = state.subTasks,
                    isCompleted = false
                )

                // Bounded so a missing Firestore ack can never leave the button spinning.
                val result = withTimeoutOrNull(SAVE_TIMEOUT_MS) {
                    if (state.isEditMode) {
                        updateTaskUseCase(task)
                    } else {
                        when (val res = addTaskUseCase(task)) {
                            is Result.Success -> Result.Success(Unit)
                            is Result.Error -> {
                                android.util.Log.e("AddEditVM", "addTaskUseCase failed: ${res.message}", res.exception)
                                res
                            }
                            else -> Result.Error(Exception("Unknown error"))
                        }
                    }
                }

                when {
                    result == null -> {
                        android.util.Log.e("AddEditVM", "Save timed out after ${SAVE_TIMEOUT_MS}ms")
                        _uiState.update {
                            it.copy(
                                errorMessage = "Save is taking too long. Check your connection and try again."
                            )
                        }
                    }
                    result is Result.Success -> {
                        android.util.Log.d("AddEditVM", "Task saved successfully")
                        onSuccess()
                    }
                    result is Result.Error -> {
                        val msg = result.message ?: "Failed to save task"
                        android.util.Log.e("AddEditVM", "Save failed: $msg", result.exception)
                        _uiState.update { it.copy(errorMessage = friendlySaveError(msg)) }
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                android.util.Log.e("AddEditVM", "saveTask crash prevented", e)
                _uiState.update { it.copy(errorMessage = "Could not save: ${e.message ?: "unknown error"}") }
            } finally {
                // Always release the spinner, whatever happened above.
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun friendlySaveError(msg: String): String = when {
        msg.contains("PERMISSION_DENIED", ignoreCase = true) ->
            "Firestore permission denied. Check firestore.rules allows users/{uid}/tasks."
        msg.contains("UNAVAILABLE", ignoreCase = true) || msg.contains("offline", ignoreCase = true) ->
            "Can't reach Firestore right now. The task will sync when you're back online."
        msg.contains("not authenticated", ignoreCase = true) ->
            "Not authenticated. Please log out and log in again."
        else -> msg
    }

    fun deleteTask(onSuccess: () -> Unit) {
        val taskId = _uiState.value.taskId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                when (val result = deleteTaskUseCase(taskId, softDelete = false)) {
                    is Result.Success -> {
                        _uiState.update { it.copy(isSaving = false) }
                        onSuccess()
                    }
                    is Result.Error -> _uiState.update { it.copy(isSaving = false, errorMessage = result.message) }
                    else -> _uiState.update { it.copy(isSaving = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Delete failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun setShowDeleteConfirm(show: Boolean) {
        _uiState.update { it.copy(showDeleteConfirm = show) }
    }
}
