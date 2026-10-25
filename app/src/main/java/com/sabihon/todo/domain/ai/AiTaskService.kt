package com.sabihon.todo.domain.ai

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Category

/**
 * Turns a typed sentence into editor fields.
 *
 * One method on purpose. The interesting behaviour is not "call a model" - it is what
 * happens when the model is slow, offline, wrong, or switched off, and that decision
 * belongs to a single caller (`ParseQuickAddUseCase`) rather than being spread across
 * implementations.
 */
interface AiTaskService {

    /**
     * @param input the raw sentence the user typed.
     * @param categories the user's own lists - the only ids this may return.
     */
    suspend fun parseQuickAdd(input: String, categories: List<Category>): Result<AiTaskDraft>
}
