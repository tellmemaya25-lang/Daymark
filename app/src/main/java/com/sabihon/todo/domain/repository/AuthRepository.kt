package com.sabihon.todo.domain.repository

import com.sabihon.todo.core.util.Result
import kotlinx.coroutines.flow.Flow

/**
 * Auth repository contract – never exposes Firebase types.
 */
interface AuthRepository {
    fun getCurrentUserId(): String?
    fun getCurrentUserEmail(): String? = null
    fun isUserLoggedIn(): Boolean
    suspend fun hasActiveSession(): Boolean = isUserLoggedIn()
    suspend fun isCurrentUserAdmin(): Boolean = false
    fun observeAuthState(): Flow<Boolean>
    suspend fun signUp(email: String, password: String, displayName: String): Result<String>
    suspend fun resendEmailVerification(email: String, password: String): Result<Unit>
    suspend fun signIn(email: String, password: String): Result<String>
    suspend fun signInWithGoogle(idToken: String): Result<String>
    suspend fun signOut(): Result<Unit>
    suspend fun sendPasswordReset(email: String): Result<Unit>
    suspend fun deleteAccount(): Result<Unit>
}
