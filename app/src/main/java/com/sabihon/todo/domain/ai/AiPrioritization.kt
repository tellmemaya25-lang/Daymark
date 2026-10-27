package com.sabihon.todo.domain.ai

import com.sabihon.todo.domain.model.Priority

/**
 * Provenance shown on AI surfaces so the user always knows whether a plan came
 * from Gemini, a recent local snapshot, or deterministic on-device rules.
 */
enum class AiSource {
    CLOUD,
    CACHE,
    LOCAL_FALLBACK
}

/**
 * Rungs of the minimum-sufficient-intelligence ladder (`docs/02-daymark-ai-system-design.md` §7).
 */
enum class AiModelTier(val modelId: String) {
    LOCAL("local-scorer"),
    FLASH_LITE("gemini-2.5-flash-lite"),
    FLASH("gemini-2.5-flash")
}

/**
 * Compact, PII-scrubbed representation of an open task passed to [AiTaskService].
 *
 * Uses signed relative hours ([dueOffsetHours]) instead of 13-digit epoch
 * milliseconds so the model never has to do Unix timestamp or timezone arithmetic.
 */
data class PromptTaskItem(
    val id: String,
    val title: String,
    val note: String? = null,
    val currentPriority: Priority,
    val categoryName: String? = null,
    /** Hours from `now`: negative means overdue, null means undated. */
    val dueOffsetHours: Int? = null,
    val streak: Int = 0,
    /** `"2/3"` when the task has a checklist, null otherwise. */
    val subTaskProgress: String? = null
)

/**
 * Raw unvalidated draft returned by the cloud adapter before closed-world
 * sanitization in `PrioritizeTasksUseCase`.
 */
data class CloudPrioritizationDraft(
    val summaryHeadline: String? = null,
    val items: List<CloudPriorityItem> = emptyList()
)

data class CloudPriorityItem(
    val taskId: String,
    val suggestedPriority: Priority? = null,
    val focusRank: Int? = null,
    val isTopFocus: Boolean = false,
    val reason: String? = null
)

/**
 * One task's focus rank and optional priority adjustment after validation.
 */
data class TaskPriorityRecommendation(
    val taskId: String,
    val currentPriority: Priority,
    val suggestedPriority: Priority,
    /** 1-based focus order across open tasks (`1` = tackle first). */
    val focusRank: Int,
    /** At most 3 tasks carry `true` per plan. */
    val isTopFocus: Boolean,
    /** Concise single-line rationale (max 90 chars). */
    val reason: String,
    /** Deterministic Rung-0 score from `LocalPriorityScorer` (always present). */
    val localScore: Int
) {
    val hasPriorityChange: Boolean get() = suggestedPriority != currentPriority
}

/**
 * Validated prioritization result ready for the review sheet and smart sort.
 */
data class PrioritizationPlan(
    val recommendations: List<TaskPriorityRecommendation>,
    val summaryHeadline: String,
    val source: AiSource,
    val modelTier: AiModelTier = AiModelTier.LOCAL,
    val generatedAtMillis: Long = System.currentTimeMillis()
) {
    val proposedChangesCount: Int
        get() = recommendations.count { it.hasPriorityChange }

    val focusRankMap: Map<String, Int>
        get() = recommendations.associate { it.taskId to it.focusRank }
}
