package com.sabihon.todo.domain.usecase

import android.util.Log
import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.AiFeature
import com.sabihon.todo.data.ai.AiQuotaGuard
import com.sabihon.todo.data.ai.LocalSubTaskHeuristics
import com.sabihon.todo.domain.ai.AiTaskService
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Steps offered for one task, plus an honest account of where they came from.
 *
 * [message] is set when there are no steps and the reason is worth saying out loud. The
 * three reasons are kept apart on purpose: "Smart AI is off" is a settings problem the
 * user fixes in Settings, "AI didn't answer" is a network problem they can retry, and
 * "no obvious steps" means the model looked and found nothing - one label for all three
 * is what made the quick-add fallback impossible to diagnose.
 */
data class SubTaskSuggestions(
    val steps: List<String> = emptyList(),
    val source: Source = Source.LOCAL,
    val message: String? = null
) {
    enum class Source { CLOUD, CACHE, LOCAL }
}

/**
 * "Suggest steps ✨" - the rules, in order (`docs/ai-task-prioritization-and-suggestions-plan.md`
 * Phase 3):
 *
 *  1. a title with no words has nothing to break down;
 *  2. a semantically similar title answered in the last few minutes is reused (Jaccard
 *     >= 0.85 over normalized word sets), because re-phrasing "buy milk" as "milk" is
 *     not a new question;
 *  3. cloud off, quota spent, circuit open or a failed call all fall back to the offline
 *     splitter, and the fallback is labelled;
 *  4. whatever comes back - cloud or cache - is cleaned, de-duplicated against the steps
 *     the user already has, capped and ordered here, so no implementation gets to break
 *     the display rules;
 *  5. nothing is ever written. The user accepts steps one at a time or all at once.
 */
class SuggestSubTasksUseCase private constructor(
    private val aiTaskService: AiTaskService,
    private val heuristics: LocalSubTaskHeuristics,
    /** The rate limiter and the cloud switch, absent in unit tests that fake the service. */
    private val cloud: CloudGate?
) {

    /** Dagger's way in: the cloud half is always present in the app. */
    @Inject
    constructor(
        aiTaskService: AiTaskService,
        heuristics: LocalSubTaskHeuristics,
        quotaGuard: AiQuotaGuard,
        prefs: AiPreferenceStore
    ) : this(
        aiTaskService = aiTaskService,
        heuristics = heuristics,
        cloud = CloudGate(quotaGuard, prefs)
    )

    /** For unit tests that wire only the service and the splitter. */
    constructor(
        aiTaskService: AiTaskService,
        heuristics: LocalSubTaskHeuristics
    ) : this(
        aiTaskService = aiTaskService,
        heuristics = heuristics,
        cloud = null
    )

    /**
     * Both cloud-only dependencies in one nullable parameter.
     *
     * The same reason `ParseQuickAddUseCase` bundles them: Kotlin erases `AiQuotaGuard?`
     * to `AiQuotaGuard`, so two constructors differing only in nullability have one JVM
     * signature between them and the compiler rejects the pair.
     */
    internal class CloudGate(
        val quotaGuard: AiQuotaGuard,
        val prefs: AiPreferenceStore
    )

    /** Same-key answers, oldest first. Small on purpose: this is a cache, not a store. */
    private data class CachedSuggestions(
        val words: Set<String>,
        val steps: List<String>
    )

    private val cacheLock = Any()
    private val cache = ArrayDeque<CachedSuggestions>()

    suspend operator fun invoke(
        title: String,
        description: String?,
        existingSubTasks: List<String>,
        uid: String? = null
    ): SubTaskSuggestions {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            return SubTaskSuggestions(message = "Give the task a name first")
        }

        val existing = existingSubTasks.map { it.trim() }.filter { it.isNotEmpty() }

        // 1. Cache. A paraphrased title is the same question, so it gets the same answer
        // without spending a call - and the answer is re-filtered, because the user may
        // have added one of those steps since it was cached.
        cached(cleanTitle)?.let { hit ->
            val steps = clean(hit, existing)
            if (steps.isNotEmpty()) {
                Log.i(TAG, "steps: served ${steps.size} from cache")
                return SubTaskSuggestions(steps = steps, source = SubTaskSuggestions.Source.CACHE)
            }
        }

        val gate = cloud
        if (gate != null && !gate.prefs.isCloudEnabledNow()) {
            Log.i(TAG, "steps: cloud AI is switched off, using the offline splitter")
            return offline(
                title = cleanTitle,
                description = description,
                existing = existing,
                messageWhenEmpty = "Smart AI is off - turn it on in Settings for suggested steps",
                noteWhenFallback = "Suggested offline (turn on Smart AI in Settings for suggested steps)"
            )
        }

        val estimatedTokens = estimateTokens(cleanTitle, description)
        val cloudResult: Result<List<String>>? = if (gate != null) {
            when (
                val outcome = gate.quotaGuard.runIfPermitted(
                    uid = uid,
                    feature = AiFeature.SUB_TASKS,
                    estimatedTokens = estimatedTokens,
                    enforceCooldown = false
                ) {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        aiTaskService.suggestSubTasks(cleanTitle, description, existing, uid)
                    }
                }
            ) {
                is AiQuotaGuard.ExecutionOutcome.Blocked -> {
                    Log.w(TAG, "steps: blocked by quota guard (${outcome.reason})")
                    val message = when (outcome.reason) {
                        AiQuotaGuard.BlockReason.DAILY_QUOTA_EXCEEDED ->
                            "Daily AI limit reached - showing offline steps"
                        AiQuotaGuard.BlockReason.COOLDOWN,
                        AiQuotaGuard.BlockReason.IN_FLIGHT ->
                            "Give it a moment, then ask again"
                        AiQuotaGuard.BlockReason.CIRCUIT_OPEN ->
                            "Suggested offline while Cloud AI reconnects"
                        AiQuotaGuard.BlockReason.CLOUD_DISABLED ->
                            "Suggested offline (turn on Smart AI in Settings for suggested steps)"
                    }
                    return offline(
                        title = cleanTitle,
                        description = description,
                        existing = existing,
                        messageWhenEmpty = message,
                        noteWhenFallback = message
                    )
                }

                is AiQuotaGuard.ExecutionOutcome.Executed -> {
                    val res = outcome.value
                    // The service counts the call, because only it knows which provider
                    // answered and the daily budget is per provider.
                    if (res is Result.Error) {
                        Log.w(TAG, "steps: the model call failed", res.exception)
                        gate.quotaGuard.recordFailure(res.exception)
                    }
                    res
                }
            }
        } else {
            withTimeoutOrNull(TIMEOUT_MS) {
                aiTaskService.suggestSubTasks(cleanTitle, description, existing, uid)
            }
        }

        val raw = (cloudResult as? Result.Success<List<String>>)?.data.orEmpty()
        val steps = clean(raw, existing)
        if (steps.isEmpty()) {
            Log.i(TAG, "steps: nothing usable came back, falling back to the offline splitter")
            val errMsg = (cloudResult as? Result.Error)?.exception?.message.orEmpty().lowercase()
            val fallbackHint = when {
                "disabled by administrator" in errMsg ->
                    "Suggested offline — Smart AI is paused for everyone by the administrator"
                "has not been used" in errMsg ||
                    "service_disabled" in errMsg ||
                    "api_not_activated" in errMsg ||
                    "is disabled" in errMsg ||
                    "not been enabled" in errMsg ->
                    "Suggested offline — add an AI key in Admin > AI & Config (free key: aistudio.google.com/apikey)"
                "app check" in errMsg || "appcheck" in errMsg || "attestation" in errMsg ->
                    "Suggested offline — copy 'App Check debug token' from Settings > AI into Firebase Console > App Check"
                "api_key_service_blocked" in errMsg ||
                    "api_key_android_app_blocked" in errMsg ||
                    "are blocked" in errMsg ->
                    "Suggested offline — allow Firebase AI Logic API on your Google Cloud API key"
                "429" in errMsg || "quota" in errMsg || "resource_exhausted" in errMsg ->
                    "Suggested offline — AI rate limit reached, try again shortly"
                else ->
                    "Suggested offline (Cloud AI unavailable right now)"
            }
            return offline(
                title = cleanTitle,
                description = description,
                existing = existing,
                messageWhenEmpty = "AI didn't answer - try again",
                noteWhenFallback = fallbackHint
            )
        }

        remember(cleanTitle, steps)
        Log.i(TAG, "steps: the model answered with ${steps.size}")
        return SubTaskSuggestions(steps = steps, source = SubTaskSuggestions.Source.CLOUD)
    }

    /** The offline attempt, labelled, or an honest message when it has nothing to offer. */
    private fun offline(
        title: String,
        description: String?,
        existing: List<String>,
        messageWhenEmpty: String,
        noteWhenFallback: String? = null
    ): SubTaskSuggestions {
        val rawOffline = if (cloud != null) {
            heuristics.suggestFallbackSteps(title, description, existing)
        } else {
            heuristics.split(title, existing)
        }
        val steps = clean(rawOffline, existing)
        return if (steps.isEmpty()) {
            SubTaskSuggestions(
                source = SubTaskSuggestions.Source.LOCAL,
                message = messageWhenEmpty
            )
        } else {
            SubTaskSuggestions(
                steps = steps,
                source = SubTaskSuggestions.Source.LOCAL,
                message = if (cloud != null) noteWhenFallback else null
            )
        }
    }

    /**
     * Enforces the display rules for every source: strip numbering and bullets, drop
     * blank and over-long steps, de-duplicate case-insensitively both within the answer
     * and against what the user already has, then cap.
     */
    internal fun clean(raw: List<String>, existing: List<String>): List<String> {
        val seen = existing.mapTo(mutableSetOf()) { it.normalized() }
        return raw.asSequence()
            .map { it.stripPrefixes() }
            .filter { it.isNotBlank() }
            .map { it.take(MAX_STEP_CHARS).trim() }
            .filter { seen.add(it.normalized()) }
            .take(MAX_STEPS)
            .toList()
    }

    private fun String.normalized(): String = trim().lowercase().replace(WHITESPACE, " ")

    /** "1. Buy milk", "- buy milk", "• buy milk", "Step 2: buy milk" all mean buy milk. */
    private fun String.stripPrefixes(): String =
        replace(LEADING_MARKER, "").trim()

    private fun estimateTokens(title: String, description: String?): Int {
        val promptChars = PROMPT_OVERHEAD_CHARS + title.length + (description?.length ?: 0)
        return (promptChars / CHARS_PER_TOKEN) + EXPECTED_OUTPUT_TOKENS
    }

    /**
     * Words that carry no subject matter. Without this, "buy milk and camera" and
     * "camera buy milk" score 0.75 against each other and miss the cache, because "and"
     * is counted as a word of equal weight to "milk".
     */
    private fun words(text: String): Set<String> =
        text.lowercase()
            .split(WORD_SPLIT)
            .filter { it.length >= MIN_WORD_CHARS }
            .filterNot { it in STOP_WORDS }
            .toSet()

    /**
     * Jaccard similarity over normalized words: "buy milk" and "milk" are close enough,
     * "buy milk" and "call the dentist" are not.
     */
    private fun cached(title: String): List<String>? {
        val target = words(title)
        if (target.isEmpty()) return null
        synchronized(cacheLock) {
            return cache.firstOrNull { entry ->
                val union = entry.words + target
                if (union.isEmpty()) {
                    false
                } else {
                    (entry.words intersect target).size.toDouble() / union.size >= CACHE_SIMILARITY
                }
            }?.steps
        }
    }

    private fun remember(title: String, steps: List<String>) {
        val entry = CachedSuggestions(words(title), steps)
        synchronized(cacheLock) {
            cache.removeAll { it.words == entry.words }
            cache.addLast(entry)
            while (cache.size > MAX_CACHED_TITLES) cache.removeFirst()
        }
    }

    /** Test seam: the cache holds across ViewModel instances, so tests need a reset. */
    internal fun clearCache() {
        synchronized(cacheLock) { cache.clear() }
    }

    private companion object {
        const val TAG = "SuggestSteps"
        const val TIMEOUT_MS = 15_000L

        const val MAX_STEPS = 6
        const val MAX_STEP_CHARS = 80
        const val MAX_CACHED_TITLES = 32
        const val CACHE_SIMILARITY = 0.85

        const val PROMPT_OVERHEAD_CHARS = 900
        const val CHARS_PER_TOKEN = 4
        const val EXPECTED_OUTPUT_TOKENS = 120
        const val MIN_WORD_CHARS = 3

        val WHITESPACE = Regex("""\s+""")

        val STOP_WORDS = setOf(
            "and", "the", "for", "with", "then", "from", "into", "your", "our",
            "some", "all", "any", "out", "off", "get", "new"
        )
        val WORD_SPLIT = Regex("""[^\p{L}\p{N}]+""")

        /** Leading "1." / "1)" / "-" / "•" / "Step 2:" - but never a word like "2nd". */
        val LEADING_MARKER = Regex(
            """^(?:step\s*\d+\s*[:.)-]\s*|\d+\s*[:.)-]\s*|[-•*–—]\s*)""",
            RegexOption.IGNORE_CASE
        )
    }
}
