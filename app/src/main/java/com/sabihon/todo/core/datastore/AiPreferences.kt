package com.sabihon.todo.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether one sentence may be sent to the model.
 *
 * On by default, because smart fill is the point of the feature and a user who never
 * opens Settings should still get it. The switch exists for the two people who care:
 * someone who does not want their words leaving the device, and someone whose model
 * calls are failing and who would rather stop paying the round-trip.
 *
 * The on-by-default matters more than it looks: turning this **off** must not make the
 * button feel broken. The offline parser still runs, so dates, times, priorities,
 * repeats, reminders and spoken sub-task separators all still fill in. Only the two
 * things that need a model - a description, and sub-tasks split out of prose - stop.
 */
@Singleton
class AiPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val enabledKey = booleanPreferencesKey("ai_enabled")

    val aiEnabled: Flow<Boolean> = context.sabihonDataStore.data.map { prefs ->
        // Absent means "never touched", which means on.
        prefs[enabledKey] ?: true
    }

    suspend fun setAiEnabled(enabled: Boolean) {
        context.sabihonDataStore.edit { prefs ->
            prefs[enabledKey] = enabled
        }
    }
}
