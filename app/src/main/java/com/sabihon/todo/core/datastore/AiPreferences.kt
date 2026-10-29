package com.sabihon.todo.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sabihon.todo.domain.ai.AiProviderPreference
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

    /**
     * Ids of suggestions the user has muted with `×`.
     *
     * Defaults are provided so existing fakes keep compiling: a store that cannot
     * remember dismissals is a store whose suggestions come back, and that is the
     * failure mode this whole set of methods exists to prevent.
     */
    suspend fun getDismissedSuggestionIds(): Set<String> = emptySet()

    suspend fun dismissSuggestion(id: String) = Unit

    suspend fun clearDismissedSuggestions() = Unit

    /**
     * Optional on-device Google AI Studio Gemini API key (`aistudio.google.com/apikey`)
     * so users on the free Firebase Spark tier can run Gemini without upgrading to Blaze.
     */
    val geminiApiKey: Flow<String>
        get() = kotlinx.coroutines.flow.flowOf("")

    suspend fun getGeminiApiKeyNow(): String = ""

    suspend fun setGeminiApiKey(key: String) = Unit

    /**
     * Optional Anthropic API key (`console.anthropic.com`), used when Gemini cannot answer.
     *
     * Defaults are provided on every member so existing in-memory fakes keep compiling: a
     * store that cannot remember a key simply never offers the fallback, which is the safe
     * direction to fail.
     */
    val claudeApiKey: Flow<String>
        get() = kotlinx.coroutines.flow.flowOf("")

    suspend fun getClaudeApiKeyNow(): String = ""

    suspend fun setClaudeApiKey(key: String) = Unit

    /** Admin override for the Claude model id; blank means "use the built-in list". */
    suspend fun getClaudeModelNow(): String = ""

    suspend fun setClaudeModel(model: String) = Unit

    /** Which provider leads a request. See [com.sabihon.todo.domain.ai.AiProviderPreference]. */
    val aiProviderPreference: Flow<AiProviderPreference>
        get() = kotlinx.coroutines.flow.flowOf(AiProviderPreference.AUTO)

    suspend fun getAiProviderPreferenceNow(): AiProviderPreference = AiProviderPreference.AUTO

    suspend fun setAiProviderPreference(preference: AiProviderPreference) = Unit
}

@Singleton
class AiPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) : AiPreferenceStore {

    private val cloudEnabledKey = booleanPreferencesKey("ai_cloud_enabled")
    private val consentPromptedKey = booleanPreferencesKey("ai_consent_prompted")
    private val dismissedSuggestionsKey = stringSetPreferencesKey("ai_dismissed_suggestions")
    private val geminiApiKeyPref = stringPreferencesKey("ai_gemini_api_key")
    private val claudeApiKeyPref = stringPreferencesKey("ai_claude_api_key")
    private val claudeModelPref = stringPreferencesKey("ai_claude_model")
    private val providerPreferencePref = stringPreferencesKey("ai_provider_preference")

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

    override suspend fun getDismissedSuggestionIds(): Set<String> {
        val prefs = context.aiDataStore.data.first()
        return prefs[dismissedSuggestionsKey]?.toSet() ?: emptySet()
    }

    override suspend fun dismissSuggestion(id: String) {
        val safeId = id.trim().take(MAX_DISMISSED_ID_CHARS)
        if (safeId.isEmpty()) return
        context.aiDataStore.edit { prefs ->
            val current = prefs[dismissedSuggestionsKey] ?: emptySet()
            // Capped: the set is a mute list, not a history. Anything past the cap is
            // old enough that the user has forgotten what it was anyway.
            prefs[dismissedSuggestionsKey] = (current + safeId).toList().takeLast(MAX_DISMISSED).toSet()
        }
    }

    override suspend fun clearDismissedSuggestions() {
        context.aiDataStore.edit { prefs -> prefs.remove(dismissedSuggestionsKey) }
    }

    override val geminiApiKey: Flow<String> = context.aiDataStore.data.map { prefs ->
        prefs[geminiApiKeyPref].orEmpty()
    }

    override suspend fun getGeminiApiKeyNow(): String = geminiApiKey.first()

    override suspend fun setGeminiApiKey(key: String) {
        val cleaned = key.trim().removeSurrounding("\"").trim()
        context.aiDataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(geminiApiKeyPref)
            } else {
                prefs[geminiApiKeyPref] = cleaned
                prefs[cloudEnabledKey] = true
                prefs[consentPromptedKey] = true
            }
        }
    }

    override val claudeApiKey: Flow<String> = context.aiDataStore.data.map { prefs ->
        prefs[claudeApiKeyPref].orEmpty()
    }

    override suspend fun getClaudeApiKeyNow(): String = claudeApiKey.first()

    override suspend fun setClaudeApiKey(key: String) {
        // Same cleaning as Gemini: blank clears the key, and a saved key implies the user
        // wants cloud AI on, so it answers the consent prompt too.
        val cleaned = key.trim().removeSurrounding("\"").trim()
        context.aiDataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(claudeApiKeyPref)
            } else {
                prefs[claudeApiKeyPref] = cleaned
                prefs[cloudEnabledKey] = true
                prefs[consentPromptedKey] = true
            }
        }
    }

    override suspend fun getClaudeModelNow(): String = context.aiDataStore.data
        .first()[claudeModelPref]
        .orEmpty()

    override suspend fun setClaudeModel(model: String) {
        val cleaned = model.trim().take(64)
        context.aiDataStore.edit { prefs ->
            if (cleaned.isEmpty()) prefs.remove(claudeModelPref) else prefs[claudeModelPref] = cleaned
        }
    }

    override val aiProviderPreference: Flow<AiProviderPreference> = context.aiDataStore.data.map { prefs ->
        AiProviderPreference.fromName(prefs[providerPreferencePref])
    }

    override suspend fun getAiProviderPreferenceNow(): AiProviderPreference =
        aiProviderPreference.first()

    override suspend fun setAiProviderPreference(preference: AiProviderPreference) {
        context.aiDataStore.edit { prefs -> prefs[providerPreferencePref] = preference.name }
    }

    private fun sanitizeKey(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9_]"), "_").take(64)

    companion object {
        const val MAX_DAILY_CLOUD_CALLS = 25
        const val MAX_DAILY_TOKENS = 40_000
        private const val MAX_DISMISSED = 200
        private const val MAX_DISMISSED_ID_CHARS = 64
    }
}
