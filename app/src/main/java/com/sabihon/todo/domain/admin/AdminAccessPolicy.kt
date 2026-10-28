package com.sabihon.todo.domain.admin

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Pure, framework-free RBAC & bootstrap verification policy for the Admin Dashboard.
 *
 * Security invariants:
 *  - Plaintext credentials are NEVER stored in code, resources, or BuildConfig.
 *  - One-time Firebase Auth bootstrap for the owner account compares a salted SHA-256
 *    digest in constant time ([MessageDigest.isEqual]).
 *  - Primary owner (`tellmemaya25@gmail.com`) cannot be demoted or suspended.
 */
object AdminAccessPolicy {

    const val PRIMARY_ADMIN_EMAIL = "tellmemaya25@gmail.com"

    private const val BOOTSTRAP_SALT = "daymark-admin-v1-9f4c2e8a"
    private const val BOOTSTRAP_DIGEST_HEX =
        "a58b4f2119c953d622094dea20ba98d8f0476548d3782f382eae2d16b89a53ea"

    fun normalizeEmail(email: String?): String =
        email?.trim()?.lowercase(Locale.ROOT).orEmpty()

    fun isPrimaryAdminEmail(email: String?): Boolean =
        normalizeEmail(email) == PRIMARY_ADMIN_EMAIL

    fun hasAdminPrivilege(
        email: String?,
        storedRole: UserRole = UserRole.USER,
        hasDelegatedAdminDoc: Boolean = false,
        hasCustomAdminClaim: Boolean = false
    ): Boolean {
        if (hasCustomAdminClaim) return true
        if (isPrimaryAdminEmail(email)) return true
        if (hasDelegatedAdminDoc) return true
        return storedRole == UserRole.ADMIN
    }

    /**
     * Prevents accidental lockout: neither the primary owner nor the acting admin's own
     * session can have its admin privilege stripped here.
     */
    fun canModifyUserRole(
        actorUid: String,
        targetUid: String,
        targetEmail: String?
    ): Boolean {
        if (actorUid.isBlank() || targetUid.isBlank()) return false
        if (actorUid == targetUid) return false
        if (isPrimaryAdminEmail(targetEmail)) return false
        return true
    }

    /**
     * Prevents self-suspension and primary-owner suspension.
     */
    fun canSuspendUser(
        actorUid: String,
        targetUid: String,
        targetEmail: String?
    ): Boolean {
        if (actorUid.isBlank() || targetUid.isBlank()) return false
        if (actorUid == targetUid) return false
        if (isPrimaryAdminEmail(targetEmail)) return false
        return true
    }

    /**
     * Verifies whether [email] and [candidateSecret] match the one-time owner bootstrap
     * digest so `AuthRepositoryImpl` can provision the Firebase Auth user on first login
     * if it has not yet been created in the Firebase Console.
     */
    fun matchesBootstrapCredential(email: String?, candidateSecret: String): Boolean {
        val normalized = normalizeEmail(email)
        if (normalized != PRIMARY_ADMIN_EMAIL || candidateSecret.isEmpty()) return false
        val actualHex = computeDigestHex(BOOTSTRAP_SALT, normalized, candidateSecret)
        return MessageDigest.isEqual(
            actualHex.toByteArray(StandardCharsets.UTF_8),
            BOOTSTRAP_DIGEST_HEX.toByteArray(StandardCharsets.UTF_8)
        )
    }

    internal fun computeDigestHex(salt: String, normalizedEmail: String, secret: String): String {
        val input = "$salt:$normalizedEmail:$secret".toByteArray(StandardCharsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(input)
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }
}
