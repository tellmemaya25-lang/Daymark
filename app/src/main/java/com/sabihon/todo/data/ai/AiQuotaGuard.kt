package com.sabihon.todo.data.ai

import com.sabihon.todo.BuildConfig
import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.datastore.AiPreferences
import com.sabihon.todo.domain.ai.AiProvider
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class AiFeature {
    PRIORITIZE,
    QUICK_ADD,

    /**
     * Step suggestions. Its own feature rather than sharing QUICK_ADD's slot because the
     * per-feature single-flight lock and cooldown are what stop "Suggest steps" being
     * tapped five times in a row into five parallel calls.
     */
    SUB_TASKS,

    /** Next-task suggestions, shown on Home and inside a project. */
    SUGGEST_NEXT
}

/**
 * On-device rate limiter, daily token/call budget enforcer, single-flight guard,
 * and 60-second circuit breaker (`docs/02-daymark-ai-system-design.md` §2 & §8).
 */
@Singleton
class AiQuotaGuard internal constructor(
    private val prefs: AiPreferenceStore,
    private val clock: () -> Long,
    private val todayProvider: () -> LocalDate,
    private val buildTimeClaudeKey: String = ""
) {

    @Inject
    constructor(prefs: AiPreferenceStore) : this(
        prefs = prefs,
        clock = System::currentTimeMillis,
        todayProvider = LocalDate::now,
        buildTimeClaudeKey = BuildConfig.CLAUDE_API_KEY
    )

    enum class BlockReason {
        CLOUD_DISABLED,
        IN_FLIGHT,
        COOLDOWN,
        DAILY_QUOTA_EXCEEDED,
        CIRCUIT_OPEN
    }

    sealed interface ExecutionOutcome<out T> {
        data class Executed<T>(val value: T) : ExecutionOutcome<T>
        data class Blocked(val reason: BlockReason) : ExecutionOutcome<Nothing>
    }

    private val featureLocks = ConcurrentHashMap<AiFeature, Mutex>()
    private val lastCallMillis = ConcurrentHashMap<String, Long>()

    @Volatile
    private var consecutiveFailures: Int = 0

    @Volatile
    private var circuitOpenUntilMillis: Long = 0L

    @Volatile
    var runtimeMaxDailyCalls: Int = AiPreferences.MAX_DAILY_CLOUD_CALLS

    private fun mutexFor(feature: AiFeature): Mutex =
        featureLocks.computeIfAbsent(feature) { Mutex() }

    /**
     * Runs [block] only when:
     *  1. Cloud AI is enabled in [AiPreferenceStore],
     *  2. No concurrent request for [feature] is already in flight,
     *  3. The 60-second circuit breaker is closed,
     *  4. The per-feature cooldown has elapsed (when [enforceCooldown] is true), and
     *  5. At least one configured provider still has daily request and token budget left.
     *
     * Rule 5 is per provider on purpose - see [providersWithRoom].
     */
    suspend fun <T> runIfPermitted(
        uid: String?,
        feature: AiFeature,
        estimatedTokens: Int,
        enforceCooldown: Boolean = true,
        block: suspend () -> T
    ): ExecutionOutcome<T> {
        if (!prefs.isCloudEnabledNow()) {
            return ExecutionOutcome.Blocked(BlockReason.CLOUD_DISABLED)
        }

        val now = clock()
        if (now < circuitOpenUntilMillis) {
            return ExecutionOutcome.Blocked(BlockReason.CIRCUIT_OPEN)
        }

        val safeUid = uid?.takeIf { it.isNotBlank() } ?: "anon"
        val cooldownKey = "$safeUid:${feature.name}"
        if (enforceCooldown) {
            val previous = lastCallMillis[cooldownKey]
            if (previous != null && (now - previous) in 0 until COOLDOWN_MS) {
                return ExecutionOutcome.Blocked(BlockReason.COOLDOWN)
            }
        }

        // Blocked only when *no* provider can serve. A spent free Gemini day must not stop
        // a Claude key the user configured and paid for - that was the whole point of the
        // fallback.
        if (providersWithRoom(safeUid, estimatedTokens).isEmpty()) {
            return ExecutionOutcome.Blocked(BlockReason.DAILY_QUOTA_EXCEEDED)
        }

        val mutex = mutexFor(feature)
        if (!mutex.tryLock()) {
            return ExecutionOutcome.Blocked(BlockReason.IN_FLIGHT)
        }

        return try {
            lastCallMillis[cooldownKey] = clock()
            val value = block()
            ExecutionOutcome.Executed(value)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Providers that may still serve today, in the order the user wants them tried.
     *
     * This is the single source of truth for "is there room": [runIfPermitted] blocks only
     * when it comes back empty, and [FirebaseAiTaskService] uses the same list to decide
     * which providers to actually call, so a spent Gemini day routes the request to Claude
     * instead of dropping it to heuristics.
     */
    suspend fun providersWithRoom(uid: String?, estimatedTokens: Int = 1): List<AiProvider> {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: "anon"
        val preference = prefs.getAiProviderPreferenceNow()
        val claudeKey = AiProviderPolicy.normalizeApiKey(
            prefs.getClaudeApiKeyNow().ifBlank { buildTimeClaudeKey }
        )

        // Gemini counts as available even with no key: Firebase AI Logic can serve without
        // one. Whether it *works* is discovered by trying, and a failure falls through to
        // Claude like any other.
        val epochDay = todayProvider().toEpochDay()
        val budgets = AiProvider.entries.associateWith { provider ->
            val snapshot = prefs.getDailyQuota(safeUid, epochDay, provider)
            AiProviderPolicy.ProviderBudget(
                calls = snapshot.calls,
                estimatedTokens = snapshot.estimatedTokens
            )
        }

        return AiProviderPolicy.providersWithRoom(
            preference = preference,
            hasClaudeKey = claudeKey.isNotEmpty(),
            budgets = budgets,
            dailyCallCap = runtimeMaxDailyCalls,
            dailyTokenCap = AiPreferences.MAX_DAILY_TOKENS,
            estimatedTokens = estimatedTokens
        )
    }

    /**
     * Records one completed cloud call against [provider].
     *
     * The provider matters: crediting every call to Gemini is what made a Claude-backed
     * user look like they had spent a free Gemini day they never touched.
     */
    suspend fun recordSuccess(
        uid: String?,
        estimatedTokens: Int,
        provider: AiProvider = AiProvider.GEMINI
    ) {
        consecutiveFailures = 0
        circuitOpenUntilMillis = 0L
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: "anon"
        prefs.recordCloudUsage(safeUid, todayProvider().toEpochDay(), estimatedTokens, provider)
    }

    fun recordFailure(error: Throwable?) {
        val message = error?.message.orEmpty().lowercase()
        val immediateTrip = "429" in message ||
            "quota" in message ||
            "resource_exhausted" in message ||
            "rate limit" in message

        if (immediateTrip) {
            consecutiveFailures += 1
            circuitOpenUntilMillis = clock() + CIRCUIT_OPEN_MS
        } else if ("403" !in message && "permission_denied" !in message) {
            // Transient 5xx / network failures open the breaker after 2 consecutive misses;
            // 403 config errors do not lock the user out for 60s once fixed in Console.
            consecutiveFailures += 1
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                circuitOpenUntilMillis = clock() + CIRCUIT_OPEN_MS
            }
        }
    }

    fun isCircuitOpen(): Boolean = clock() < circuitOpenUntilMillis

    fun resetRuntimeState() {
        consecutiveFailures = 0
        circuitOpenUntilMillis = 0L
        lastCallMillis.clear()
    }

    companion object {
        const val COOLDOWN_MS = 15_000L
        const val CIRCUIT_OPEN_MS = 60_000L
        const val MAX_CONSECUTIVE_FAILURES = 2
    }
}
