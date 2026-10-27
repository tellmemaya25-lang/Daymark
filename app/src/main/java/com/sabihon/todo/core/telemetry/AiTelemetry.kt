package com.sabihon.todo.core.telemetry

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.sabihon.todo.domain.ai.AiModelTier
import com.sabihon.todo.domain.ai.AiSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy-safe observability contract (`docs/02-daymark-ai-system-design.md` §9.1).
 *
 * Strictly forbids task titles, descriptions, or category names in telemetry payloads;
 * logs only operational latency, cache/fallback provenance, and human-in-the-loop
 * acceptance vs. undo/dismiss counters.
 */
interface AiTelemetryLogger {

    fun logRequest(
        feature: String,
        modelTier: AiModelTier,
        source: AiSource,
        latencyMs: Long,
        errorType: String? = null
    )

    fun logPrioritizationDecision(
        proposedCount: Int,
        acceptedCount: Int,
        dismissed: Boolean
    )

    fun logPrioritizationUndo(revertedCount: Int)

    fun logSuggestionAction(
        kind: String,
        action: String,
        count: Int = 1
    )
}

object NoOpAiTelemetry : AiTelemetryLogger {
    override fun logRequest(
        feature: String,
        modelTier: AiModelTier,
        source: AiSource,
        latencyMs: Long,
        errorType: String?
    ) = Unit

    override fun logPrioritizationDecision(
        proposedCount: Int,
        acceptedCount: Int,
        dismissed: Boolean
    ) = Unit

    override fun logPrioritizationUndo(revertedCount: Int) = Unit

    override fun logSuggestionAction(kind: String, action: String, count: Int) = Unit
}

@Singleton
class FirebaseAiTelemetry @Inject constructor(
    @ApplicationContext private val context: Context
) : AiTelemetryLogger {

    private val analytics: FirebaseAnalytics by lazy {
        FirebaseAnalytics.getInstance(context)
    }

    override fun logRequest(
        feature: String,
        modelTier: AiModelTier,
        source: AiSource,
        latencyMs: Long,
        errorType: String?
    ) {
        safeLog("daymark_ai_request") {
            putString("feature", feature.take(32))
            putString("model_tier", modelTier.name.lowercase())
            putString("source", source.name.lowercase())
            putLong("latency_ms", latencyMs.coerceAtLeast(0L))
            if (!errorType.isNullOrBlank()) {
                putString("error_type", errorType.take(32))
            }
        }
    }

    override fun logPrioritizationDecision(
        proposedCount: Int,
        acceptedCount: Int,
        dismissed: Boolean
    ) {
        safeLog("daymark_ai_prio_decision") {
            putInt("proposed_count", proposedCount.coerceAtLeast(0))
            putInt("accepted_count", acceptedCount.coerceAtLeast(0))
            putInt("dismissed", if (dismissed) 1 else 0)
        }
    }

    override fun logPrioritizationUndo(revertedCount: Int) {
        safeLog("daymark_ai_prio_undo") {
            putInt("reverted_count", revertedCount.coerceAtLeast(0))
        }
    }

    override fun logSuggestionAction(kind: String, action: String, count: Int) {
        safeLog("daymark_ai_suggestion_action") {
            putString("kind", kind.take(24))
            putString("action", action.take(24))
            putInt("count", count.coerceAtLeast(0))
        }
    }

    private inline fun safeLog(eventName: String, build: Bundle.() -> Unit) {
        try {
            val bundle = Bundle().apply(build)
            analytics.logEvent(eventName, bundle)
        } catch (_: Throwable) {
            // Telemetry must never disrupt user workflows or preview/test environments.
        }
    }
}
