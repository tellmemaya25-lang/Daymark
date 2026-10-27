package com.sabihon.todo.ui.categories

import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.ai.SuggestedTaskCandidate
import com.sabihon.todo.domain.model.Task

data class CategoriesUiState(
    val isLoading: Boolean = true,
    val categories: List<Category> = emptyList(),
    val error: String? = null,
    val showAddDialog: Boolean = false,
    val editingCategory: Category? = null
)

data class CategoryDetailUiState(
    val isLoading: Boolean = true,
    val category: Category? = null,
    val tasks: List<Task> = emptyList(),
    val error: String? = null,
    // ---- suggested next steps (Phase 4) ----
    val suggestions: List<SuggestedTaskCandidate> = emptyList(),
    val isSuggesting: Boolean = false,
    val suggestionsExpanded: Boolean = true
)
