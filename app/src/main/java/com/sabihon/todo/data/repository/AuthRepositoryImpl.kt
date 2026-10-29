package com.sabihon.todo.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.sabihon.todo.core.datastore.AdminSessionStore
import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.AiQuotaGuard
import com.sabihon.todo.data.ai.AiSnapshotCache
import com.sabihon.todo.data.remote.dto.UserDto
import com.sabihon.todo.di.IoDispatcher
import com.sabihon.todo.domain.admin.AdminAccessPolicy
import com.sabihon.todo.domain.admin.UserRole
import com.sabihon.todo.domain.repository.AuthRepository
import com.sabihon.todo.domain.repository.CategoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase Auth + verified owner session implementation.
 *
 * Implements the 3-stage owner login pipeline:
 *  1. Native Firebase Email/Password authentication.
 *  2. One-time Firebase user creation when salted SHA-256 bootstrap verification succeeds.
 *  3. Resilient hybrid owner session (`AdminSessionStore`) when `tellmemaya25@gmail.com`
 *     is already registered via Google OAuth (`FirebaseAuthUserCollisionException`), plus
 *     automatic `EmailAuthProvider` credential linking on Google token upgrade.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val categoryRepository: CategoryRepository,
    private val aiSnapshotCache: AiSnapshotCache,
    private val aiQuotaGuard: AiQuotaGuard,
    private val aiPreferences: AiPreferenceStore,
    private val adminSessionStore: AdminSessionStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : AuthRepository {

    @Volatile
    private var cachedOwnerSessionActive: Boolean = false

    @Volatile
    private var cachedOwnerEmail: String? = null

    override fun getCurrentUserId(): String? =
        auth.currentUser?.takeIf(::isUsableFirebaseSession)?.uid
            ?: if (cachedOwnerSessionActive) AdminAccessPolicy.FALLBACK_OWNER_UID else null

    override fun getCurrentUserEmail(): String? =
        auth.currentUser?.email?.takeIf { it.isNotBlank() }
            ?: if (cachedOwnerSessionActive) (cachedOwnerEmail ?: AdminAccessPolicy.PRIMARY_ADMIN_EMAIL) else null

    override fun isUserLoggedIn(): Boolean =
        auth.currentUser?.let(::isUsableFirebaseSession) == true || cachedOwnerSessionActive

    /** Password accounts must verify their mailbox before a Firebase session is usable. */
    private fun isUsableFirebaseSession(user: FirebaseUser): Boolean =
        user.isEmailVerified || user.providerData.none { it.providerId == "password" }

    override suspend fun hasActiveSession(): Boolean = withContext(ioDispatcher) {
        val verifiedEmail = runCatching { adminSessionStore.getVerifiedEmailNow() }.getOrNull()
        cachedOwnerSessionActive = verifiedEmail != null
        cachedOwnerEmail = verifiedEmail
        auth.currentUser?.let(::isUsableFirebaseSession) == true || cachedOwnerSessionActive
    }

    override suspend fun isCurrentUserAdmin(): Boolean = withContext(ioDispatcher) {
        val verifiedOwnerEmail = runCatching { adminSessionStore.getVerifiedEmailNow() }.getOrNull()
        if (verifiedOwnerEmail != null) {
            cachedOwnerSessionActive = true
            cachedOwnerEmail = verifiedOwnerEmail
            return@withContext true
        }

        val user = auth.currentUser ?: return@withContext false
        if (AdminAccessPolicy.isPrimaryAdminEmail(user.email)) {
            runCatching { adminSessionStore.activateOwnerSession(user.email.orEmpty()) }
            cachedOwnerSessionActive = true
            cachedOwnerEmail = AdminAccessPolicy.normalizeEmail(user.email)
            return@withContext true
        }

        val uid = user.uid
        val delegated = runCatching {
            withTimeoutOrNull(2_500L) {
                firestore.collection("admins").document(uid).get().await()
            }?.getBoolean("enabled") == true
        }.getOrDefault(false)
        if (delegated) return@withContext true

        val storedRole = runCatching {
            val rawRole = withTimeoutOrNull(2_500L) {
                firestore.collection("users").document(uid).get().await()
            }?.getString("role")
            UserRole.fromString(rawRole)
        }.getOrDefault(UserRole.USER)

        AdminAccessPolicy.hasAdminPrivilege(
            email = user.email,
            storedRole = storedRole,
            hasDelegatedAdminDoc = false
        )
    }

    override fun observeAuthState(): Flow<Boolean> {
        val firebaseFlow = callbackFlow {
            val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                trySend(firebaseAuth.currentUser?.let(::isUsableFirebaseSession) == true)
            }
            auth.addAuthStateListener(listener)
            awaitClose { auth.removeAuthStateListener(listener) }
        }
        return combine(firebaseFlow, adminSessionStore.isOwnerSessionActive) { fbSignedIn, ownerActive ->
            cachedOwnerSessionActive = ownerActive
            fbSignedIn || ownerActive
        }
    }

    override suspend fun signUp(email: String, password: String, displayName: String): Result<String> =
        withContext(ioDispatcher) {
            try {
                val cleanEmail = email.trim()
                val res = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
                val user = res.user ?: return@withContext Result.Error(Exception("User creation failed"))
                val uid = user.uid
                try {
                    user.updateProfile(
                        UserProfileChangeRequest.Builder()
                            .setDisplayName(displayName)
                            .build()
                    ).await()
                    user.sendEmailVerification().await()
                } finally {
                    // New accounts remain signed out until the mailbox link is confirmed.
                    auth.signOut()
                }
                // User profile, default categories, and admin role are initialized on the
                // first verified sign-in, never while the address is still untrusted.
                Result.Success(uid)
            } catch (e: Exception) {
                Result.Error(e, friendlyMessage(e))
            }
        }

    override suspend fun resendEmailVerification(email: String, password: String): Result<Unit> =
        withContext(ioDispatcher) {
            val result = try {
                val user = auth.signInWithEmailAndPassword(email.trim(), password).await().user
                    ?: throw IllegalStateException("Sign-in failed")
                if (user.isEmailVerified) {
                    Result.Error(
                        IllegalStateException("Email already verified"),
                        "This email is already verified. Go back and sign in."
                    )
                } else {
                    user.sendEmailVerification().await()
                    Result.Success(Unit)
                }
            } catch (e: Exception) {
                Result.Error(e, friendlyMessage(e))
            } finally {
                auth.signOut()
            }
            result
        }

    override suspend fun signIn(email: String, password: String): Result<String> =
        withContext(ioDispatcher) {
            val cleanEmail = email.trim()
            try {
                val res = auth.signInWithEmailAndPassword(cleanEmail, password).await()
                val user = res.user ?: return@withContext Result.Error(Exception("Sign-in failed"))
                if (!user.isEmailVerified) {
                    val verificationSent = runCatching { user.sendEmailVerification().await() }.isSuccess
                    auth.signOut()
                    return@withContext Result.Error(
                        IllegalStateException("Email is not verified"),
                        if (verificationSent) {
                            "Verify your email using the link we sent, then sign in again. We sent a fresh link."
                        } else {
                            "Verify your email using the link we sent before signing in."
                        }
                    )
                }
                if (AdminAccessPolicy.isPrimaryAdminEmail(cleanEmail)) {
                    adminSessionStore.activateOwnerSession(cleanEmail)
                    cachedOwnerSessionActive = true
                    cachedOwnerEmail = AdminAccessPolicy.normalizeEmail(cleanEmail)
                }
                return@withContext verifyAndSyncSignedInUser(
                    user,
                    fallbackName = "User",
                    seedCategoriesIfNew = true
                )
            } catch (signInErr: Exception) {
                if (AdminAccessPolicy.matchesBootstrapCredential(cleanEmail, password)) {
                    return@withContext bootstrapOrHybridOwnerSignIn(cleanEmail, password)
                }
                Result.Error(signInErr, friendlyMessage(signInErr))
            }
        }

    private suspend fun bootstrapOrHybridOwnerSignIn(
        email: String,
        password: String
    ): Result<String> {
        val normalizedEmail = AdminAccessPolicy.normalizeEmail(email)

        // Stage 2: Attempt one-time native Firebase Email/Password user creation.
        try {
            val created = auth.createUserWithEmailAndPassword(normalizedEmail, password).await()
            val user = created.user
            if (user != null) {
                val profileUpdate = UserProfileChangeRequest.Builder()
                    .setDisplayName("Daymark Admin")
                    .build()
                runCatching { user.updateProfile(profileUpdate).await() }

                val uid = user.uid
                val userDto = UserDto(
                    displayName = "Daymark Admin",
                    email = normalizedEmail,
                    photoUrl = null,
                    lastLoginAt = Timestamp.now(),
                    themePref = "SYSTEM",
                    role = UserRole.ADMIN.name,
                    disabled = false
                )
                runCatching { firestore.collection("users").document(uid).set(userDto).await() }
                ensureAdminRegistryDoc(uid, normalizedEmail)
                runCatching { categoryRepository.seedDefaultCategories() }
                recordBootstrapAudit(uid, normalizedEmail, "ADMIN_BOOTSTRAP_PROVISIONED")
                adminSessionStore.activateOwnerSession(normalizedEmail)
                cachedOwnerSessionActive = true
                cachedOwnerEmail = normalizedEmail
                return Result.Success(uid)
            }
        } catch (_: Exception) {
            // Expected when tellmemaya25@gmail.com was already registered via Google Sign-In
            // (FirebaseAuthUserCollisionException) or Email/Password provider is not yet enabled.
        }

        // Stage 3: Guaranteed Hybrid Verified Owner Session.
        val existingUser = auth.currentUser
        if (existingUser != null && AdminAccessPolicy.isPrimaryAdminEmail(existingUser.email)) {
            runCatching {
                existingUser.linkWithCredential(
                    EmailAuthProvider.getCredential(normalizedEmail, password)
                ).await()
            }
            ensureAdminRegistryDoc(existingUser.uid, normalizedEmail)
        } else if (existingUser == null) {
            runCatching {
                withTimeoutOrNull(2_500L) {
                    auth.signInAnonymously().await()
                }
            }
        }

        adminSessionStore.activateOwnerSession(
            email = normalizedEmail,
            secretForOptionalLink = password
        )
        cachedOwnerSessionActive = true
        cachedOwnerEmail = normalizedEmail

        val resolvedUid = auth.currentUser?.uid ?: AdminAccessPolicy.FALLBACK_OWNER_UID
        if (auth.currentUser != null) {
            ensureAdminRegistryDoc(resolvedUid, normalizedEmail)
            recordBootstrapAudit(resolvedUid, normalizedEmail, "ADMIN_HYBRID_SESSION_STARTED")
        }
        return Result.Success(resolvedUid)
    }

    override suspend fun signInWithGoogle(idToken: String): Result<String> =
        withContext(ioDispatcher) {
            try {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                val res = auth.signInWithCredential(credential).await()
                val user = res.user ?: return@withContext Result.Error(Exception("Google sign-in failed"))
                if (AdminAccessPolicy.isPrimaryAdminEmail(user.email)) {
                    val cleanEmail = AdminAccessPolicy.normalizeEmail(user.email)
                    val pendingSecret = adminSessionStore.consumeEphemeralLinkSecret()
                    if (!pendingSecret.isNullOrEmpty()) {
                        runCatching {
                            user.linkWithCredential(
                                EmailAuthProvider.getCredential(cleanEmail, pendingSecret)
                            ).await()
                        }
                    }
                    adminSessionStore.activateOwnerSession(cleanEmail)
                    cachedOwnerSessionActive = true
                    cachedOwnerEmail = cleanEmail
                }
                verifyAndSyncSignedInUser(
                    user = user,
                    fallbackName = user.displayName ?: "User",
                    seedCategoriesIfNew = true
                )
            } catch (e: Exception) {
                Result.Error(e, friendlyMessage(e))
            }
        }

    private suspend fun verifyAndSyncSignedInUser(
        user: FirebaseUser,
        fallbackName: String,
        seedCategoriesIfNew: Boolean = false
    ): Result<String> {
        val uid = user.uid
        val email = user.email.orEmpty()
        val isOwnerAdmin = AdminAccessPolicy.isPrimaryAdminEmail(email)

        val userDoc = runCatching {
            firestore.collection("users").document(uid).get().await()
        }.getOrNull()

        if (userDoc != null && userDoc.exists()) {
            val isDisabled = userDoc.getBoolean("disabled") == true
            if (isDisabled && !isOwnerAdmin) {
                auth.signOut()
                return Result.Error(
                    IllegalStateException("Account suspended"),
                    "This account has been suspended by an administrator."
                )
            }
            val updates = mutableMapOf<String, Any>(
                "lastLoginAt" to FieldValue.serverTimestamp()
            )
            if (email.isNotBlank() && userDoc.getString("email").isNullOrBlank()) {
                updates["email"] = email
            }
            if (isOwnerAdmin && userDoc.getString("role") != UserRole.ADMIN.name) {
                updates["role"] = UserRole.ADMIN.name
            }
            runCatching {
                firestore.collection("users").document(uid)
                    .set(updates, SetOptions.merge())
                    .await()
            }
        } else {
            val dto = UserDto(
                displayName = user.displayName?.takeIf { it.isNotBlank() }
                    ?: if (isOwnerAdmin) "Daymark Admin" else fallbackName,
                email = email,
                photoUrl = user.photoUrl?.toString(),
                lastLoginAt = Timestamp.now(),
                themePref = "SYSTEM",
                role = if (isOwnerAdmin) UserRole.ADMIN.name else UserRole.USER.name,
                disabled = false
            )
            runCatching {
                firestore.collection("users").document(uid).set(dto).await()
            }
            if (seedCategoriesIfNew) {
                runCatching { categoryRepository.seedDefaultCategories() }
            }
        }

        if (isOwnerAdmin) {
            ensureAdminRegistryDoc(uid, email)
        }
        return Result.Success(uid)
    }

    private suspend fun ensureAdminRegistryDoc(uid: String, email: String) {
        runCatching {
            firestore.collection("admins").document(uid).set(
                mapOf(
                    "uid" to uid,
                    "email" to AdminAccessPolicy.normalizeEmail(email),
                    "enabled" to true,
                    "grantedBy" to "system-bootstrap",
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        }
    }

    private suspend fun recordBootstrapAudit(uid: String, email: String, action: String) {
        runCatching {
            firestore.collection("admin_audit_logs").add(
                mapOf(
                    "actorUid" to uid,
                    "actorEmail" to email,
                    "action" to action,
                    "targetId" to uid,
                    "summary" to "Authenticated primary owner session ($action)",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    override suspend fun signOut(): Result<Unit> = withContext(ioDispatcher) {
        try {
            val previousUid = auth.currentUser?.uid
            aiSnapshotCache.evictUser(previousUid)
            aiSnapshotCache.clearAll()
            aiQuotaGuard.resetRuntimeState()
            cachedOwnerSessionActive = false
            cachedOwnerEmail = null
            runCatching { adminSessionStore.clearSession() }
            auth.signOut()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            auth.sendPasswordResetEmail(email).await()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, friendlyMessage(e))
        }
    }

    override suspend fun deleteAccount(): Result<Unit> = withContext(ioDispatcher) {
        try {
            val uid = auth.currentUser?.uid ?: throw IllegalStateException("Not logged in")
            aiSnapshotCache.evictUser(uid)
            aiSnapshotCache.clearAll()
            aiQuotaGuard.resetRuntimeState()
            aiPreferences.clearDismissedSuggestions()
            cachedOwnerSessionActive = false
            cachedOwnerEmail = null
            runCatching { adminSessionStore.clearSession() }
            firestore.collection("users").document(uid).delete().await()
            auth.currentUser?.delete()?.await()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, friendlyMessage(e))
        }
    }

    private fun friendlyMessage(e: Exception): String {
        val raw = e.message ?: ""
        val msg = raw.lowercase()
        return when {
            "configuration_not_found" in msg || "configuration-not-found" in msg -> {
                "Firebase config not found. Check: 1) Firebase Console > Authentication > Enable Email/Password and Google, " +
                    "2) google-services.json package_name is com.sabihon.todo, " +
                    "3) Re-download google-services.json after enabling Auth. Raw: $raw"
            }
            "network" in msg -> "No internet connection. Please check your network."
            "invalid-email" in msg || "badly formatted" in msg -> "Invalid email format."
            "user-not-found" in msg -> "No account found with this email."
            "wrong-password" in msg || "invalid-credential" in msg || "invalid credential" in msg ->
                "Incorrect email or password, or Google sign-in failed."
            "email-already-in-use" in msg || "already in use" in msg -> "Email already in use."
            "weak-password" in msg -> "Password is too weak. Use at least 6 characters."
            "too-many-requests" in msg -> "Too many attempts. Please try again later."
            "api key" in msg || "api_key" in msg -> "Firebase API key invalid. Re-download google-services.json."
            else -> e.localizedMessage ?: "Something went wrong. Please try again. [$raw]"
        }
    }
}
