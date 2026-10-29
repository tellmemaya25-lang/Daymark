package com.sabihon.todo.ui.auth

/**
 * Immutable UiState per screen.
 */
data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /**
     * Set once a reset request has been accepted by Firebase. Holds the address it was
     * sent to, and swaps the form for the "check your inbox" panel.
     */
    val resetSentTo: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val displayNameError: String? = null,
    val confirmPasswordError: String? = null,
    val isLoggedIn: Boolean = false,
    /** Non-null after Firebase sends the signup verification link. */
    val verificationEmailSentTo: String? = null
)
