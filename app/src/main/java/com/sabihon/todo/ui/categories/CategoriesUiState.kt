package com.sabihon.todo.ui.categories

import com.sabihon.todo.domain.ai.SuggestedTaskCandidate
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.ui.home.HomeSort
import com.sabihon.todo.ui.home.HomeSortDirection

data class CategoriesUiState(
    val isLoading: Boolean = true,
    val categories: List<Category> = emptyList(),
    val error: String? = null,
    val showAddDialog: Boolean = false,
    val editingCategory: Category? = null
)

data class CategoryDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val category: Category? = null,
    val tasks: List<Task> = emptyList(),
    val openTasks: List<Task> = emptyList(),
    val completedTasks: List<Task> = emptyList(),
    val sort: HomeSort = HomeSort.DUE_DATE,
    val sortDirection: HomeSortDirection = HomeSortDirection.ASCENDING,
    val error: String? = null,
    val snackbarMessage: String? = null,
    val deletedTaskIdForUndo: String? = null,
    // ---- suggested next steps (Phase 4) ----
    val suggestions: List<SuggestedTaskCandidate> = emptyList(),
    val isSuggesting: Boolean = false,
    val suggestionsExpanded: Boolean = true
) {
    val totalCount: Int get() = openTasks.size + completedTasks.size
}
