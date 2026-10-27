package com.sabihon.todo.domain.usecase

import android.util.Log
import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.telemetry.AiTelemetry
import com.sabihon.todo.core.telemetry.FirebaseAiTelemetry
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.AiFeature
import com.sabihon.todo.data.ai.AiQuotaGuard
import com.sabihon.todo.data.ai.LocalSuggestionHeuristics
import com.sabihon.todo.domain.ai.AiTaskService
import com.sabihon.todo.domain.ai.SuggestedTaskCandidate
import com.sabihon.todo.domain.ai.SuggestionContext
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Task
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Up to three things worth doing next, and where the answer came from.
 *
 * [message] is set only when there is nothing to show and the reason is the user's to
 * act on. "Nothing to suggest" is not a failure - an empty, calm card is the correct
 * answer for someone with a tidy list.
 */
data class NextTaskSuggestions(
    val candidates: List<SuggestedTaskCandidate> = emptyList(),
    val source: Source = Source.LOCAL,
    val message: String? = null
) {
    enum class Source { CLOUD, LOCAL }
}

/**
 * "Suggested next steps ✨" (`docs/ai-task-prioritization-and-suggestions-plan.md`
 * Phase 4).
 *
 * The order of operations is the contract:
 *
 *  1. a small, privacy-safe [SuggestionContext] is assembled - titles only, never ids,
 *     notes or timestamps;
 *  2. cloud is asked only when it is switched on and the quota guard allows it, and a
 *     blocked or failed call falls back to [LocalSuggestionHeuristics] rather than
 *     giving up;
 *  3. whatever answered is held to the same rules: the list id must exist, a candidate
 *     the user already has open is dropped, a muted id stays dropped, the count is
 *     capped at three and the due offset is clamped;
 *  4. nothing is written. A suggestion becomes a task only when the user says so.
 */
class SuggestNextTasksUseCase private constructor(
    private val aiTaskService: AiTaskService,
    private val heuristics: LocalSuggestionHeuristics,
    private val telemetry: AiTelemetry,
    private val cloud: CloudGate?
) {

    /** Dagger's way in: the cloud half is always present in the app. */
    @Inject
    constructor(
        aiTaskService: AiTaskService,
        heuristics: LocalSuggestionHeuristics,
        quotaGuard: AiQuotaGuard,
        prefs: AiPreferenceStore,
        telemetry: AiTelemetry
    ) : this(
        aiTaskService = aiTaskService,
        heuristics = heuristics,
        telemetry = telemetry,
        cloud = CloudGate(quotaGuard, prefs)
    )

    /** For unit tests that fake the service and the splitter. */
    constructor(
        aiTaskService: AiTaskService,
        heuristics: LocalSuggestionHeuristics
    ) : this(
        aiTaskService = aiTaskService,
        heuristics = heuristics,
        telemetry = AiTelemetry.NoOp,
        cloud = null
    )

    /**
     * Cloud dependencies bundled into one nullable parameter - the same shape as
     * `ParseQuickAddUseCase`, and for the same reason: separate nullable parameters
     * erase to identical JVM signatures and the compiler rejects the pair.
     */
    internal class CloudGate(
        val quotaGuard: AiQuotaGuard,
        val prefs: AiPreferenceStore
    )

    suspend operator fun invoke(
        openTasks: List<Task>,
        recentCompleted: List<Task>,
        categories: List<Category>,
        uid: String? = null,
        forceInteractive: Boolean = false
    ): NextTaskSuggestions {
        val open = openTasks.filter { !it.isCompleted && !it.isDeleted }
        val completed = recentCompleted.filter { it.isCompleted && !it.isDeleted }
        val dismissed = cloud?.prefs?.getDismissedSuggestionIds().orEmpty()

        val gate = cloud
        if (gate != null && !gate.prefs.isCloudEnabledNow()) {
            Log.i(TAG, "next: cloud AI is switched off, using the local heuristics")
            return local(open, completed, categories, dismissed, message = null, interactive = forceInteractive)
        }

        val context = buildContext(open, completed, categories)
        if (context.openTitles.isEmpty() && context.recentCompletedTitles.isEmpty()) {
            return if (forceInteractive) {
                local(open, completed, categories, dismissed, message = null, interactive = true)
            } else {
                NextTaskSuggestions(message = "Nothing to suggest from yet - add a task or two")
            }
        }

        val startedAt = System.currentTimeMillis()
        val estimatedTokens = estimateTokens(context)
        val cloudResult: Result<List<SuggestedTaskCandidate>>? = if (gate != null) {
            when (
                val outcome = gate.quotaGuard.runIfPermitted(
                    uid = uid,
                    feature = AiFeature.SUGGEST_NEXT,
                    estimatedTokens = estimatedTokens,
                    enforceCooldown = !forceInteractive
                ) {
                    withTimeoutOrNull(TIMEOUT_MS) { aiTaskService.suggestNextTasks(context) }
                }
            ) {
                is AiQuotaGuard.ExecutionOutcome.Blocked -> {
                    Log.w(TAG, "next: blocked by quota guard (${outcome.reason})")
                    telemetry.logCall(
                        FirebaseAiTelemetry.FEATURE_SUGGEST_NEXT,
                        source = "cloud",
                        status = "blocked_${outcome.reason.name.lowercase()}",
                        latencyMs = System.currentTimeMillis() - startedAt
                    )
                    return local(open, completed, categories, dismissed, message = null, interactive = forceInteractive)
                }

                is AiQuotaGuard.ExecutionOutcome.Executed -> {
                    val res = outcome.value
                    if (res is Result.Success) {
                        gate.quotaGuard.recordSuccess(uid, estimatedTokens)
                    } else {
                        Log.w(TAG, "next: the model call failed", (res as? Result.Error)?.exception)
                        gate.quotaGuard.recordFailure((res as? Result.Error)?.exception)
                    }
                    telemetry.logCall(
                        FirebaseAiTelemetry.FEATURE_SUGGEST_NEXT,
                        source = "cloud",
                        status = if (res is Result.Success) "ok" else "error",
                        latencyMs = System.currentTimeMillis() - startedAt
                    )
                    res
                }
            }
        } else {
            withTimeoutOrNull(TIMEOUT_MS) { aiTaskService.suggestNextTasks(context) }
        }

        val raw = (cloudResult as? Result.Success<List<SuggestedTaskCandidate>>)?.data.orEmpty()
        val cleaned = clean(raw, open, categories, dismissed)
        if (cleaned.isEmpty()) {
            Log.i(TAG, "next: nothing usable from the model, using the local heuristics")
            return local(open, completed, categories, dismissed, message = null, interactive = forceInteractive)
        }

        Log.i(TAG, "next: the model suggested ${cleaned.size}")
        return NextTaskSuggestions(candidates = cleaned, source = NextTaskSuggestions.Source.CLOUD)
    }

    private fun local(
        open: List<Task>,
        completed: List<Task>,
        categories: List<Category>,
        dismissed: Set<String>,
        message: String?,
        interactive: Boolean = false
    ): NextTaskSuggestions {
        val rawCandidates = if (interactive) {
            heuristics.suggestInteractive(open, completed, categories)
        } else {
            heuristics.suggest(open, completed, categories)
        }
        val candidates = clean(
            raw = rawCandidates,
            open = open,
            categories = categories,
            dismissed = dismissed
        )
        return NextTaskSuggestions(
            candidates = candidates,
            source = NextTaskSuggestions.Source.LOCAL,
            message = if (candidates.isEmpty()) message else null
        )
    }

    /**
     * The rules every source is held to.
     *
     * Exposed for tests: this is where a hallucinated list id, a duplicate of an open
     * task, a muted suggestion and an absurd due date all get stopped.
     */
    internal fun clean(
        raw: List<SuggestedTaskCandidate>,
        open: List<Task>,
        categories: List<Category>,
        dismissed: Set<String>,
        limit: Int = MAX_SUGGESTIONS
    ): List<SuggestedTaskCandidate> {
        val validCategoryIds = categories.mapTo(mutableSetOf()) { it.id }
        val openTitles = open.mapTo(mutableSetOf()) { it.title.normalized() }
        val seenTitles = mutableSetOf<String>()

        return raw.asSequence()
            .filter { it.suggestionId.isNotBlank() && it.suggestionId !in dismissed }
            .filter { it.title.isNotBlank() }
            .map { candidate ->
                candidate.copy(
                    title = candidate.title.trim().take(MAX_TITLE_CHARS),
                    // A list id the user does not have is worse than none: it would
                    // create a task pointing at a list that cannot be opened.
                    categoryId = candidate.categoryId?.takeIf { it in validCategoryIds },
                    dueOffsetHours = candidate.dueOffsetHours?.coerceIn(0, MAX_DUE_OFFSET_HOURS),
                    reason = candidate.reason.trim().take(MAX_REASON_CHARS)
                )
            }
            .filter { it.title.normalized() !in openTitles }
            .filter { seenTitles.add(it.title.normalized()) }
            .take(limit)
            .toList()
    }

    /**
     * Titles only: no ids, no notes, nothing that could be pasted into a prompt to name
     * a document. Ten open tasks is enough for the model to see the shape of the list
     * without the whole backlog going over the wire.
     */
    private fun buildContext(
        open: List<Task>,
        completed: List<Task>,
        categories: List<Category>
    ): SuggestionContext {
        val openTitles = open.asSequence()
            .sortedWith(
                compareByDescending<Task> { it.dueAt != null }
                    .thenBy { it.dueAt ?: Long.MAX_VALUE }
            )
            .map { it.title.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_CONTEXT_OPEN)
            .toList()

        val completedTitles = completed.asSequence()
            .sortedByDescending { it.completedAt ?: it.updatedAt }
            .map { it.title.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_CONTEXT_COMPLETED)
            .toList()

        return SuggestionContext(
            openTitles = openTitles,
            recentCompletedTitles = completedTitles,
            categories = categories
        )
    }

    private fun estimateTokens(context: SuggestionContext): Int {
        val chars = PROMPT_OVERHEAD_CHARS +
            context.openTitles.sumOf { it.length } +
            context.recentCompletedTitles.sumOf { it.length } +
            context.categories.sumOf { it.name.length + 8 }
        return (chars / CHARS_PER_TOKEN) + EXPECTED_OUTPUT_TOKENS
    }

    private fun String.normalized(): String = trim().lowercase().replace(WHITESPACE, " ")

    private companion object {
        const val TAG = "SuggestNext"
        const val TIMEOUT_MS = 15_000L

        const val MAX_SUGGESTIONS = 3
        const val MAX_TITLE_CHARS = 60
        const val MAX_REASON_CHARS = 60

        /** Two weeks: past that a suggested due date is a guess about someone's diary. */
        const val MAX_DUE_OFFSET_HOURS = 14 * 24

        const val MAX_CONTEXT_OPEN = 10
        const val MAX_CONTEXT_COMPLETED = 8

        const val PROMPT_OVERHEAD_CHARS = 1_400
        const val CHARS_PER_TOKEN = 4
        const val EXPECTED_OUTPUT_TOKENS = 220

        val WHITESPACE = Regex("""\s+""")
    }
}
