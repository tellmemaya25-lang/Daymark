package com.sabihon.todo.ui.addedit

import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.SubTask

/**
 * UiState for Add/Edit Task.
 */
data class AddEditUiState(
    val taskId: String? = null,
    val title: String = "",
    val description: String = "",
    val categoryId: String? = null,
    val priority: Priority = Priority.MEDIUM,
    val dueDateMillis: Long? = null,
    val dueTimeMillis: Long? = null,
    val dueAt: Long? = null,
    val reminderEnabled: Boolean = false,
    val reminderAt: Long? = null,
    val subTasks: List<SubTask> = emptyList(),
    val newSubTaskTitle: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val titleError: String? = null,
    val categories: List<Category> = emptyList(),
    val isEditMode: Boolean = false,
    val showDeleteConfirm: Boolean = false,

    // ---- repeating task ----
    val repeat: RepeatRule = RepeatRule.NONE,
    /** Selected weekdays, 1 = Monday … 7 = Sunday. Only meaningful for WEEKLY. */
    val repeatDays: List<Int> = emptyList(),

    // ---- quick add ----
    /** A parse is in flight; the button shows a small spinner and ignores taps. */
    val isQuickAddBusy: Boolean = false,
    /** "AI filled: Fri 30 Oct, 17:00 · High" - null hides the strip. */
    val quickAddSummary: String? = null,
    /** The one-line failure message, when there was nothing worth filling. */
    val quickAddError: String? = null
)
