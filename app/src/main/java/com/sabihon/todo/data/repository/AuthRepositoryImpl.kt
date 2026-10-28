package com.sabihon.todo.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase Auth implementation – creates users/{uid} doc, enforces suspended-account
 * checks, and bootstraps/verifies Admin RBAC privileges.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val categoryRepository: CategoryRepository,
    private val aiSnapshotCache: AiSnapshotCache,
    private val aiQuotaGuard: AiQuotaGuard,
    private val aiPreferences: AiPreferenceStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : AuthRepository {

    override fun getCurrentUserId(): String? = auth.currentUser?.uid
    override fun getCurrentUserEmail(): String? = auth.currentUser?.email
    override fun isUserLoggedIn(): Boolean = auth.currentUser != null

    override suspend fun isCurrentUserAdmin(): Boolean = withContext(ioDispatcher) {
        val user = auth.currentUser ?: return@withContext false
        if (AdminAccessPolicy.isPrimaryAdminEmail(user.email)) {
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

    override fun observeAuthState(): Flow<Boolean> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser != null)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override suspend fun signUp(email: String, password: String, displayName: String): Result<String> =
        withContext(ioDispatcher) {
            try {
                val cleanEmail = email.trim()
                val res = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
                val user = res.user ?: return@withContext Result.Error(Exception("User creation failed"))
                val profileUpdate = UserProfileChangeRequest.Builder()
                    .setDisplayName(displayName)
                    .build()
                user.updateProfile(profileUpdate).await()

                val uid = user.uid
                val assignedRole = if (AdminAccessPolicy.isPrimaryAdminEmail(cleanEmail)) {
                    UserRole.ADMIN.name
                } else {
                    UserRole.USER.name
                }
                val userDto = UserDto(
                    displayName = displayName,
                    email = cleanEmail,
                    photoUrl = null,
                    lastLoginAt = Timestamp.now(),
                    themePref = "SYSTEM",
                    role = assignedRole,
                    disabled = false
                )
                firestore.collection("users").document(uid).set(userDto).await()
                if (assignedRole == UserRole.ADMIN.name) {
                    ensureAdminRegistryDoc(uid, cleanEmail)
                }

                categoryRepository.seedDefaultCategories()
                Result.Success(uid)
            } catch (e: Exception) {
                Result.Error(e, friendlyMessage(e))
            }
        }

    override suspend fun signIn(email: String, password: String): Result<String> =
        withContext(ioDispatcher) {
            val cleanEmail = email.trim()
            try {
                val res = auth.signInWithEmailAndPassword(cleanEmail, password).await()
                val user = res.user ?: return@withContext Result.Error(Exception("Sign-in failed"))
                return@withContext verifyAndSyncSignedInUser(user, fallbackName = "User")
            } catch (signInErr: Exception) {
                if (AdminAccessPolicy.matchesBootstrapCredential(cleanEmail, password)) {
                    return@withContext bootstrapOwnerAdminAccount(cleanEmail, password, signInErr)
                }
                Result.Error(signInErr, friendlyMessage(signInErr))
            }
        }

    private suspend fun bootstrapOwnerAdminAccount(
        email: String,
        password: String,
        originalError: Exception
    ): Result<String> {
        return try {
            val created = auth.createUserWithEmailAndPassword(email, password).await()
            val user = created.user
                ?: return Result.Error(originalError, friendlyMessage(originalError))
            val profileUpdate = UserProfileChangeRequest.Builder()
                .setDisplayName("Daymark Admin")
                .build()
            runCatching { user.updateProfile(profileUpdate).await() }

            val uid = user.uid
            val userDto = UserDto(
                displayName = "Daymark Admin",
                email = email,
                photoUrl = null,
                lastLoginAt = Timestamp.now(),
                themePref = "SYSTEM",
                role = UserRole.ADMIN.name,
                disabled = false
            )
            runCatching { firestore.collection("users").document(uid).set(userDto).await() }
            ensureAdminRegistryDoc(uid, email)
            runCatching { categoryRepository.seedDefaultCategories() }
            recordBootstrapAudit(uid, email)
            Result.Success(uid)
        } catch (createErr: Exception) {
            val msg = (createErr.message ?: "").lowercase()
            if ("email-already-in-use" in msg) {
                Result.Error(
                    createErr,
                    "This admin email is already registered (e.g. via Google Sign-In or an updated password). " +
                        "Sign in with your current password or tap 'Continue with Google', then manage your password in Admin > Security."
                )
            } else {
                Result.Error(originalError, friendlyMessage(originalError))
            }
        }
    }

    override suspend fun signInWithGoogle(idToken: String): Result<String> =
        withContext(ioDispatcher) {
            try {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                val res = auth.signInWithCredential(credential).await()
                val user = res.user ?: return@withContext Result.Error(Exception("Google sign-in failed"))
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

    private suspend fun recordBootstrapAudit(uid: String, email: String) {
        runCatching {
            firestore.collection("admin_audit_logs").add(
                mapOf(
                    "actorUid" to uid,
                    "actorEmail" to email,
                    "action" to "ADMIN_BOOTSTRAP_PROVISIONED",
                    "targetId" to uid,
                    "summary" to "Provisioned primary admin account in Firebase Auth",
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
            "email-already-in-use" in msg -> "Email already in use."
            "weak-password" in msg -> "Password is too weak. Use at least 6 characters."
            "too-many-requests" in msg -> "Too many attempts. Please try again later."
            "api key" in msg || "api_key" in msg -> "Firebase API key invalid. Re-download google-services.json."
            else -> e.localizedMessage ?: "Something went wrong. Please try again. [$raw]"
        }
    }
}
