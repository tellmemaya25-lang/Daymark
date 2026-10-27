package com.sabihon.todo.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated DataStore file for AI settings and per-user daily quota counters.
 * Kept separate from `sabihon_prefs` and `daymark_onboarding` so only one
 * DataStore instance ever owns this file in the process.
 */
private val Context.aiDataStore by preferencesDataStore(name = "daymark_ai_prefs")

data class DailyAiQuotaSnapshot(
    val epochDay: Long,
    val calls: Int,
    val estimatedTokens: Int
)

/**
 * Seam for AI preferences and quota persistence so pure-JVM tests can supply an
 * in-memory implementation without an Android [Context].
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

    private fun sanitizeKey(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9_]"), "_").take(64)

    companion object {
        const val MAX_DAILY_CLOUD_CALLS = 25
        const val MAX_DAILY_TOKENS = 40_000
    }
}
