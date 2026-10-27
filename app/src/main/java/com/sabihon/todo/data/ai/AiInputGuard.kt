package com.sabihon.todo.data.ai

import com.sabihon.todo.domain.ai.PromptCategoryItem
import com.sabihon.todo.domain.ai.PromptNextTaskContext
import com.sabihon.todo.domain.ai.PromptSubTaskRequest
import com.sabihon.todo.domain.ai.PromptTaskItem
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Task
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Pre-egress input guardrails (`docs/02-daymark-ai-system-design.md` §3.1):
 *  1. Scrubs personal emails, phone numbers, URLs, and card-length digit sequences
 *     from prompt copies without touching stored [Task] documents.
 *  2. Neutralises XML delimiter characters (`<`, `>`) so task text cannot break out
 *     of `<untrusted_tasks_json>`, `<untrusted_task_json>`, or `<untrusted_context_json>`.
 *  3. Bounds task count (`25` / `10`) and per-field character length (`80` / `120`) so no
 *     prompt can burn unbounded tokens.
 */
@Singleton
class AiInputGuard @Inject constructor() {

    fun scrubPii(raw: String): String {
        if (raw.isBlank()) return ""
        var cleaned = raw
        cleaned = URL_REGEX.replace(cleaned, "[URL]")
        cleaned = EMAIL_REGEX.replace(cleaned, "[EMAIL]")
        cleaned = CARD_DIGITS_REGEX.replace(cleaned) { match ->
            val digitsOnly = match.value.count { it.isDigit() }
            if (digitsOnly in 13..19) "[REDACTED]" else match.value
        }
        cleaned = PHONE_REGEX.replace(cleaned) { match ->
            val candidate = match.value.trim()
            val digitsOnly = candidate.count { it.isDigit() }
            // Keep ISO dates (2026-10-27 -> 8 digits) intact; redact 10+ digit numbers
            // and explicit international (+...) numbers with at least 8 digits.
            val isExplicitIntl = candidate.startsWith("+") && digitsOnly >= 8
            if (isExplicitIntl || digitsOnly in 10..15) "[PHONE]" else match.value
        }
        return cleaned
    }

    fun sanitizeForPrompt(raw: String, maxChars: Int): String {
        if (raw.isBlank()) return ""
        return scrubPii(raw)
            .replace('<', '(')
            .replace('>', ')')
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .take(maxChars)
    }

    /**
     * Selects up to [MAX_PROMPT_TASKS] open tasks pre-ranked by [scorer] and converts
     * them into compact, PII-scrubbed [PromptTaskItem]s with signed relative hours.
     */
    fun buildPromptItems(
        openTasks: List<Task>,
        categories: List<Category>,
        scorer: LocalPriorityScorer,
        nowMillis: Long = System.currentTimeMillis(),
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        limit: Int = MAX_PROMPT_TASKS
    ): List<PromptTaskItem> {
        val categoryMap = categories.associate { it.id to sanitizeForPrompt(it.name, MAX_CATEGORY_CHARS) }
        val candidates = scorer
            .rankTasks(openTasks.filter { !it.isCompleted && !it.isDeleted }, nowMillis, today, zone)
            .take(limit.coerceIn(1, MAX_PROMPT_TASKS))

        return candidates.map { task ->
            val sanitizedTitle = sanitizeForPrompt(task.title, MAX_TITLE_CHARS)
                .ifBlank { "Untitled task" }
            val sanitizedNote = task.description
                .takeIf { it.isNotBlank() }
                ?.let { sanitizeForPrompt(it, MAX_DESCRIPTION_CHARS) }
                ?.takeIf { it.isNotBlank() }
            val dueHours = task.dueAt?.let { due ->
                ((due - nowMillis).toDouble() / HOUR_MS.toDouble()).roundToInt()
                    .coerceIn(-MAX_OFFSET_HOURS, MAX_OFFSET_HOURS)
            }
            val subProgress = if (task.subTasks.isNotEmpty()) {
                "${task.subTasks.count { it.isDone }}/${task.subTasks.size}"
            } else {
                null
            }

            PromptTaskItem(
                id = task.id,
                title = sanitizedTitle,
                note = sanitizedNote,
                currentPriority = task.priority,
                categoryName = task.categoryId?.let { categoryMap[it] }?.takeIf { it.isNotBlank() },
                dueOffsetHours = dueHours,
                streak = task.streak.coerceAtLeast(0),
                subTaskProgress = subProgress
            )
        }
    }

    /**
     * Builds a PII-scrubbed, length-bounded [PromptSubTaskRequest] (`Phase 3`).
     */
    fun buildSubTaskRequest(
        title: String,
        description: String,
        existingSubTasks: List<String>
    ): PromptSubTaskRequest {
        val cleanTitle = sanitizeForPrompt(title, MAX_TITLE_CHARS)
        val cleanDescription = description
            .takeIf { it.isNotBlank() }
            ?.let { sanitizeForPrompt(it, MAX_SUBTASK_DESC_CHARS) }
            ?.takeIf { it.isNotBlank() }
        val cleanExisting = existingSubTasks
            .map { sanitizeForPrompt(it, MAX_TITLE_CHARS) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_EXISTING_SUBTASKS)

        return PromptSubTaskRequest(
            title = cleanTitle,
            description = cleanDescription,
            existingSubTasks = cleanExisting
        )
    }

    /**
     * Builds a PII-scrubbed, token-bounded [PromptNextTaskContext] (`Phase 4`).
     */
    fun buildNextTaskContext(
        openTasks: List<Task>,
        completedTasks: List<Task>,
        categories: List<Category>,
        targetCategoryId: String?,
        scorer: LocalPriorityScorer,
        nowMillis: Long = System.currentTimeMillis(),
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): PromptNextTaskContext {
        val scopedOpen = if (targetCategoryId != null) {
            openTasks.filter { it.categoryId == targetCategoryId }
        } else {
            openTasks
        }
        val scopedCompleted = if (targetCategoryId != null) {
            completedTasks.filter { it.categoryId == targetCategoryId }
        } else {
            completedTasks
        }

        val promptOpen = buildPromptItems(
            openTasks = scopedOpen,
            categories = categories,
            scorer = scorer,
            nowMillis = nowMillis,
            today = today,
            zone = zone,
            limit = MAX_NEXT_TASK_OPEN_CONTEXT
        )

        val recentCompletedTitles = scopedCompleted
            .filter { it.isCompleted && !it.isDeleted }
            .sortedByDescending { it.completedAt ?: it.updatedAt }
            .map { sanitizeForPrompt(it.title, MAX_TITLE_CHARS) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_NEXT_TASK_COMPLETED_CONTEXT)

        val promptCategories = categories
            .take(MAX_CONTEXT_CATEGORIES)
            .mapNotNull { cat ->
                val cleanName = sanitizeForPrompt(cat.name, MAX_CATEGORY_CHARS)
                if (cat.id.isBlank() || cleanName.isBlank()) null
                else PromptCategoryItem(id = cat.id, name = cleanName)
            }

        val targetCatName = targetCategoryId?.let { id ->
            promptCategories.firstOrNull { it.id == id }?.name
        }

        return PromptNextTaskContext(
            openTasks = promptOpen,
            recentlyCompletedTitles = recentCompletedTitles,
            categories = promptCategories,
            targetCategoryId = targetCategoryId,
            targetCategoryName = targetCatName
        )
    }

    fun estimateTokens(promptChars: Int, expectedOutputChars: Int = 700): Int =
        ((promptChars.coerceAtLeast(0) + expectedOutputChars.coerceAtLeast(0)) / 4).coerceAtLeast(1)

    companion object {
        const val MAX_PROMPT_TASKS = 25
        const val MAX_TITLE_CHARS = 80
        const val MAX_DESCRIPTION_CHARS = 120
        const val MAX_SUBTASK_DESC_CHARS = 160
        const val MAX_CATEGORY_CHARS = 32
        const val MAX_EXISTING_SUBTASKS = 12
        const val MAX_NEXT_TASK_OPEN_CONTEXT = 10
        const val MAX_NEXT_TASK_COMPLETED_CONTEXT = 8
        const val MAX_CONTEXT_CATEGORIES = 15
        private const val HOUR_MS = 3_600_000L
        private const val MAX_OFFSET_HOURS = 24 * 365 * 5

        private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
        private val CARD_DIGITS_REGEX = Regex("""(?<!\d)(?:\d[ \-]?){13,19}(?!\d)""")
        private val PHONE_REGEX = Regex("""(?<!\w)(?:\+\d{1,3}[\s\-]?)?(?:\(\d{2,4}\)[\s\-]?)?\d[\d\s\-]{6,16}\d(?!\w)""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
