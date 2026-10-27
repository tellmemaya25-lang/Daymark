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
import com.sabihon.todo.domain.ai.AiModelTier
import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.ai.AiTaskService
import com.sabihon.todo.domain.ai.CloudPrioritizationDraft
import com.sabihon.todo.domain.ai.PrioritizationPlan
import com.sabihon.todo.domain.ai.PromptTaskItem
import com.sabihon.todo.domain.ai.TaskPriorityRecommendation
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Task
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Hybrid AI Task Prioritization orchestrator (`docs/02-daymark-ai-system-design.md` §10.1).
 *
 * Pipeline:
 *  1. Compute Rung-0 deterministic plan (`LocalPriorityScorer`) so local scores and an
 *     instant fallback are always available.
 *  2. Check `uid`-namespaced `AiSnapshotCache` (unless [forceRefresh] is true).
 *  3. Route via `AiRouter` (`LOCAL` vs `FLASH_LITE` vs `FLASH`).
 *  4. Enforce `AiQuotaGuard` (opt-in gate, single-flight `Mutex`, cooldown, 25/day cap,
 *     60s circuit breaker).
 *  5. Scrub PII and bound prompt size via `AiInputGuard`, call Gemini inside a hard
 *     10-second timeout (with at most 1 retry on transient `503`), and sanitize output
 *     against the closed set of open `taskId`s.
 *  6. On any timeout, quota block, network drop, or malformed answer, return the
 *     deterministic Rung-0 plan (`AiSource.LOCAL_FALLBACK`) without throwing.
 */
class PrioritizeTasksUseCase internal constructor(
    private val aiTaskService: AiTaskService,
    private val prefs: AiPreferenceStore,
    private val scorer: LocalPriorityScorer,
    private val inputGuard: AiInputGuard,
    private val quotaGuard: AiQuotaGuard,
    private val snapshotCache: AiSnapshotCache,
    private val router: AiRouter,
    private val clock: () -> Long,
    private val todayProvider: () -> LocalDate,
    private val zoneProvider: () -> ZoneId,
    private val telemetry: AiTelemetryLogger = NoOpAiTelemetry
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
        telemetry: AiTelemetryLogger
    ) : this(
        aiTaskService = aiTaskService,
        prefs = prefs,
        scorer = scorer,
        inputGuard = inputGuard,
        quotaGuard = quotaGuard,
        snapshotCache = snapshotCache,
        router = router,
        clock = System::currentTimeMillis,
        todayProvider = LocalDate::now,
        zoneProvider = ZoneId::systemDefault,
        telemetry = telemetry
    )

    suspend operator fun invoke(
        uid: String?,
        tasks: List<Task>,
        categories: List<Category>,
        forceRefresh: Boolean = false
    ): PrioritizationPlan {
        val nowMillis = clock()
        val today = todayProvider()
        val zone = zoneProvider()
        val activeTasks = tasks.filter { !it.isCompleted && !it.isDeleted }
        val collisionWarning = router.detectScheduleCollision(activeTasks, nowMillis)

        val localPlan = scorer.buildLocalPlan(
            openTasks = activeTasks,
            nowMillis = nowMillis,
            today = today,
            zone = zone,
            collisionWarning = collisionWarning
        )
        if (activeTasks.isEmpty()) return localPlan

        if (!forceRefresh) {
            val cached = snapshotCache.get(uid, today, activeTasks)
            if (cached != null) return cached
        }

        val cloudEnabled = prefs.isCloudEnabledNow()
        val tier = router.chooseTier(activeTasks, categories, cloudEnabled)
        if (tier == AiModelTier.LOCAL) {
            snapshotCache.put(uid, today, activeTasks, localPlan)
            return localPlan
        }

        val promptItems = inputGuard.buildPromptItems(
            openTasks = activeTasks,
            categories = categories,
            scorer = scorer,
            nowMillis = nowMillis,
            today = today,
            zone = zone
        )
        val estimatedTokens = estimatePromptTokens(promptItems)

        val execution = quotaGuard.runIfPermitted(
            uid = uid,
            feature = AiFeature.PRIORITIZE,
            estimatedTokens = estimatedTokens,
            enforceCooldown = true
        ) {
            callCloudWithSingleTransientRetry(promptItems, tier)
        }

        return when (execution) {
            is AiQuotaGuard.ExecutionOutcome.Blocked -> localPlan
            is AiQuotaGuard.ExecutionOutcome.Executed -> {
                val cloudResult = execution.value
                val sanitized = (cloudResult as? Result.Success<CloudPrioritizationDraft>)?.data
                    ?.let { draft ->
                        sanitizeAndMerge(
                            draft = draft,
                            activeTasks = activeTasks,
                            localPlan = localPlan,
                            tier = tier,
                            collisionWarning = collisionWarning,
                            nowMillis = nowMillis
                        )
                    }

                if (sanitized != null) {
                    quotaGuard.recordSuccess(uid, estimatedTokens)
                    snapshotCache.put(uid, today, activeTasks, sanitized)
                    telemetry.logRequest(
                        feature = "prioritize_tasks",
                        modelTier = tier,
                        source = AiSource.CLOUD,
                        latencyMs = (clock() - nowMillis).coerceAtLeast(0L)
                    )
                    sanitized
                } else {
                    val err = (cloudResult as? Result.Error)?.exception
                        ?: IllegalStateException("Prioritization timed out or returned invalid items")
                    quotaGuard.recordFailure(err)
                    telemetry.logRequest(
                        feature = "prioritize_tasks",
                        modelTier = tier,
                        source = AiSource.LOCAL_FALLBACK,
                        latencyMs = (clock() - nowMillis).coerceAtLeast(0L),
                        errorType = "fallback"
                    )
                    localPlan
                }
            }
        }
    }

    /**
     * Hard 10-second wall-clock bound. Retries once only on transient `503` /
     * `"unavailable"`; never retries `429` or `403`.
     */
    private suspend fun callCloudWithSingleTransientRetry(
        items: List<PromptTaskItem>,
        tier: AiModelTier
    ): Result<CloudPrioritizationDraft>? = withTimeoutOrNull(TIMEOUT_MS) {
        val first = aiTaskService.prioritizeTasks(items, tier)
        if (first is Result.Error && isTransientUnavailable(first.exception)) {
            delay(RETRY_DELAY_MS)
            aiTaskService.prioritizeTasks(items, tier)
        } else {
            first
        }
    }

    private fun isTransientUnavailable(error: Throwable?): Boolean {
        val msg = error?.message.orEmpty().lowercase()
        if ("429" in msg || "403" in msg || "quota" in msg || "app check" in msg) return false
        return "503" in msg || "unavailable" in msg || "overloaded" in msg
    }

    /**
     * Output Guardrails (`AiOutputSanitizer` — `docs/02-daymark-ai-system-design.md` §3.2):
     *  - Drops any `taskId` not present in [activeTasks] (defeats hallucinated IDs).
     *  - Deduplicates repeated `taskId`s.
     *  - Caps `isTopFocus == true` at [LocalPriorityScorer.MAX_TOP_FOCUS] (3).
     *  - Strips newlines, markdown fences, and URLs from `reason` and summary.
     *  - Appends any open tasks omitted by the model (including tasks #26+) in
     *    deterministic `LocalPriorityScorer` order so `focusRank` covers `1..N`.
     */
    private fun sanitizeAndMerge(
        draft: CloudPrioritizationDraft,
        activeTasks: List<Task>,
        localPlan: PrioritizationPlan,
        tier: AiModelTier,
        collisionWarning: String?,
        nowMillis: Long
    ): PrioritizationPlan? {
        val openById = activeTasks.associateBy { it.id }
        val localById = localPlan.recommendations.associateBy { it.taskId }

        val validCloudItems = draft.items
            .filter { it.taskId in openById }
            .distinctBy { it.taskId }
            .sortedWith(
                compareBy<com.sabihon.todo.domain.ai.CloudPriorityItem> {
                    it.focusRank?.takeIf { r -> r > 0 } ?: Int.MAX_VALUE
                }.thenBy { localById[it.taskId]?.focusRank ?: Int.MAX_VALUE }
            )

        // Reject an empty or completely hallucinated response so the caller falls back cleanly.
        if (validCloudItems.isEmpty()) return null

        val coveredIds = validCloudItems.map { it.taskId }.toSet()
        val remainingLocal = localPlan.recommendations.filter { it.taskId !in coveredIds }

        var topFocusCount = 0
        val merged = mutableListOf<TaskPriorityRecommendation>()

        validCloudItems.forEach { cloudItem ->
            val task = openById.getValue(cloudItem.taskId)
            val fallbackRec = localById.getValue(cloudItem.taskId)
            val rank = merged.size + 1
            val allowTopFocus = (cloudItem.isTopFocus || rank <= 3 && draft.items.none { it.isTopFocus }) &&
                topFocusCount < LocalPriorityScorer.MAX_TOP_FOCUS &&
                rank <= LocalPriorityScorer.MAX_TOP_FOCUS
            if (allowTopFocus) topFocusCount += 1

            val cleanReason = sanitizeLine(cloudItem.reason, MAX_REASON_CHARS)
                ?: fallbackRec.reason

            merged += TaskPriorityRecommendation(
                taskId = task.id,
                currentPriority = task.priority,
                suggestedPriority = cloudItem.suggestedPriority ?: task.priority,
                focusRank = rank,
                isTopFocus = allowTopFocus,
                reason = cleanReason,
                localScore = fallbackRec.localScore
            )
        }

        remainingLocal.forEach { fallbackRec ->
            val rank = merged.size + 1
            val allowTopFocus = topFocusCount < LocalPriorityScorer.MAX_TOP_FOCUS && rank <= LocalPriorityScorer.MAX_TOP_FOCUS
            if (allowTopFocus) topFocusCount += 1
            merged += fallbackRec.copy(
                focusRank = rank,
                isTopFocus = allowTopFocus
            )
        }

        val cleanSummary = collisionWarning
            ?: sanitizeLine(draft.summaryHeadline, MAX_SUMMARY_CHARS)
            ?: localPlan.summaryHeadline

        return PrioritizationPlan(
            recommendations = merged,
            summaryHeadline = cleanSummary,
            source = AiSource.CLOUD,
            modelTier = tier,
            generatedAtMillis = nowMillis
        )
    }

    private fun sanitizeLine(raw: String?, maxChars: Int): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw
            .replace(URL_IN_OUTPUT_REGEX, "")
            .replace(MARKDOWN_CHARS_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .take(maxChars)
        return cleaned.takeIf { it.isNotEmpty() }
    }

    private fun estimatePromptTokens(items: List<PromptTaskItem>): Int {
        val dataChars = items.sumOf {
            it.id.length + it.title.length + (it.note?.length ?: 0) + (it.categoryName?.length ?: 0) + 28
        }
        return inputGuard.estimateTokens(
            promptChars = BASE_PROMPT_CHARS + dataChars,
            expectedOutputChars = items.size * 55 + 120
        )
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        private const val RETRY_DELAY_MS = 450L
        private const val BASE_PROMPT_CHARS = 1_100
        private const val MAX_REASON_CHARS = 90
        private const val MAX_SUMMARY_CHARS = 110
        private val URL_IN_OUTPUT_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val MARKDOWN_CHARS_REGEX = Regex("""[`*_#<>\[\]]""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
