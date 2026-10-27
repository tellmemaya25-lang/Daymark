package com.sabihon.todo.domain.ai

import com.sabihon.todo.domain.model.Priority
import java.security.MessageDigest

/**
 * Compact, PII-scrubbed request sent to [AiTaskService.suggestSubTasks] (`Phase 3`).
 */
data class PromptSubTaskRequest(
    val title: String,
    val description: String? = null,
    val existingSubTasks: List<String> = emptyList()
)

/**
 * Validated checklist breakdown result ready for `AddEditTaskScreen` and `HomeScreen`.
 */
data class SubTaskSuggestionsResult(
    val steps: List<String>,
    val source: AiSource,
    val modelTier: AiModelTier = AiModelTier.LOCAL
)

data class PromptCategoryItem(
    val id: String,
    val name: String
)

/**
 * Compact, PII-scrubbed context sent to [AiTaskService.suggestNextTasks] (`Phase 4`).
 */
data class PromptNextTaskContext(
    val openTasks: List<PromptTaskItem>,
    val recentlyCompletedTitles: List<String>,
    val categories: List<PromptCategoryItem>,
    val targetCategoryId: String? = null,
    val targetCategoryName: String? = null
)

/**
 * Raw unvalidated suggestion returned by the cloud adapter before closed-world
 * category whitelisting, open-task deduplication, and date clamping.
 */
data class CloudSuggestedTaskItem(
    val title: String,
    val description: String? = null,
    val categoryId: String? = null,
    val priority: Priority? = null,
    val dueOffsetHours: Int? = null,
    val reason: String? = null
)

/**
 * One validated, actionable next-task candidate (`docs/02-daymark-ai-system-design.md` §5.1).
 */
data class SuggestedTaskCandidate(
    /** Deterministic hash of normalized title + categoryId used for permanent dismissal. */
    val suggestionId: String,
    val title: String,
    val description: String? = null,
    val categoryId: String? = null,
    val priority: Priority = Priority.MEDIUM,
    val dueAt: Long? = null,
    /** Concise single-line rationale (max 90 chars). */
    val reason: String
) {
    companion object {
        fun computeId(title: String, categoryId: String?): String {
            val normalizedTitle = title
                .lowercase()
                .replace(NON_ALNUM_REGEX, " ")
                .trim()
                .replace(MULTI_SPACE_REGEX, " ")
            val raw = "${categoryId?.trim().orEmpty()}::$normalizedTitle"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }

        private val NON_ALNUM_REGEX = Regex("[^a-z0-9\\s]")
        private val MULTI_SPACE_REGEX = Regex("\\s+")
    }
}

/**
 * Validated next-task suggestions ready for `SuggestedTasksCard` on Home & Project screens.
 */
data class TaskSuggestionsResult(
    val candidates: List<SuggestedTaskCandidate>,
    val source: AiSource,
    val modelTier: AiModelTier = AiModelTier.LOCAL
)
