package com.sabihon.todo.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.onboardingStore by preferencesDataStore(name = "daymark_onboarding")

/**
 * Remembers whether the daymark onboarding cover (the "Make room for what matters." screen)
 * has already been shown, so it appears once per install rather than on every launch.
 */
@Singleton
class OnboardingPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val seenKey = booleanPreferencesKey("onboarding_seen")

    val hasSeenOnboarding: Flow<Boolean> = context.onboardingStore.data.map { prefs ->
        prefs[seenKey] ?: false
    }

    suspend fun markOnboardingSeen() {
        context.onboardingStore.edit { prefs -> prefs[seenKey] = true }
    }

    suspend fun hasSeenOnboardingNow(): Boolean = hasSeenOnboarding.first()

    /** Mostly useful for previews / "replay the intro" style entry points. */
    suspend fun resetOnboarding() {
        context.onboardingStore.edit { prefs -> prefs.remove(seenKey) }
    }
}
