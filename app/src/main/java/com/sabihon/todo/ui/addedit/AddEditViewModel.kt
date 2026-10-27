package com.sabihon.todo.ui.addedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sabihon.todo.core.datastore.AiPreferences
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.ai.AiTaskDraft
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.SubTask
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.repository.CategoryRepository
import com.sabihon.todo.domain.repository.TaskRepository
import com.sabihon.todo.domain.usecase.AddTaskUseCase
import com.sabihon.todo.domain.usecase.DeleteTaskUseCase
import com.sabihon.todo.domain.usecase.ParseQuickAddUseCase
import com.sabihon.todo.domain.usecase.QuickAddResult
import com.sabihon.todo.domain.usecase.RepeatScheduler
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
    private val parseQuickAddUseCase: ParseQuickAddUseCase,
    private val aiPreferences: AiPreferences,
    private val auth: FirebaseAuth
) : ViewModel() {

    private companion object {
        /** Hard ceiling for one save round-trip; keeps the Save button honest. */
        const val SAVE_TIMEOUT_MS = 20_000L
    }

    private val _uiState = MutableStateFlow(AddEditUiState())
    val uiState: StateFlow<AddEditUiState> = _uiState.asStateFlow()

    /**
     * The fields a quick-add is allowed to touch, captured before it runs so the strip's
     * Undo can put them back exactly.
     */
    private data class QuickAddSnapshot(
        val title: String,
        val description: String,
        val dueAt: Long?,
        val dueDateMillis: Long?,
        val dueTimeMillis: Long?,
        val priority: Priority,
        val categoryId: String?,
        val repeat: RepeatRule,
        val repeatDays: List<Int>,
        val subTasks: List<SubTask>,
        val reminderEnabled: Boolean,
        val reminderAt: Long?
    )

    private var quickAddUndo: QuickAddSnapshot? = null

    /**
     * The form is seeded from Firestore exactly once. Later emissions (including the
     * echo of our own sub-task writes) must not overwrite what the user is typing.
     */
    private var seededTaskId: String? = null

    /**
     * The document this form was opened on.
     *
     * Saving rebuilds a whole [Task], so anything the form does not edit - streak,
     * series id, completion state - has to be carried over from here or an edit
     * would quietly reset it.
     */
    private var originalTask: Task? = null

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
                                    subTasks = task.subTasks,
                                    repeat = task.repeat,
                                    repeatDays = task.repeatDays
                                )
                            }
                            // Outside the update block: `update` needs the lambda to
                            // return the new state, and an assignment returns Unit.
                            originalTask = task
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

    /** Repeat choice. Weekly with no days picked falls back to a sensible default. */
    fun onRepeatSelected(rule: RepeatRule) {
        _uiState.update { state ->
            val days = when {
                rule != RepeatRule.WEEKLY -> emptyList()
                state.repeatDays.isNotEmpty() -> state.repeatDays
                // Re-selecting Weekly keeps the previous days; a first switch seeds today.
                else -> listOf(java.time.LocalDate.now().dayOfWeek.value)
            }
            state.copy(repeat = rule, repeatDays = days)
        }
    }

    /** Adds or removes one weekday from a weekly schedule. */
    fun onRepeatDayToggled(day: Int) {
        _uiState.update { state ->
            val days = state.repeatDays.toMutableList()
            if (!days.remove(day)) days.add(day)
            days.sort()
            state.copy(
                repeat = if (days.isEmpty()) RepeatRule.NONE else RepeatRule.WEEKLY,
                repeatDays = days
            )
        }
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

    /* ---------------------------------------------------------------- */
    /* Quick add                                                        */
    /* ---------------------------------------------------------------- */

    /**
     * Reads the title box as a sentence and fills in what it can.
     *
     * It fills, it never saves: the user still presses Save, and the strip above the
     * title offers Undo for the fields this changed. A failure is a one-line message -
     * the form is untouched and the offline parser has already had its turn.
     */
    fun onQuickAdd() {
        val snapshot = _uiState.value
        val sentence = snapshot.title.trim()
        if (sentence.isEmpty() || snapshot.isQuickAddBusy) return

        quickAddUndo = null
        _uiState.update {
            it.copy(isQuickAddBusy = true, quickAddSummary = null, quickAddError = null)
        }

        viewModelScope.launch {
            // Read on every tap, not once at construction: the switch in Settings can
            // change while this editor is open behind it.
            val allowCloud = aiPreferences.aiEnabled.first()
            val result = parseQuickAddUseCase(sentence, snapshot.categories, allowCloud)
            val draft = result.draft

            if (!draft.hasDetails) {
                _uiState.update {
                    it.copy(
                        isQuickAddBusy = false,
                        quickAddError = "Couldn't find anything to fill in from that"
                    )
                }
                return@launch
            }

            quickAddUndo = QuickAddSnapshot(
                title = snapshot.title,
                description = snapshot.description,
                dueAt = snapshot.dueAt,
                dueDateMillis = snapshot.dueDateMillis,
                dueTimeMillis = snapshot.dueTimeMillis,
                priority = snapshot.priority,
                categoryId = snapshot.categoryId,
                repeat = snapshot.repeat,
                repeatDays = snapshot.repeatDays,
                subTasks = snapshot.subTasks,
                reminderEnabled = snapshot.reminderEnabled,
                reminderAt = snapshot.reminderAt
            )

            val dueMillis = draft.dueAt
            _uiState.update { current ->
                // Sub-tasks are added, never replaced: the sentence names new steps, and
                // wiping a checklist the user built by hand would be unforgivable.
                val existing = current.subTasks.map { it.title.lowercase() }.toSet()
                val extra = draft.subTasks
                    .filter { it.isNotBlank() && it.lowercase() !in existing }
                    .map { title ->
                        SubTask(
                            id = System.currentTimeMillis().toString() + "_" + (0..999).random(),
                            title = title,
                            isDone = false
                        )
                    }

                current.copy(
                    title = draft.title.ifBlank { current.title },
                    // Only a blank description is filled: the user's own note wins.
                    description = draft.description?.takeIf {
                        it.isNotBlank() && current.description.isBlank()
                    } ?: current.description,
                    subTasks = current.subTasks + extra,
                    // All three date fields move together: dueAt is what is saved,
                    // the other two are what the pickers reopen on.
                    dueAt = dueMillis ?: current.dueAt,
                    dueDateMillis = dueMillis ?: current.dueDateMillis,
                    dueTimeMillis = dueMillis ?: current.dueTimeMillis,
                    priority = draft.priority ?: current.priority,
                    categoryId = draft.categoryId ?: current.categoryId,
                    repeat = if (draft.repeat != RepeatRule.NONE) draft.repeat else current.repeat,
                    repeatDays = draft.repeatDays.ifEmpty { current.repeatDays },
                    // Tri-state: true turns the switch on and schedules against the due
                    // time; false turns it off; null leaves whatever the user chose.
                    reminderEnabled = draft.reminder ?: current.reminderEnabled,
                    reminderAt = when (draft.reminder) {
                        true -> (dueMillis ?: current.dueAt)?.let { due ->
                            due - (draft.reminderLeadMinutes ?: 0) * 60_000L
                        } ?: current.reminderAt
                        false -> null
                        null -> current.reminderAt
                    },
                    isQuickAddBusy = false,
                    quickAddSummary = summaryOf(draft, result.source, snapshot.categories),
                    quickAddError = null
                )
            }

        }
    }

    /** Puts back exactly the fields the last quick add changed. */
    fun onUndoQuickAdd() {
        val previous = quickAddUndo ?: return
        quickAddUndo = null
        _uiState.update { current ->
            current.copy(
                title = previous.title,
                description = previous.description,
                dueAt = previous.dueAt,
                dueDateMillis = previous.dueDateMillis,
                dueTimeMillis = previous.dueTimeMillis,
                priority = previous.priority,
                categoryId = previous.categoryId,
                repeat = previous.repeat,
                repeatDays = previous.repeatDays,
                subTasks = previous.subTasks,
                reminderEnabled = previous.reminderEnabled,
                reminderAt = previous.reminderAt,
                isQuickAddBusy = false,
                quickAddSummary = null,
                quickAddError = null
            )
        }
    }

    fun onDismissQuickAdd() {
        quickAddUndo = null
        _uiState.update { it.copy(quickAddSummary = null, quickAddError = null) }
    }

    /**
     * "AI filled: Fri 30 Oct, 17:00 · High · Work".
     *
     * Says which half did the work, because a cloud parse and an offline one are not
     * the same promise - and because the user deserves to know a sentence left the
     * device at all.
     */
    private fun summaryOf(
        draft: AiTaskDraft,
        source: QuickAddResult.Source,
        categories: List<Category>
    ): String {
        val parts = mutableListOf<String>()

        draft.dueAt?.let { millis ->
            parts += SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
        }
        draft.priority?.let { priority ->
            parts += priority.name.lowercase().replaceFirstChar { it.uppercase() }
        }
        draft.categoryId?.let { id ->
            categories.firstOrNull { it.id == id }?.name?.let { parts += it }
        }
        when (draft.repeat) {
            RepeatRule.DAILY -> parts += "Daily"
            RepeatRule.WEEKLY -> parts += "Weekly"
            RepeatRule.NONE -> Unit
        }
        if (!draft.description.isNullOrBlank()) parts += "Note"
        if (draft.subTasks.isNotEmpty()) {
            parts += if (draft.subTasks.size == 1) "1 sub-task" else "${draft.subTasks.size} sub-tasks"
        }
        if (draft.reminder == true) parts += "Reminder"
        if (draft.reminder == false) parts += "No reminder"

        val label = if (source == QuickAddResult.Source.CLOUD) "AI filled" else "Filled offline"
        return if (parts.isEmpty()) label else "$label: ${parts.joinToString(" · ")}"
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

                val original = originalTask
                // A repeating task needs a date to count occurrences from; without one
                // it can never be scheduled, so default to today at 09:00.
                val dueAt = state.dueAt ?: if (state.repeat.isRecurring) {
                    RepeatScheduler.dueAtMillis(java.time.LocalDate.now())
                } else {
                    null
                }

                val task = Task(
                    id = state.taskId ?: "",
                    title = state.title.trim(),
                    description = state.description.trim(),
                    categoryId = state.categoryId,
                    priority = state.priority,
                    dueAt = dueAt,
                    reminderAt = if (state.reminderEnabled) state.reminderAt ?: dueAt else null,
                    subTasks = state.subTasks,
                    isCompleted = original?.isCompleted ?: false,
                    completedAt = original?.completedAt,
                    repeat = state.repeat,
                    repeatDays = if (state.repeat == RepeatRule.WEEKLY) state.repeatDays else emptyList(),
                    streak = original?.streak ?: 0,
                    lastCompletedDay = original?.lastCompletedDay,
                    seriesId = original?.seriesId
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
