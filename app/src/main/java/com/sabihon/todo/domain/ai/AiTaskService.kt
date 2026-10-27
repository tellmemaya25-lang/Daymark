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
     */
    suspend fun parseQuickAdd(input: String, categories: List<Category>): Result<AiTaskDraft>

    /**
     * Audits up to 25 scrubbed open tasks and returns candidate focus ranks and
     * priority calibrations.
     */
    suspend fun prioritizeTasks(
        tasks: List<PromptTaskItem>,
        modelTier: AiModelTier = AiModelTier.FLASH_LITE
    ): Result<CloudPrioritizationDraft> =
        Result.Error(UnsupportedOperationException("Prioritization not supported by this service"))
}
