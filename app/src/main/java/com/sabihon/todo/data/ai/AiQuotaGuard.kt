package com.sabihon.todo.data.ai

import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.datastore.AiPreferences
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class AiFeature {
    PRIORITIZE,
    QUICK_ADD,
    SUGGEST_SUBTASKS,
    SUGGEST_NEXT_TASKS
}

/**
 * On-device rate limiter, daily token/call budget enforcer, single-flight guard,
 * and 60-second circuit breaker (`docs/02-daymark-ai-system-design.md` §2 & §8).
 */
@Singleton
class AiQuotaGuard internal constructor(
    private val prefs: AiPreferenceStore,
    private val clock: () -> Long,
    private val todayProvider: () -> LocalDate
) {

    @Inject
    constructor(prefs: AiPreferenceStore) : this(
        prefs = prefs,
        clock = System::currentTimeMillis,
        todayProvider = LocalDate::now
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

    private fun mutexFor(feature: AiFeature): Mutex =
        featureLocks.computeIfAbsent(feature) { Mutex() }

    /**
     * Runs [block] only when:
     *  1. Cloud AI is enabled in [AiPreferenceStore],
     *  2. No concurrent request for [feature] is already in flight,
     *  3. The 60-second circuit breaker is closed,
     *  4. The per-feature cooldown has elapsed (when [enforceCooldown] is true), and
     *  5. The user's daily request and token budgets have room.
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

        val epochDay = todayProvider().toEpochDay()
        val snapshot = prefs.getDailyQuota(safeUid, epochDay)
        if (snapshot.calls >= AiPreferences.MAX_DAILY_CLOUD_CALLS ||
            snapshot.estimatedTokens + estimatedTokens.coerceAtLeast(1) > AiPreferences.MAX_DAILY_TOKENS
        ) {
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

    suspend fun recordSuccess(uid: String?, estimatedTokens: Int) {
        consecutiveFailures = 0
        circuitOpenUntilMillis = 0L
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: "anon"
        prefs.recordCloudUsage(safeUid, todayProvider().toEpochDay(), estimatedTokens)
    }

    fun recordFailure(error: Throwable?) {
        val message = error?.message.orEmpty().lowercase()
        val immediateTrip = "429" in message ||
            "quota" in message ||
            "resource_exhausted" in message ||
            "rate limit" in message ||
            "403" in message ||
            "permission_denied" in message ||
            "app check" in message ||
            "appcheck" in message

        if (immediateTrip) {
            consecutiveFailures += 1
            circuitOpenUntilMillis = clock() + CIRCUIT_OPEN_MS
        } else {
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
