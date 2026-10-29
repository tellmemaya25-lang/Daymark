package com.sabihon.todo.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.announcementStore by preferencesDataStore(name = "daymark_announcements")

/**
 * Remembers which broadcast message the user has already dismissed.
 *
 * The dismissal is keyed by the **text**, not by a boolean: the banner is whatever an admin
 * last typed into the dashboard, so "dismissed" has to mean "dismissed *this* message".
 * A boolean would silence the next announcement too, and the one after that, forever - the
 * feature would quietly stop working after its first use.
 */
@Singleton
class AnnouncementPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dismissedKey = stringPreferencesKey("dismissed_banner")

    /** The exact text the user dismissed, if any. */
    val dismissed: Flow<String> = context.announcementStore.data.map { prefs ->
        prefs[dismissedKey].orEmpty()
    }

    suspend fun dismiss(text: String) {
        val safe = text.trim()
        if (safe.isEmpty()) return
        context.announcementStore.edit { prefs -> prefs[dismissedKey] = safe }
    }

    suspend fun dismissedNow(): String = dismissed.first()
}
