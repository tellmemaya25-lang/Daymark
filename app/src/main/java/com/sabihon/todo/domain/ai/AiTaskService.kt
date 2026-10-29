package com.sabihon.todo.domain.ai

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Category

/**
 * Cloud AI boundary for task parsing and prioritization.
 *
 * Implementations stay thin: build the prompt, ask for structured JSON, and map
 * the wire shape. Timeouts, quota checks, PII scrubbing, closed-world validation,
 * and deterministic fallbacks live in the domain use cases so they can be tested
 * deterministically on the JVM without a network.
 */
interface AiTaskService {

    /**
     * @param input the raw (or PII-scrubbed) sentence the user typed.
     * @param categories the user's own lists - the only ids this may return.
     * @param uid whose cloud budget this call spends, so the per-provider daily counters
     *   land on the right account and the right wallet. A null uid is billed as "anon".
     */
    suspend fun parseQuickAdd(
        input: String,
        categories: List<Category>,
        uid: String? = null
    ): Result<AiTaskDraft>

    /**
     * Audits up to 25 scrubbed open tasks and returns candidate focus ranks and
     * priority calibrations.
     */
    suspend fun prioritizeTasks(
        tasks: List<PromptTaskItem>,
        modelTier: AiModelTier = AiModelTier.FLASH_LITE,
        uid: String? = null
    ): Result<CloudPrioritizationDraft> =
        Result.Error(UnsupportedOperationException("Prioritization not supported by this service"))

    /**
     * Breaks one task into concrete steps.
     *
     * @param title the task being broken down.
     * @param description the note attached to it, if any - often where the detail lives.
     * @param existingSubTasks steps the user already typed, which must not come back.
     * @return raw candidate steps. Cleaning, de-duplication, capping and ordering are the
     *   use case's job, so every implementation - cloud, cached or heuristic - is held to
     *   the same rules.
     */
    suspend fun suggestSubTasks(
        title: String,
        description: String?,
        existingSubTasks: List<String>,
        uid: String? = null
    ): Result<List<String>> =
        Result.Error(UnsupportedOperationException("Step suggestions not supported by this service"))

    /**
     * Suggests up to three things worth doing next.
     *
     * @param context only titles and list names - see [SuggestionContext].
     * @return raw candidates. Whitelisting the list id, dropping anything the user
     *   already has open or has muted, and capping the count are the use case's job, so
     *   the local heuristics and the cloud answer are judged by identical rules.
     */
    suspend fun suggestNextTasks(
        context: SuggestionContext,
        uid: String? = null
    ): Result<List<SuggestedTaskCandidate>> =
        Result.Error(UnsupportedOperationException("Next-task suggestions not supported by this service"))
}
