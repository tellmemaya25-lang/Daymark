package com.sabihon.todo.data.ai

import com.sabihon.todo.domain.ai.SuggestedTaskCandidate
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rung 0 offline suggestion heuristics (`docs/02-daymark-ai-system-design.md` §7 & §11.1).
 *
 * Runs in `< 2 ms` with `0` tokens whenever Cloud AI is disabled, the device is offline,
 * or daily quota / circuit breaker is active.
 */
@Singleton
class LocalSuggestionHeuristics @Inject constructor() {

    /**
     * Generates 3–5 concrete checklist steps offline from compound phrases or domain
     * intent templates, deduplicated against [existingSubTasks].
     */
    fun suggestSubTasks(
        title: String,
        description: String = "",
        existingSubTasks: List<String> = emptyList()
    ): List<String> {
        val cleanTitle = title.trim()
        if (cleanTitle.length < 3) return emptyList()

        val existingLower = existingSubTasks.map { it.trim().lowercase() }.toSet()
        val combinedLower = "$cleanTitle ${description.trim()}".lowercase()

        // 1. If the title/description explicitly lists comma/conjunction clauses, split them first.
        val explicitParts = extractExplicitClauses(cleanTitle, description)
        val templateSteps = when {
            explicitParts.size >= 2 -> explicitParts
            matchesAny(combinedLower, "slide", "deck", "presentation", "pitch", "demo") -> listOf(
                "Outline key talking points and flow",
                "Draft core slides and visuals",
                "Run a timed rehearsal",
                "Export PDF backup and share link"
            )
            matchesAny(combinedLower, "exam", "quiz", "test", "study", "lecture", "midterm", "final") -> listOf(
                "Review syllabus topics and weak areas",
                "Summarize key formulas and concepts",
                "Work through practice problems",
                "Do a 15-minute recall check"
            )
            matchesAny(combinedLower, "report", "essay", "paper", "proposal", "doc", "spec", "thesis") -> listOf(
                "Outline sections and main takeaway",
                "Gather supporting data and references",
                "Write first complete draft",
                "Proofread and format final version"
            )
            matchesAny(combinedLower, "trip", "travel", "flight", "pack", "hotel", "vacation") -> listOf(
                "Confirm bookings and travel times",
                "Pack essentials and chargers",
                "Download tickets and offline maps",
                "Set departure reminder"
            )
            matchesAny(combinedLower, "meeting", "retro", "standup", "sync", "interview", "call") -> listOf(
                "Prepare agenda and top questions",
                "Confirm time and meeting link",
                "Capture decisions and action items",
                "Send brief follow-up summary"
            )
            matchesAny(combinedLower, "bill", "invoice", "tax", "budget", "rent", "pay", "reimburse") -> listOf(
                "Verify amount due and account details",
                "Submit payment or transfer",
                "Save receipt or confirmation number"
            )
            matchesAny(combinedLower, "bug", "deploy", "release", "refactor", "pr", "feature", "code") -> listOf(
                "Reproduce issue and confirm scope",
                "Implement fix and edge-case checks",
                "Run unit tests and verify build",
                "Open PR with concise summary"
            )
            matchesAny(combinedLower, "grocery", "groceries", "shop", "market", "buy", "supplies") -> listOf(
                "Check pantry and list missing items",
                "Group items by store section",
                "Pick up essentials and put away"
            )
            matchesAny(combinedLower, "clean", "organize", "declutter", "laundry", "tidy", "move") -> listOf(
                "Clear surfaces and sort items to keep",
                "Wipe down and organize main area",
                "Put away or donate remaining items"
            )
            else -> listOf(
                "Clarify the goal and first small step",
                "Complete the main part of $cleanTitle".take(MAX_STEP_CHARS),
                "Review details and wrap up"
            )
        }

        return templateSteps
            .map { sentenceCase(it.trim()).take(MAX_STEP_CHARS) }
            .filter { it.length >= 3 && it.lowercase() != cleanTitle.lowercase() }
            .filter { it.lowercase() !in existingLower }
            .distinctBy { it.lowercase() }
            .take(MAX_SUBTASK_SUGGESTIONS)
    }

    /**
     * Generates up to 3 grounded next-task candidates offline from recent completions,
     * project state, and overdue work.
     */
    fun suggestNextTasks(
        openTasks: List<Task>,
        completedTasks: List<Task>,
        categories: List<Category>,
        targetCategoryId: String? = null,
        dismissedIds: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis(),
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<SuggestedTaskCandidate> {
        val validCategoryIds = categories.map { it.id }.toSet()
        val categoryNames = categories.associate { it.id to it.name }
        val activeOpen = openTasks.filter { !it.isCompleted && !it.isDeleted }
        val scopedOpen = if (targetCategoryId != null) {
            activeOpen.filter { it.categoryId == targetCategoryId }
        } else {
            activeOpen
        }
        val scopedCompleted = completedTasks
            .filter { it.isCompleted && !it.isDeleted }
            .filter { targetCategoryId == null || it.categoryId == targetCategoryId }
            .sortedByDescending { it.completedAt ?: it.updatedAt }

        val openTitleTokens = activeOpen.map { AiSnapshotCache.normalizeSemanticTokens(it.title) }
        val rawCandidates = mutableListOf<SuggestedTaskCandidate>()

        val tomorrowNineAm = today.plusDays(1)
            .atTime(9, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
            .coerceAtLeast(nowMillis + HOUR_MS)

        // 1. Follow-up from recent completions (e.g. "Draft X" -> "Review and finalize X")
        for (doneTask in scopedCompleted.take(5)) {
            val followUpTitle = deriveFollowUpFromCompleted(doneTask.title) ?: continue
            val catId = (targetCategoryId ?: doneTask.categoryId)?.takeIf { it in validCategoryIds }
            rawCandidates += buildCandidate(
                title = followUpTitle,
                categoryId = catId,
                priority = Priority.MEDIUM,
                dueAt = tomorrowNineAm,
                reason = "Follows '${doneTask.title.take(36)}' you completed recently"
            )
            if (rawCandidates.size >= 4) break
        }

        // 2. Overdue triage suggestion when >= 2 open tasks have slipped past their due time
        val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val overdueTasks = scopedOpen.filter { it.dueAt != null && it.dueAt < minOf(nowMillis, startOfToday) }
        if (overdueTasks.size >= 2) {
            val catId = targetCategoryId?.takeIf { it in validCategoryIds }
            rawCandidates += buildCandidate(
                title = "Review and reschedule ${overdueTasks.size} overdue tasks",
                categoryId = catId,
                priority = Priority.HIGH,
                dueAt = nowMillis + 2 * HOUR_MS,
                reason = "${overdueTasks.size} open tasks slipped past their due time"
            )
        }

        // 3. Project milestone kick-off when a project has completions or 0 open tasks
        if (targetCategoryId != null && targetCategoryId in validCategoryIds && scopedOpen.isEmpty()) {
            val projectName = categoryNames[targetCategoryId] ?: "this project"
            rawCandidates += buildCandidate(
                title = "Outline next milestone for $projectName",
                categoryId = targetCategoryId,
                priority = Priority.MEDIUM,
                dueAt = tomorrowNineAm,
                reason = "No open tasks left in $projectName"
            )
        } else {
            val emptyActiveProject = categories.firstOrNull { cat ->
                activeOpen.none { it.categoryId == cat.id } &&
                    completedTasks.any { it.isCompleted && !it.isDeleted && it.categoryId == cat.id }
            }
            if (emptyActiveProject != null && targetCategoryId == null) {
                rawCandidates += buildCandidate(
                    title = "Plan next step for ${emptyActiveProject.name}",
                    categoryId = emptyActiveProject.id,
                    priority = Priority.MEDIUM,
                    dueAt = tomorrowNineAm,
                    reason = "All current tasks in ${emptyActiveProject.name} are complete"
                )
            }
        }

        // 4. Calm starter fallback so an explicit tap never comes back empty-handed
        if (rawCandidates.isEmpty()) {
            val fallbackCat = targetCategoryId?.takeIf { it in validCategoryIds }
            val label = fallbackCat?.let { categoryNames[it] }
            rawCandidates += buildCandidate(
                title = if (label != null) {
                    "Pick top priority for $label this week"
                } else {
                    "Block 15 minutes to plan tomorrow's top 3 tasks"
                },
                categoryId = fallbackCat,
                priority = Priority.LOW,
                dueAt = tomorrowNineAm,
                reason = "Keeps your upcoming schedule clear and realistic"
            )
        }

        val accepted = mutableListOf<SuggestedTaskCandidate>()
        for (candidate in rawCandidates) {
            if (candidate.suggestionId in dismissedIds) continue
            val candTokens = AiSnapshotCache.normalizeSemanticTokens(candidate.title)
            val duplicatesOpen = activeOpen.any {
                it.title.trim().equals(candidate.title.trim(), ignoreCase = true)
            } || openTitleTokens.any { existing ->
                AiSnapshotCache.jaccardSimilarity(candTokens, existing) >= 0.80
            }
            val duplicatesAccepted = accepted.any {
                it.suggestionId == candidate.suggestionId ||
                    it.title.equals(candidate.title, ignoreCase = true)
            }
            if (!duplicatesOpen && !duplicatesAccepted) {
                accepted += candidate
            }
            if (accepted.size >= MAX_NEXT_TASK_SUGGESTIONS) break
        }

        return accepted
    }

    private fun buildCandidate(
        title: String,
        categoryId: String?,
        priority: Priority,
        dueAt: Long?,
        reason: String
    ): SuggestedTaskCandidate {
        val cleanTitle = sentenceCase(title.trim()).take(MAX_STEP_CHARS)
        return SuggestedTaskCandidate(
            suggestionId = SuggestedTaskCandidate.computeId(cleanTitle, categoryId),
            title = cleanTitle,
            description = null,
            categoryId = categoryId,
            priority = priority,
            dueAt = dueAt,
            reason = reason.trim().take(MAX_REASON_CHARS)
        )
    }

    private fun deriveFollowUpFromCompleted(completedTitle: String): String? {
        val trimmed = completedTitle.trim()
        if (trimmed.length < 4) return null
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("draft ") -> "Review and share ${trimmed.drop(6)}"
            lower.startsWith("write ") -> "Proofread and send ${trimmed.drop(6)}"
            lower.startsWith("prepare ") -> "Deliver or review ${trimmed.drop(8)}"
            lower.startsWith("outline ") -> "Draft full version of ${trimmed.drop(8)}"
            lower.startsWith("buy ") || lower.startsWith("order ") -> null
            matchesAny(lower, "meeting", "sync", "call", "interview", "retro") ->
                "Send follow-up action items from $trimmed"
            else -> null
        }
    }

    private fun extractExplicitClauses(title: String, description: String): List<String> {
        val source = if (description.contains(",") || description.contains("\n")) {
            description
        } else if (title.contains(",") && (" and " in title.lowercase())) {
            title
        } else {
            return emptyList()
        }

        return source
            .split(CLAUSE_SPLIT_REGEX)
            .map { it.replace(LEADING_BULLET_REGEX, "").trim() }
            .filter { it.length in 4..MAX_STEP_CHARS }
    }

    private fun matchesAny(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }

    private fun sentenceCase(text: String): String =
        text.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }

    companion object {
        const val MAX_SUBTASK_SUGGESTIONS = 6
        const val MAX_NEXT_TASK_SUGGESTIONS = 3
        private const val MAX_STEP_CHARS = 80
        private const val MAX_REASON_CHARS = 90
        private const val HOUR_MS = 60 * 60 * 1000L
        private val CLAUSE_SPLIT_REGEX = Regex("""(?:\r?\n|;|,\s+(?:and\s+)?|\s+and\s+)""", RegexOption.IGNORE_CASE)
        private val LEADING_BULLET_REGEX = Regex("""^[\-\*\u2022\d]+[\.\)\:\-\s]*""")
    }
}
