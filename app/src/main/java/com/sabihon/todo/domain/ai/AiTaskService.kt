package com.sabihon.todo.domain.ai

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Category

/**
 * Everything the app asks a language model to do.
 *
 * Kept to plain Kotlin types so the domain layer knows nothing about Firebase or
 * network calls, and unit tests can replace it with a stub in one line.
 */
interface AiTaskService {

    /**
     * Reads one sentence of natural language ("call mom friday 5pm, urgent") and
     * returns the fields it implies.
     *
     * [categories] is the user's own list of categories, passed in so the model can
     * match by meaning ("work", "groceries") without inventing an id that does not exist.
     */
    suspend fun parseQuickAdd(
        input: String,
        categories: List<Category>
    ): Result<AiTaskDraft>

    /**
     * Evaluates a compact, PII-scrubbed list of open tasks and proposes a focus order
     * (`1..N`), up to 3 Top Focus picks, and calibrated [com.sabihon.todo.domain.model.Priority] changes.
     */
    suspend fun prioritizeTasks(
        tasks: List<PromptTaskItem>,
        modelTier: AiModelTier = AiModelTier.FLASH_LITE
    ): Result<CloudPrioritizationDraft> =
        Result.Error(UnsupportedOperationException("Prioritization not configured"))

    /**
     * Breaks a task down into 3–6 concrete, verb-first checklist steps (`Phase 3`).
     */
    suspend fun suggestSubTasks(
        request: PromptSubTaskRequest,
        modelTier: AiModelTier = AiModelTier.FLASH_LITE
    ): Result<List<String>> =
        Result.Error(UnsupportedOperationException("Sub-task suggestions not configured"))

    /**
     * Proposes up to 3 grounded follow-up tasks from active projects and recent
     * completions (`Phase 4`).
     */
    suspend fun suggestNextTasks(
        context: PromptNextTaskContext,
        modelTier: AiModelTier = AiModelTier.FLASH_LITE
    ): Result<List<CloudSuggestedTaskItem>> =
        Result.Error(UnsupportedOperationException("Next-task suggestions not configured"))
}
