package com.sabihon.todo.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated DataStore file for AI settings, per-user daily quota counters, and
 * dismissed suggestion fingerprints. Kept separate from `sabihon_prefs` and
 * `daymark_onboarding` so only one DataStore instance ever owns this file in process.
 */
private val Context.aiDataStore by preferencesDataStore(name = "daymark_ai_prefs")

data class DailyAiQuotaSnapshot(
    val epochDay: Long,
    val calls: Int,
    val estimatedTokens: Int
)

/**
 * Seam for AI preferences, quota persistence, and suggestion dismissal state so
 * pure-JVM tests can supply an in-memory implementation without an Android [Context].
 */
interface AiPreferenceStore {
    val cloudEnabled: Flow<Boolean>
    val consentPrompted: Flow<Boolean>

    suspend fun isCloudEnabledNow(): Boolean
    suspend fun hasPromptedConsentNow(): Boolean

    /** Updates the cloud AI switch and marks the one-time consent prompt as answered. */
    suspend fun setCloudEnabled(enabled: Boolean)

    /** Live count of cloud AI calls made by [uid] on [epochDay]. */
    fun observeDailyCalls(
        uid: String?,
        epochDay: Long = LocalDate.now().toEpochDay()
    ): Flow<Int>

    suspend fun getDailyQuota(uid: String, epochDay: Long): DailyAiQuotaSnapshot

    suspend fun recordCloudUsage(uid: String, epochDay: Long, estimatedTokens: Int)

    /** Dismissed `suggestionId` hashes for [uid] so rejected next-task ideas never reappear. */
    suspend fun getDismissedSuggestionIds(uid: String?): Set<String> = emptySet()

    suspend fun dismissSuggestion(uid: String?, suggestionId: String) = Unit

    suspend fun clearDismissedSuggestions(uid: String?) = Unit
}

@Singleton
class AiPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) : AiPreferenceStore {

    private val cloudEnabledKey = booleanPreferencesKey("ai_cloud_enabled")
    private val consentPromptedKey = booleanPreferencesKey("ai_consent_prompted")

    private fun dayKey(uid: String) = longPreferencesKey("ai_quota_${sanitizeKey(uid)}_day")
    private fun callsKey(uid: String) = intPreferencesKey("ai_quota_${sanitizeKey(uid)}_calls")
    private fun tokensKey(uid: String) = intPreferencesKey("ai_quota_${sanitizeKey(uid)}_tokens")
    private fun dismissedKey(uid: String) = stringSetPreferencesKey("ai_dismissed_${sanitizeKey(uid)}")

    override val cloudEnabled: Flow<Boolean> = context.aiDataStore.data.map { prefs ->
        prefs[cloudEnabledKey] ?: false
    }

    override val consentPrompted: Flow<Boolean> = context.aiDataStore.data.map { prefs ->
        prefs[consentPromptedKey] ?: false
    }

    override suspend fun isCloudEnabledNow(): Boolean = cloudEnabled.first()

    override suspend fun hasPromptedConsentNow(): Boolean = consentPrompted.first()

    override suspend fun setCloudEnabled(enabled: Boolean) {
        context.aiDataStore.edit { prefs ->
            prefs[cloudEnabledKey] = enabled
            prefs[consentPromptedKey] = true
        }
    }

    override fun observeDailyCalls(uid: String?, epochDay: Long): Flow<Int> {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: return kotlinx.coroutines.flow.flowOf(0)
        return context.aiDataStore.data.map { prefs ->
            val storedDay = prefs[dayKey(safeUid)]
            if (storedDay == epochDay) prefs[callsKey(safeUid)] ?: 0 else 0
        }
    }

    override suspend fun getDailyQuota(uid: String, epochDay: Long): DailyAiQuotaSnapshot {
        val safeUid = uid.ifBlank { "anon" }
        val prefs = context.aiDataStore.data.first()
        val storedDay = prefs[dayKey(safeUid)]
        return if (storedDay == epochDay) {
            DailyAiQuotaSnapshot(
                epochDay = epochDay,
                calls = prefs[callsKey(safeUid)] ?: 0,
                estimatedTokens = prefs[tokensKey(safeUid)] ?: 0
            )
        } else {
            DailyAiQuotaSnapshot(epochDay = epochDay, calls = 0, estimatedTokens = 0)
        }
    }

    override suspend fun recordCloudUsage(uid: String, epochDay: Long, estimatedTokens: Int) {
        val safeUid = uid.ifBlank { "anon" }
        val deltaTokens = estimatedTokens.coerceAtLeast(0)
        context.aiDataStore.edit { prefs ->
            val dKey = dayKey(safeUid)
            val cKey = callsKey(safeUid)
            val tKey = tokensKey(safeUid)
            if (prefs[dKey] == epochDay) {
                prefs[cKey] = (prefs[cKey] ?: 0) + 1
                prefs[tKey] = (prefs[tKey] ?: 0) + deltaTokens
            } else {
                prefs[dKey] = epochDay
                prefs[cKey] = 1
                prefs[tKey] = deltaTokens
            }
        }
    }

    override suspend fun getDismissedSuggestionIds(uid: String?): Set<String> {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: "anon"
        val prefs = context.aiDataStore.data.first()
        return prefs[dismissedKey(safeUid)] ?: emptySet()
    }

    override suspend fun dismissSuggestion(uid: String?, suggestionId: String) {
        if (suggestionId.isBlank()) return
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: "anon"
        val key = dismissedKey(safeUid)
        context.aiDataStore.edit { prefs ->
            val current = prefs[key].orEmpty().toList()
            val updated = (current + suggestionId)
                .distinct()
                .takeLast(MAX_DISMISSED_SUGGESTIONS)
                .toSet()
            prefs[key] = updated
        }
    }

    override suspend fun clearDismissedSuggestions(uid: String?) {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: "anon"
        context.aiDataStore.edit { prefs ->
            prefs.remove(dismissedKey(safeUid))
        }
    }

    private fun sanitizeKey(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9_]"), "_").take(64)

    companion object {
        const val MAX_DAILY_CLOUD_CALLS = 25
        const val MAX_DAILY_TOKENS = 40_000
        const val MAX_DISMISSED_SUGGESTIONS = 100
    }
}
