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
import com.sabihon.todo.data.ai.LocalSuggestionHeuristics
import com.sabihon.todo.domain.ai.AiModelTier
import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.ai.AiTaskService
import com.sabihon.todo.domain.ai.PromptSubTaskRequest
import com.sabihon.todo.domain.ai.SubTaskSuggestionsResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Phase 3 — Intra-task checklist breakdown orchestrator (`docs/02-daymark-ai-system-design.md` §10.2):
 *
 *  1. Checks semantic Jaccard cache (`>= 0.85`) in [AiSnapshotCache] and filters against
 *     live [existingSubTasks].
 *  2. Routes via [AiRouter] (`LOCAL` vs `FLASH_LITE`).
 *  3. Enforces [AiQuotaGuard] (`Mutex`, 15s cooldown, 25/day cap, 60s circuit breaker).
 *  4. Scrubs PII via [AiInputGuard], calls Gemini within 10s (1 retry on transient `503`),
 *     strips bullets/markdown/URLs, deduplicates against [existingSubTasks], and caps at 6 steps.
 *  5. Falls back deterministically to [LocalSuggestionHeuristics] on any failure or offline state.
 */
class SuggestSubTasksUseCase internal constructor(
    private val aiTaskService: AiTaskService,
    private val prefs: AiPreferenceStore,
    private val inputGuard: AiInputGuard,
    private val quotaGuard: AiQuotaGuard,
    private val snapshotCache: AiSnapshotCache,
    private val router: AiRouter,
    private val localHeuristics: LocalSuggestionHeuristics,
    private val telemetry: AiTelemetryLogger = NoOpAiTelemetry,
    private val clock: () -> Long = System::currentTimeMillis
) {

    @Inject
    constructor(
        aiTaskService: AiTaskService,
        prefs: AiPreferenceStore,
        inputGuard: AiInputGuard,
        quotaGuard: AiQuotaGuard,
        snapshotCache: AiSnapshotCache,
        router: AiRouter,
        localHeuristics: LocalSuggestionHeuristics,
        telemetry: AiTelemetryLogger
    ) : this(
        aiTaskService = aiTaskService,
        prefs = prefs,
        inputGuard = inputGuard,
        quotaGuard = quotaGuard,
        snapshotCache = snapshotCache,
        router = router,
        localHeuristics = localHeuristics,
        telemetry = telemetry,
        clock = System::currentTimeMillis
    )

    suspend operator fun invoke(
        uid: String?,
        title: String,
        description: String = "",
        existingSubTasks: List<String> = emptyList(),
        forceRefresh: Boolean = false
    ): SubTaskSuggestionsResult {
        val startedAt = clock()
        val trimmedTitle = title.trim()
        if (trimmedTitle.length < AiRouter.MIN_SUBTASK_TITLE_LENGTH) {
            return SubTaskSuggestionsResult(
                steps = emptyList(),
                source = AiSource.LOCAL_FALLBACK,
                modelTier = AiModelTier.LOCAL
            )
        }

        val fallbackSteps = localHeuristics.suggestSubTasks(
            title = trimmedTitle,
            description = description,
            existingSubTasks = existingSubTasks
        )

        if (!forceRefresh) {
            val cached = snapshotCache.getSubTasks(uid, trimmedTitle, description)
            if (cached != null) {
                val filteredCached = sanitizeSteps(cached, trimmedTitle, existingSubTasks)
                if (filteredCached.isNotEmpty()) {
                    telemetry.logRequest(
                        feature = TELEMETRY_FEATURE,
                        modelTier = AiModelTier.LOCAL,
                        source = AiSource.CACHE,
                        latencyMs = (clock() - startedAt).coerceAtLeast(0L)
                    )
                    return SubTaskSuggestionsResult(
                        steps = filteredCached,
                        source = AiSource.CACHE,
                        modelTier = AiModelTier.LOCAL
                    )
                }
            }
        }

        val cloudEnabled = prefs.isCloudEnabledNow()
        val tier = router.chooseSubTaskTier(trimmedTitle, cloudEnabled)
        if (tier == AiModelTier.LOCAL) {
            telemetry.logRequest(
                feature = TELEMETRY_FEATURE,
                modelTier = AiModelTier.LOCAL,
                source = AiSource.LOCAL_FALLBACK,
                latencyMs = (clock() - startedAt).coerceAtLeast(0L)
            )
            return SubTaskSuggestionsResult(
                steps = fallbackSteps,
                source = AiSource.LOCAL_FALLBACK,
                modelTier = AiModelTier.LOCAL
            )
        }

        val promptRequest = inputGuard.buildSubTaskRequest(trimmedTitle, description, existingSubTasks)
        if (promptRequest.title.isBlank()) {
            return SubTaskSuggestionsResult(
                steps = fallbackSteps,
                source = AiSource.LOCAL_FALLBACK,
                modelTier = AiModelTier.LOCAL
            )
        }

        val estimatedTokens = inputGuard.estimateTokens(
            promptChars = BASE_PROMPT_CHARS + promptRequest.title.length +
                (promptRequest.description?.length ?: 0) +
                promptRequest.existingSubTasks.sumOf { it.length },
            expectedOutputChars = 320
        )

        val execution = quotaGuard.runIfPermitted(
            uid = uid,
            feature = AiFeature.SUGGEST_SUBTASKS,
            estimatedTokens = estimatedTokens,
            enforceCooldown = true
        ) {
            callCloudWithSingleTransientRetry(promptRequest, tier)
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
                SubTaskSuggestionsResult(
                    steps = fallbackSteps,
                    source = AiSource.LOCAL_FALLBACK,
                    modelTier = AiModelTier.LOCAL
                )
            }
            is AiQuotaGuard.ExecutionOutcome.Executed -> {
                val cloudResult = execution.value
                val sanitized = (cloudResult as? Result.Success<List<String>>)?.data
                    ?.let { rawSteps -> sanitizeSteps(rawSteps, trimmedTitle, existingSubTasks) }
                    ?.takeIf { it.isNotEmpty() }

                if (sanitized != null) {
                    quotaGuard.recordSuccess(uid, estimatedTokens)
                    // Store the full sanitized step set keyed by title+description so future
                    // edits or similar tasks hit the semantic Jaccard cache.
                    snapshotCache.putSubTasks(uid, trimmedTitle, description, sanitized)
                    telemetry.logRequest(
                        feature = TELEMETRY_FEATURE,
                        modelTier = tier,
                        source = AiSource.CLOUD,
                        latencyMs = (clock() - startedAt).coerceAtLeast(0L)
                    )
                    SubTaskSuggestionsResult(
                        steps = sanitized,
                        source = AiSource.CLOUD,
                        modelTier = tier
                    )
                } else {
                    val err = (cloudResult as? Result.Error)?.exception
                        ?: IllegalStateException("Sub-task suggestion timed out or returned no valid steps")
                    quotaGuard.recordFailure(err)
                    telemetry.logRequest(
                        feature = TELEMETRY_FEATURE,
                        modelTier = tier,
                        source = AiSource.LOCAL_FALLBACK,
                        latencyMs = (clock() - startedAt).coerceAtLeast(0L),
                        errorType = classifyError(err)
                    )
                    SubTaskSuggestionsResult(
                        steps = fallbackSteps,
                        source = AiSource.LOCAL_FALLBACK,
                        modelTier = AiModelTier.LOCAL
                    )
                }
            }
        }
    }

    private suspend fun callCloudWithSingleTransientRetry(
        request: PromptSubTaskRequest,
        tier: AiModelTier
    ): Result<List<String>>? = withTimeoutOrNull(TIMEOUT_MS) {
        val first = aiTaskService.suggestSubTasks(request, tier)
        if (first is Result.Error && isTransientUnavailable(first.exception)) {
            delay(RETRY_DELAY_MS)
            aiTaskService.suggestSubTasks(request, tier)
        } else {
            first
        }
    }

    private fun isTransientUnavailable(error: Throwable?): Boolean {
        val msg = error?.message.orEmpty().lowercase()
        if ("429" in msg || "403" in msg || "quota" in msg || "app check" in msg) return false
        return "503" in msg || "unavailable" in msg || "overloaded" in msg
    }

    private fun sanitizeSteps(
        rawSteps: List<String>,
        taskTitle: String,
        existingSubTasks: List<String>
    ): List<String> {
        val normalizedTitle = taskTitle.trim().lowercase()
        val existingLower = existingSubTasks.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val existingTokenSets = existingSubTasks
            .map { AiSnapshotCache.normalizeSemanticTokens(it) }
            .filter { it.isNotEmpty() }

        val accepted = mutableListOf<String>()
        val acceptedTokenSets = mutableListOf<Set<String>>()

        for (raw in rawSteps) {
            val cleaned = raw
                .replace(URL_IN_OUTPUT_REGEX, "")
                .replace(MARKDOWN_CHARS_REGEX, "")
                .replace(LEADING_BULLET_REGEX, "")
                .replace(WHITESPACE_REGEX, " ")
                .trim()
                .take(MAX_STEP_CHARS)
                .trim()

            if (cleaned.length < MIN_STEP_CHARS) continue
            val sentenceCased = cleaned.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase() else ch.toString()
            }
            val candidateLower = sentenceCased.lowercase()
            if (candidateLower == normalizedTitle) continue
            if (candidateLower in existingLower) continue
            if (accepted.any { it.equals(sentenceCased, ignoreCase = true) }) continue

            val candidateTokens = AiSnapshotCache.normalizeSemanticTokens(sentenceCased)
            if (candidateTokens.size >= 2) {
                val nearDupExisting = existingTokenSets.any {
                    AiSnapshotCache.jaccardSimilarity(candidateTokens, it) >= DEDUP_JACCARD_THRESHOLD
                }
                val nearDupBatch = acceptedTokenSets.any {
                    AiSnapshotCache.jaccardSimilarity(candidateTokens, it) >= DEDUP_JACCARD_THRESHOLD
                }
                if (nearDupExisting || nearDupBatch) continue
                acceptedTokenSets += candidateTokens
            }

            accepted += sentenceCased
            if (accepted.size >= MAX_STEPS) break
        }

        return accepted
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
        const val MAX_STEPS = 6
        const val MAX_STEP_CHARS = 80
        private const val MIN_STEP_CHARS = 3
        private const val BASE_PROMPT_CHARS = 720
        private const val RETRY_DELAY_MS = 450L
        private const val DEDUP_JACCARD_THRESHOLD = 0.88
        private const val TELEMETRY_FEATURE = "suggest_subtasks"

        private val LEADING_BULLET_REGEX = Regex("""^(?:step\s*\d+[\.\:\)\-\s]+|[\-\*\u2022\d]+[\.\)\:\-\s]+)""", RegexOption.IGNORE_CASE)
        private val URL_IN_OUTPUT_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val MARKDOWN_CHARS_REGEX = Regex("""[`*_#<>\[\]]""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
