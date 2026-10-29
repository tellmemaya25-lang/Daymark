package com.sabihon.todo.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sabihon.todo.domain.reminder.ReminderAlertStyle
import com.sabihon.todo.domain.reminder.ReminderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Its own DataStore file, like `daymark_ai_prefs`: one file, one owner, and reminder settings
 * never race the theme or AI writes.
 */
private val Context.reminderDataStore by preferencesDataStore(name = "daymark_reminder_prefs")

/**
 * The Settings > Reminders surface, persisted.
 *
 * Reads return [ReminderSettings], which is pure and carries the defaults; this class only
 * turns DataStore keys into that object and back. Invalid stored values fall back to the
 * default rather than throwing - a corrupt preference must not stop the app from reminding.
 */
@Singleton
class ReminderPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val enabledKey = booleanPreferencesKey("reminders_enabled")
    private val leadKey = intPreferencesKey("reminder_lead_minutes")
    private val styleKey = stringPreferencesKey("reminder_alert_style")
    private val snoozeKey = intPreferencesKey("reminder_snooze_minutes")

    /**
     * Whether the POST_NOTIFICATIONS prompt has already been shown.
     *
     * Android stops re-prompting after two denials, so asking on every launch burns the
     * chances that matter. After the first ask, the Settings row is the way back.
     */
    private val permissionAskedKey = booleanPreferencesKey("reminder_permission_asked")

    val settings: Flow<ReminderSettings> = context.reminderDataStore.data.map(::toSettings)

    suspend fun current(): ReminderSettings = settings.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.reminderDataStore.edit { it[enabledKey] = enabled }
    }

    suspend fun setLeadMinutes(minutes: Int) {
        val safe = minutes.coerceIn(0, ReminderSettings.MAX_LEAD_MINUTES)
        context.reminderDataStore.edit { it[leadKey] = safe }
    }

    suspend fun setAlertStyle(style: ReminderAlertStyle) {
        context.reminderDataStore.edit { it[styleKey] = style.name }
    }

    suspend fun setSnoozeMinutes(minutes: Int) {
        val safe = minutes.coerceIn(1, 24 * 60)
        context.reminderDataStore.edit { it[snoozeKey] = safe }
    }

    suspend fun hasAskedForPermission(): Boolean =
        context.reminderDataStore.data.first()[permissionAskedKey] ?: false

    suspend fun markPermissionAsked() {
        context.reminderDataStore.edit { it[permissionAskedKey] = true }
    }

    private fun toSettings(prefs: Preferences): ReminderSettings = ReminderSettings(
        enabled = prefs[enabledKey] ?: ReminderSettings.DEFAULT.enabled,
        leadMinutes = (prefs[leadKey] ?: ReminderSettings.DEFAULT.leadMinutes)
            .coerceIn(0, ReminderSettings.MAX_LEAD_MINUTES),
        alertStyle = ReminderAlertStyle.fromName(
            prefs[styleKey] ?: ReminderSettings.DEFAULT.alertStyle.name
        ),
        snoozeMinutes = (prefs[snoozeKey] ?: ReminderSettings.DEFAULT.snoozeMinutes)
            .coerceIn(1, 24 * 60)
    )
}
