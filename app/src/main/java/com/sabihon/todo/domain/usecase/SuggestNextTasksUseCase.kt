package com.sabihon.todo.domain.usecase

import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.telemetry.AiTelemetryLogger
import com.sabihon.todo.core.telemetry.NoOpAiTelemetry
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.AiFeature
import com.sabihon.todo.data.ai.AiInputGuard
import com.sabihon.todo.data.ai.AiQuotaGuard
import com.sabihon.todo.data.ai.AiRouter
import com.sabihon.todo.data.ai.AiSnapshotCache
import com.sabihon.todo.data.ai.LocalPriorityScorer
import com.sabihon.todo.data.ai.LocalSuggestionHeuristics
import com.sabihon.todo.domain.ai.AiModelTier
import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.ai.AiTaskService
import com.sabihon.todo.domain.ai.CloudSuggestedTaskItem
import com.sabihon.todo.domain.ai.PromptNextTaskContext
import com.sabihon.todo.domain.ai.SuggestedTaskCandidate
import com.sabihon.todo.domain.ai.TaskSuggestionsResult
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Phase 4 — Contextual next-task suggestion orchestrator (`docs/02-daymark-ai-system-design.md` §5.3 & §11.1):
 *
 *  1. Splits live tasks into open + recently completed and reads `uid`-scoped dismissed IDs.
 *  2. Checks [AiSnapshotCache.getNextTasks] (`2 h` TTL) and re-filters dismissed/open duplicates.
 *  3. Routes via [AiRouter] (`LOCAL` vs `FLASH_LITE` vs `FLASH`) and guards via [AiQuotaGuard].
 *  4. Enforces closed-world `categoryId` whitelisting, open-task deduplication, `1..720h`
 *     relative due-time clamping, and 3-item cap.
 *  5. Falls back seamlessly to [LocalSuggestionHeuristics.suggestNextTasks] on any failure.
 */
class SuggestNextTasksUseCase internal constructor(
    private val aiTaskService: AiTaskService,
    private val prefs: AiPreferenceStore,
    private val scorer: LocalPriorityScorer,
    private val inputGuard: AiInputGuard,
    private val quotaGuard: AiQuotaGuard,
    private val snapshotCache: AiSnapshotCache,
    private val router: AiRouter,
    private val localHeuristics: LocalSuggestionHeuristics,
    private val telemetry: AiTelemetryLogger = NoOpAiTelemetry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val todayProvider: () -> LocalDate = LocalDate::now,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault
) {

    @Inject
    constructor(
        aiTaskService: AiTaskService,
        prefs: AiPreferenceStore,
        scorer: LocalPriorityScorer,
        inputGuard: AiInputGuard,
        quotaGuard: AiQuotaGuard,
        snapshotCache: AiSnapshotCache,
        router: AiRouter,
        localHeuristics: LocalSuggestionHeuristics,
        telemetry: AiTelemetryLogger
    ) : this(
        aiTaskService = aiTaskService,
        prefs = prefs,
        scorer = scorer,
        inputGuard = inputGuard,
        quotaGuard = quotaGuard,
        snapshotCache = snapshotCache,
        router = router,
        localHeuristics = localHeuristics,
        telemetry = telemetry,
        clock = System::currentTimeMillis,
        todayProvider = LocalDate::now,
        zoneProvider = ZoneId::systemDefault
    )

    suspend operator fun invoke(
        uid: String?,
        allTasks: List<Task>,
        categories: List<Category>,
        targetCategoryId: String? = null,
        forceRefresh: Boolean = false
    ): TaskSuggestionsResult {
        val startedAt = clock()
        val nowMillis = startedAt
        val today = todayProvider()
        val zone = zoneProvider()

        val activeOpen = allTasks.filter { !it.isCompleted && !it.isDeleted }
        val completedTasks = allTasks.filter { it.isCompleted && !it.isDeleted }
        val dismissedIds = prefs.getDismissedSuggestionIds(uid)

        val fallbackCandidates = localHeuristics.suggestNextTasks(
            openTasks = activeOpen,
            completedTasks = completedTasks,
            categories = categories,
            targetCategoryId = targetCategoryId,
            dismissedIds = dismissedIds,
            nowMillis = nowMillis,
            today = today,
            zone = zone
        )

        if (!forceRefresh) {
            val cached = snapshotCache.getNextTasks(
                uid = uid,
                today = today,
                targetCategoryId = targetCategoryId,
                openTasks = activeOpen,
                completedTasks = completedTasks
            )
            if (cached != null) {
                val filtered = filterAgainstLiveState(cached.candidates, activeOpen, dismissedIds)
                if (filtered.isNotEmpty()) {
                    telemetry.logRequest(
                        feature = TELEMETRY_FEATURE,
                        modelTier = cached.modelTier,
                        source = AiSource.CACHE,
                        latencyMs = (clock() - startedAt).coerceAtLeast(0L)
                    )
                    return cached.copy(candidates = filtered, source = AiSource.CACHE)
                }
            }
        }

        val cloudEnabled = prefs.isCloudEnabledNow()
        val tier = router.chooseNextTaskTier(
            openTasks = activeOpen,
            completedTasks = completedTasks,
            categories = categories,
            targetCategoryId = targetCategoryId,
            cloudEnabled = cloudEnabled
        )
        if (tier == AiModelTier.LOCAL) {
            telemetry.logRequest(
                feature = TELEMETRY_FEATURE,
                modelTier = AiModelTier.LOCAL,
                source = AiSource.LOCAL_FALLBACK,
                latencyMs = (clock() - startedAt).coerceAtLeast(0L)
            )
            return TaskSuggestionsResult(
                candidates = fallbackCandidates,
                source = AiSource.LOCAL_FALLBACK,
                modelTier = AiModelTier.LOCAL
            )
        }

        val promptContext = inputGuard.buildNextTaskContext(
            openTasks = activeOpen,
            completedTasks = completedTasks,
            categories = categories,
            targetCategoryId = targetCategoryId,
            scorer = scorer,
            nowMillis = nowMillis,
            today = today,
            zone = zone
        )

        val estimatedTokens = estimateContextTokens(promptContext)
        val execution = quotaGuard.runIfPermitted(
            uid = uid,
            feature = AiFeature.SUGGEST_NEXT_TASKS,
            estimatedTokens = estimatedTokens,
            enforceCooldown = true
        ) {
            callCloudWithSingleTransientRetry(promptContext, tier)
        }

        return when (execution) {
            is AiQuotaGuard.ExecutionOutcome.Blocked -> {
                telemetry.logRequest(
                    feature = TELEMETRY_FEATURE,
                    modelTier = AiModelTier.LOCAL,
                    source = AiSource.LOCAL_FALLBACK,
                    latencyMs = (clock() - startedAt).coerceAtLeast(0L),
                    errorType = execution.reason.name.lowercase()
                )
                TaskSuggestionsResult(
                    candidates = fallbackCandidates,
                    source = AiSource.LOCAL_FALLBACK,
                    modelTier = AiModelTier.LOCAL
                )
            }
            is AiQuotaGuard.ExecutionOutcome.Executed -> {
                val cloudResult = execution.value
                val sanitized = (cloudResult as? Result.Success<List<CloudSuggestedTaskItem>>)?.data
                    ?.let { items ->
                        sanitizeCandidates(
                            rawItems = items,
                            activeOpen = activeOpen,
                            categories = categories,
                            targetCategoryId = targetCategoryId,
                            dismissedIds = dismissedIds,
                            nowMillis = nowMillis
                        )
                    }
                    ?.takeIf { it.isNotEmpty() }

                if (sanitized != null) {
                    quotaGuard.recordSuccess(uid, estimatedTokens)
                    val resolved = TaskSuggestionsResult(
                        candidates = sanitized,
                        source = AiSource.CLOUD,
                        modelTier = tier
                    )
                    snapshotCache.putNextTasks(
                        uid = uid,
                        today = today,
                        targetCategoryId = targetCategoryId,
                        openTasks = activeOpen,
                        completedTasks = completedTasks,
                        result = resolved
                    )
                    telemetry.logRequest(
                        feature = TELEMETRY_FEATURE,
                        modelTier = tier,
                        source = AiSource.CLOUD,
                        latencyMs = (clock() - startedAt).coerceAtLeast(0L)
                    )
                    resolved
                } else {
                    val err = (cloudResult as? Result.Error)?.exception
                        ?: IllegalStateException("Next-task suggestions timed out or returned no valid tasks")
                    quotaGuard.recordFailure(err)
                    telemetry.logRequest(
                        feature = TELEMETRY_FEATURE,
                        modelTier = tier,
                        source = AiSource.LOCAL_FALLBACK,
                        latencyMs = (clock() - startedAt).coerceAtLeast(0L),
                        errorType = classifyError(err)
                    )
                    TaskSuggestionsResult(
                        candidates = fallbackCandidates,
                        source = AiSource.LOCAL_FALLBACK,
                        modelTier = AiModelTier.LOCAL
                    )
                }
            }
        }
    }

    suspend fun dismiss(uid: String?, suggestionId: String) {
        if (suggestionId.isBlank()) return
        prefs.dismissSuggestion(uid, suggestionId)
        telemetry.logSuggestionAction(kind = "next_task", action = "dismissed", count = 1)
    }

    private suspend fun callCloudWithSingleTransientRetry(
        context: PromptNextTaskContext,
        tier: AiModelTier
    ): Result<List<CloudSuggestedTaskItem>>? = withTimeoutOrNull(TIMEOUT_MS) {
        val first = aiTaskService.suggestNextTasks(context, tier)
        if (first is Result.Error && isTransientUnavailable(first.exception)) {
            delay(RETRY_DELAY_MS)
            aiTaskService.suggestNextTasks(context, tier)
        } else {
            first
        }
    }

    private fun isTransientUnavailable(error: Throwable?): Boolean {
        val msg = error?.message.orEmpty().lowercase()
        if ("429" in msg || "403" in msg || "quota" in msg || "app check" in msg) return false
        return "503" in msg || "unavailable" in msg || "overloaded" in msg
    }

    private fun sanitizeCandidates(
        rawItems: List<CloudSuggestedTaskItem>,
        activeOpen: List<Task>,
        categories: List<Category>,
        targetCategoryId: String?,
        dismissedIds: Set<String>,
        nowMillis: Long
    ): List<SuggestedTaskCandidate> {
        val validCategoryIds = categories.map { it.id }.toSet()
        val scopedCategoryId = targetCategoryId?.takeIf { it in validCategoryIds }
        val openLowerTitles = activeOpen.map { it.title.trim().lowercase() }.toSet()
        val openTokenSets = activeOpen
            .map { AiSnapshotCache.normalizeSemanticTokens(it.title) }
            .filter { it.isNotEmpty() }

        val accepted = mutableListOf<SuggestedTaskCandidate>()
        val acceptedTokenSets = mutableListOf<Set<String>>()

        for (item in rawItems) {
            val cleanTitle = cleanLine(item.title, MAX_TITLE_CHARS, stripBullets = true) ?: continue
            if (cleanTitle.length < MIN_TITLE_CHARS) continue
            val sentenceTitle = cleanTitle.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase() else ch.toString()
            }
            if (sentenceTitle.lowercase() in openLowerTitles) continue

            val candidateTokens = AiSnapshotCache.normalizeSemanticTokens(sentenceTitle)
            if (candidateTokens.isNotEmpty()) {
                val overlapsOpen = openTokenSets.any {
                    AiSnapshotCache.jaccardSimilarity(candidateTokens, it) >= OPEN_TASK_OVERLAP_THRESHOLD
                }
                val overlapsAccepted = acceptedTokenSets.any {
                    AiSnapshotCache.jaccardSimilarity(candidateTokens, it) >= OPEN_TASK_OVERLAP_THRESHOLD
                }
                if (overlapsOpen || overlapsAccepted) continue
            }

            // Closed-world category whitelist: never permit a phantom categoryId.
            val resolvedCategoryId = scopedCategoryId
                ?: item.categoryId?.takeIf { it in validCategoryIds }

            val suggestionId = SuggestedTaskCandidate.computeId(sentenceTitle, resolvedCategoryId)
            if (suggestionId in dismissedIds) continue
            if (accepted.any { it.suggestionId == suggestionId || it.title.equals(sentenceTitle, ignoreCase = true) }) {
                continue
            }

            val cleanDescription = cleanLine(item.description, MAX_DESCRIPTION_CHARS, stripBullets = false)
            val cleanReason = cleanLine(item.reason, MAX_REASON_CHARS, stripBullets = false)
                ?: "Suggested from your active tasks and recent progress"

            val resolvedDueAt = item.dueOffsetHours?.let { rawHours ->
                val clampedHours = rawHours.coerceIn(MIN_DUE_OFFSET_HOURS, MAX_DUE_OFFSET_HOURS)
                nowMillis + clampedHours.toLong() * HOUR_MS
            }

            if (candidateTokens.isNotEmpty()) {
                acceptedTokenSets += candidateTokens
            }
            accepted += SuggestedTaskCandidate(
                suggestionId = suggestionId,
                title = sentenceTitle,
                description = cleanDescription,
                categoryId = resolvedCategoryId,
                priority = item.priority ?: Priority.MEDIUM,
                dueAt = resolvedDueAt,
                reason = cleanReason
            )

            if (accepted.size >= MAX_SUGGESTIONS) break
        }

        return accepted
    }

    private fun filterAgainstLiveState(
        candidates: List<SuggestedTaskCandidate>,
        activeOpen: List<Task>,
        dismissedIds: Set<String>
    ): List<SuggestedTaskCandidate> {
        val openLowerTitles = activeOpen.map { it.title.trim().lowercase() }.toSet()
        val openTokenSets = activeOpen
            .map { AiSnapshotCache.normalizeSemanticTokens(it.title) }
            .filter { it.isNotEmpty() }

        return candidates.filter { candidate ->
            if (candidate.suggestionId in dismissedIds) return@filter false
            if (candidate.title.trim().lowercase() in openLowerTitles) return@filter false
            val tokens = AiSnapshotCache.normalizeSemanticTokens(candidate.title)
            tokens.isEmpty() || openTokenSets.none {
                AiSnapshotCache.jaccardSimilarity(tokens, it) >= OPEN_TASK_OVERLAP_THRESHOLD
            }
        }
    }

    private fun cleanLine(raw: String?, maxChars: Int, stripBullets: Boolean): String? {
        if (raw.isNullOrBlank()) return null
        var cleaned = raw
            .replace(URL_IN_OUTPUT_REGEX, "")
            .replace(MARKDOWN_CHARS_REGEX, "")
        if (stripBullets) {
            cleaned = cleaned.replace(LEADING_BULLET_REGEX, "")
        }
        cleaned = cleaned
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .take(maxChars)
            .trim()
        return cleaned.takeIf { it.isNotEmpty() }
    }

    private fun estimateContextTokens(context: PromptNextTaskContext): Int {
        val openChars = context.openTasks.sumOf {
            it.id.length + it.title.length + (it.note?.length ?: 0) + (it.categoryName?.length ?: 0) + 24
        }
        val completedChars = context.recentlyCompletedTitles.sumOf { it.length + 4 }
        val categoryChars = context.categories.sumOf { it.id.length + it.name.length + 6 }
        return inputGuard.estimateTokens(
            promptChars = BASE_PROMPT_CHARS + openChars + completedChars + categoryChars,
            expectedOutputChars = 480
        )
    }

    private fun classifyError(error: Throwable?): String {
        val msg = error?.message.orEmpty().lowercase()
        return when {
            "429" in msg || "quota" in msg || "resource_exhausted" in msg -> "rate_limit_429"
            "403" in msg || "app check" in msg || "permission_denied" in msg -> "app_check_403"
            "timeout" in msg -> "timeout"
            else -> "fallback"
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val MAX_SUGGESTIONS = 3
        const val MIN_DUE_OFFSET_HOURS = 1
        const val MAX_DUE_OFFSET_HOURS = 30 * 24
        private const val MIN_TITLE_CHARS = 3
        private const val MAX_TITLE_CHARS = 80
        private const val MAX_DESCRIPTION_CHARS = 160
        private const val MAX_REASON_CHARS = 90
        private const val BASE_PROMPT_CHARS = 950
        private const val RETRY_DELAY_MS = 450L
        private const val HOUR_MS = 3_600_000L
        private const val OPEN_TASK_OVERLAP_THRESHOLD = 0.80
        private const val TELEMETRY_FEATURE = "suggest_next_tasks"

        private val LEADING_BULLET_REGEX = Regex("""^[\-\*\u2022\d]+[\.\)\:\-\s]*""")
        private val URL_IN_OUTPUT_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val MARKDOWN_CHARS_REGEX = Regex("""[`*_#<>\[\]]""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
