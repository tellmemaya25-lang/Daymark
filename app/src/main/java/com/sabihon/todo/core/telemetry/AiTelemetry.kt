package com.sabihon.todo.core.telemetry

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zero-PII product telemetry for the AI features (`docs/02-daymark-ai-system-design.md`
 * §9.1).
 *
 * Two events, and neither of them carries anything the user typed:
 *
 *  - `daymark_ai_call` - `feature`, `source`, `status`, `latency_ms`. This is the event
 *    that answers "is the cloud path working at all?", which took three rounds of
 *    guessing to answer by hand.
 *  - `daymark_ai_outcome` - `feature`, `action` (`accepted` / `dismissed`), `count`.
 *    The rate of these two is the only real measure of whether the suggestions are
 *    good. A card that is always dismissed is a card that should not exist.
 *
 * Kept behind an interface so ViewModels are testable without Firebase, and so the whole
 * thing can be a no-op in unit tests without stubbing Analytics.
 */
interface AiTelemetry {
    fun logCall(feature: String, source: String, status: String, latencyMs: Long)
    fun logOutcome(feature: String, action: String, count: Int = 1)

    /** Swallows everything: a telemetry failure must never surface to the user. */
    object NoOp : AiTelemetry {
        override fun logCall(feature: String, source: String, status: String, latencyMs: Long) = Unit
        override fun logOutcome(feature: String, action: String, count: Int) = Unit
    }
}

@Singleton
class FirebaseAiTelemetry @Inject constructor(
    private val analytics: FirebaseAnalytics
) : AiTelemetry {

    override fun logCall(feature: String, source: String, status: String, latencyMs: Long) {
        // Wrapped because Analytics is a third-party SDK on a background path: if it
        // throws, the user loses their suggestion, which is not a trade worth making.
        runCatching {
            analytics.logEvent(
                EVENT_CALL,
                Bundle().apply {
                    putString(PARAM_FEATURE, feature)
                    putString(PARAM_SOURCE, source)
                    putString(PARAM_STATUS, status)
                    putLong(PARAM_LATENCY_MS, latencyMs)
                }
            )
        }
        runCatching {
            val dayKey = LocalDate.now().toString()
            val normalizedSource = source.lowercase()
            val normalizedStatus = status.lowercase()
            val isCloudSuccess = normalizedSource.contains("cloud") && normalizedStatus == "ok"
            val isLocalFallback = normalizedSource.contains("local") || normalizedSource.contains("fallback")
            val isError = normalizedStatus != "ok" && !isLocalFallback
            val updates = mutableMapOf<String, Any>(
                "dateKey" to dayKey,
                "totalCalls" to FieldValue.increment(1L),
                "totalLatencyMs" to FieldValue.increment(latencyMs.coerceAtLeast(0L)),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (isCloudSuccess) updates["cloudSuccessCount"] = FieldValue.increment(1L)
            if (isLocalFallback) updates["localFallbackCount"] = FieldValue.increment(1L)
            if (isError) updates["errorCount"] = FieldValue.increment(1L)
            FirebaseFirestore.getInstance()
                .collection("ai_telemetry_daily")
                .document(dayKey)
                .set(updates, SetOptions.merge())
        }
    }

    override fun logOutcome(feature: String, action: String, count: Int) {
        runCatching {
            analytics.logEvent(
                EVENT_OUTCOME,
                Bundle().apply {
                    putString(PARAM_FEATURE, feature)
                    putString(PARAM_ACTION, action)
                    putInt(PARAM_COUNT, count)
                }
            )
        }
        runCatching {
            val dayKey = LocalDate.now().toString()
            val delta = count.coerceAtLeast(1).toLong()
            val field = if (action == ACTION_ACCEPTED) "acceptedOutcomes" else "dismissedOutcomes"
            FirebaseFirestore.getInstance()
                .collection("ai_telemetry_daily")
                .document(dayKey)
                .set(
                    mapOf(
                        "dateKey" to dayKey,
                        field to FieldValue.increment(delta),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
        }
    }

    companion object {
        const val EVENT_CALL = "daymark_ai_call"
        const val EVENT_OUTCOME = "daymark_ai_outcome"

        const val PARAM_FEATURE = "feature"
        const val PARAM_SOURCE = "source"
        const val PARAM_STATUS = "status"
        const val PARAM_LATENCY_MS = "latency_ms"
        const val PARAM_ACTION = "action"
        const val PARAM_COUNT = "count"

        /** Feature names, kept as constants so a dashboard query cannot drift. */
        const val FEATURE_QUICK_ADD = "quick_add"
        const val FEATURE_PRIORITIZE = "prioritize"
        const val FEATURE_SUB_TASKS = "sub_tasks"
        const val FEATURE_SUGGEST_NEXT = "suggest_next"

        const val ACTION_ACCEPTED = "accepted"
        const val ACTION_DISMISSED = "dismissed"
    }
}
