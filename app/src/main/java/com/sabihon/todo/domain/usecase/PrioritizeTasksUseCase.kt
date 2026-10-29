package com.sabihon.todo.domain.usecase

import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.telemetry.AiTelemetry
import com.sabihon.todo.core.telemetry.FirebaseAiTelemetry
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
    private val telemetry: AiTelemetry = AiTelemetry.NoOp
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
        telemetry: AiTelemetry
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

        val cloudEnabled = prefs.isCloudEnabledNow()
        if (forceRefresh) {
            quotaGuard.resetRuntimeState()
        } else if (cloudEnabled) {
            val cached = snapshotCache.get(uid, today, activeTasks)
            if (cached != null) {
                telemetry.logCall(
                    feature = FirebaseAiTelemetry.FEATURE_PRIORITIZE,
                    source = "cache",
                    status = "ok",
                    latencyMs = (clock() - nowMillis).coerceAtLeast(0L)
                )
                return cached
            }
        }

        val tier = router.chooseTier(activeTasks, categories, cloudEnabled)
        if (tier == AiModelTier.LOCAL) {
            telemetry.logCall(
                feature = FirebaseAiTelemetry.FEATURE_PRIORITIZE,
                source = "local",
                status = "ok",
                latencyMs = (clock() - nowMillis).coerceAtLeast(0L)
            )
            val note = if (!cloudEnabled) {
                "Smart Cloud AI is turned off in Settings."
            } else {
                "Add at least 2 open tasks for Gemini trade-off ranking."
            }
            return localPlan.copy(fallbackNote = note)
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
            enforceCooldown = !forceRefresh
        ) {
            callCloudWithSingleTransientRetry(promptItems, tier, uid)
        }

        return when (execution) {
            is AiQuotaGuard.ExecutionOutcome.Blocked -> {
                telemetry.logCall(
                    feature = FirebaseAiTelemetry.FEATURE_PRIORITIZE,
                    source = "local",
                    status = "blocked_${execution.reason.name.lowercase()}",
                    latencyMs = (clock() - nowMillis).coerceAtLeast(0L)
                )
                val blockedNote = when (execution.reason) {
                    AiQuotaGuard.BlockReason.DAILY_QUOTA_EXCEEDED ->
                        "Daily AI limit reached on every provider — using offline smart rules until tomorrow."
                    AiQuotaGuard.BlockReason.CLOUD_DISABLED ->
                        "Smart Cloud AI is turned off in Settings."
                    AiQuotaGuard.BlockReason.COOLDOWN,
                    AiQuotaGuard.BlockReason.IN_FLIGHT,
                    AiQuotaGuard.BlockReason.CIRCUIT_OPEN ->
                        "AI is cooling down briefly — tap ↻ to retry now."
                }
                localPlan.copy(fallbackNote = blockedNote)
            }
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
                    // Counting is the service's job: it alone knows whether Gemini or Claude
                    // answered, and the daily budget is per provider.
                    snapshotCache.put(uid, today, activeTasks, sanitized)
                    telemetry.logCall(
                        feature = FirebaseAiTelemetry.FEATURE_PRIORITIZE,
                        source = "cloud",
                        status = "ok",
                        latencyMs = (clock() - nowMillis).coerceAtLeast(0L)
                    )
                    sanitized
                } else {
                    val err = (cloudResult as? Result.Error)?.exception
                        ?: IllegalStateException("Prioritization timed out or returned invalid items")
                    quotaGuard.recordFailure(err)
                    telemetry.logCall(
                        feature = FirebaseAiTelemetry.FEATURE_PRIORITIZE,
                        source = "cloud",
                        status = "fallback",
                        latencyMs = (clock() - nowMillis).coerceAtLeast(0L)
                    )
                    localPlan.copy(fallbackNote = describeCloudFailure(cloudResult, err))
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
        tier: AiModelTier,
        uid: String?
    ): Result<CloudPrioritizationDraft>? = withTimeoutOrNull(TIMEOUT_MS) {
        val first = aiTaskService.prioritizeTasks(items, tier, uid)
        if (first is Result.Error && isTransientUnavailable(first.exception)) {
            delay(RETRY_DELAY_MS)
            aiTaskService.prioritizeTasks(items, tier, uid)
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

    private fun describeCloudFailure(
        cloudResult: Result<CloudPrioritizationDraft>?,
        error: Throwable
    ): String {
        if (cloudResult == null) {
            return "Gemini took too long to respond — tap ↻ to retry."
        }
        val raw = error.message.orEmpty()
        val lower = raw.lowercase()
        return when {
            "vertex ai in firebase" in lower ||
                "has not been used" in lower ||
                "service_disabled" in lower ||
                "api_not_activated" in lower ||
                "is disabled" in lower ||
                "not been enabled" in lower ->
                "Paste a free Gemini key from aistudio.google.com/apikey in Settings > AI > Gemini API key (or enable 'Vertex AI in Firebase API' in Google Cloud Console for sabihon-todo), then tap ↻."
            "app check" in lower || "appcheck" in lower || "attestation" in lower ->
                "App Check debug token needed — go to Settings > AI > tap 'App Check debug token' to copy it, add it in Firebase Console > App Check > Apps > Manage debug tokens, then tap ↻."
            "api_key_service_blocked" in lower ||
                "api_key_android_app_blocked" in lower ||
                "requests to this api" in lower ||
                "are blocked" in lower ->
                "API key restriction blocked Gemini — allow 'Firebase AI Logic API' & 'Generative Language API' (and your debug SHA-1) in Google Cloud Console > Credentials."
            "429" in lower || "quota" in lower || "resource_exhausted" in lower ->
                "Gemini free-tier rate limit reached — wait a moment, then tap ↻."
            "unable to resolve host" in lower || "network" in lower || "offline" in lower || "socket" in lower ->
                "Couldn't reach Gemini (check internet connection), then tap ↻."
            else -> {
                val brief = sanitizeLine(
                    raw.lineSequence().firstOrNull { it.isNotBlank() },
                    120
                ) ?: "request failed"
                "Gemini error ($brief) — check Firebase Console > Build > AI Logic, then tap ↻."
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
        private const val RETRY_DELAY_MS = 450L
        private const val BASE_PROMPT_CHARS = 1_100
        private const val MAX_REASON_CHARS = 90
        private const val MAX_SUMMARY_CHARS = 110
        private val URL_IN_OUTPUT_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val MARKDOWN_CHARS_REGEX = Regex("""[`*_#<>\[\]]""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
