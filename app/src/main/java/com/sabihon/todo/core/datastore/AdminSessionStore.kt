package com.sabihon.todo.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sabihon.todo.domain.admin.AdminAccessPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.adminSessionDataStore by preferencesDataStore(name = "daymark_admin_session")

/**
 * Persists verified primary-admin session state across app launches when the owner
 * signs in via the salted SHA-256 bootstrap credential while Firebase Auth has that
 * email bound to Google OAuth (`FirebaseAuthUserCollisionException`) or offline.
 *
 * Plaintext passwords are NEVER written to disk. An optional ephemeral in-memory-only
 * credential reference is held strictly for the current process so that if the admin
 * taps "Link Google Firebase Token", `AuthRepositoryImpl` can call
 * `FirebaseUser.linkWithCredential(EmailAuthProvider)` and immediately wipe it.
 */
@Singleton
class AdminSessionStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val activeKey = booleanPreferencesKey("verified_owner_active")
    private val emailKey = stringPreferencesKey("verified_owner_email")
    private val activatedAtKey = longPreferencesKey("verified_owner_at")

    @Volatile
    private var ephemeralLinkSecret: String? = null

    val isOwnerSessionActive: Flow<Boolean> = context.adminSessionDataStore.data.map { prefs ->
        val active = prefs[activeKey] ?: false
        val email = prefs[emailKey]
        active && AdminAccessPolicy.isPrimaryAdminEmail(email)
    }

    suspend fun isOwnerSessionActiveNow(): Boolean = isOwnerSessionActive.first()

    suspend fun getVerifiedEmailNow(): String? {
        val prefs = context.adminSessionDataStore.data.first()
        val active = prefs[activeKey] ?: false
        val email = prefs[emailKey]
        return if (active && AdminAccessPolicy.isPrimaryAdminEmail(email)) email else null
    }

    suspend fun activateOwnerSession(email: String, secretForOptionalLink: String? = null) {
        val normalized = AdminAccessPolicy.normalizeEmail(email)
        if (!AdminAccessPolicy.isPrimaryAdminEmail(normalized)) return
        ephemeralLinkSecret = secretForOptionalLink?.takeIf { it.isNotEmpty() }
        context.adminSessionDataStore.edit { prefs ->
            prefs[activeKey] = true
            prefs[emailKey] = normalized
            prefs[activatedAtKey] = System.currentTimeMillis()
        }
    }

    fun consumeEphemeralLinkSecret(): String? {
        val secret = ephemeralLinkSecret
        ephemeralLinkSecret = null
        return secret
    }

    suspend fun clearSession() {
        ephemeralLinkSecret = null
        context.adminSessionDataStore.edit { prefs ->
            prefs.remove(activeKey)
            prefs.remove(emailKey)
            prefs.remove(activatedAtKey)
        }
    }
}
