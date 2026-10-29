package com.sabihon.todo.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Login, SignUp, ForgotPassword – now with Google OAuth 2.0 support.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // Kept only in memory while the verification panel is open, for the resend action.
    private var pendingVerificationPassword: String? = null

    fun onEmailChange(email: String) {
        _uiState.update { it.copy(email = email, emailError = null, errorMessage = null) }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, passwordError = null, errorMessage = null) }
    }

    fun onDisplayNameChange(name: String) {
        _uiState.update { it.copy(displayName = name, displayNameError = null) }
    }

    fun onConfirmPasswordChange(confirm: String) {
        _uiState.update { it.copy(confirmPassword = confirm, confirmPasswordError = null) }
    }

    private fun validateLogin(): Boolean {
        var valid = true
        if (_uiState.value.email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(_uiState.value.email).matches()) {
            _uiState.update { it.copy(emailError = "Enter a valid email") }
            valid = false
        }
        if (_uiState.value.password.length < 6) {
            _uiState.update { it.copy(passwordError = "Password must be at least 6 characters") }
            valid = false
        }
        return valid
    }

    private fun validateSignUp(): Boolean {
        var valid = true
        if (_uiState.value.displayName.isBlank()) {
            _uiState.update { it.copy(displayNameError = "Display name required") }
            valid = false
        }
        if (_uiState.value.email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(_uiState.value.email).matches()) {
            _uiState.update { it.copy(emailError = "Enter a valid email") }
            valid = false
        }
        if (_uiState.value.password.length < 6) {
            _uiState.update { it.copy(passwordError = "Password must be at least 6 characters") }
            valid = false
        }
        // The sign-up form has a single password field (the reference's layout, and the
        // Show toggle covers what a second hidden field was catching), so this only runs
        // when a confirm value is actually present.
        if (_uiState.value.confirmPassword.isNotEmpty() &&
            _uiState.value.confirmPassword != _uiState.value.password
        ) {
            _uiState.update { it.copy(confirmPasswordError = "Passwords do not match") }
            valid = false
        }
        return valid
    }

    fun signIn(onSuccess: () -> Unit, onAdminSuccess: () -> Unit = onSuccess) {
        if (!validateLogin()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authRepository.signIn(_uiState.value.email.trim(), _uiState.value.password)) {
                is Result.Success -> {
                    val isAdmin = authRepository.isCurrentUserAdmin()
                    _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                    if (isAdmin) onAdminSuccess() else onSuccess()
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.message ?: "Login failed") }
                }
                else -> {}
            }
        }
    }

    fun signUp() {
        if (!validateSignUp()) return
        val email = _uiState.value.email.trim()
        val password = _uiState.value.password
        val displayName = _uiState.value.displayName.trim()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authRepository.signUp(email, password, displayName)) {
                is Result.Success -> {
                    pendingVerificationPassword = password
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoggedIn = false,
                            password = "",
                            verificationEmailSentTo = email
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.message ?: "Sign up failed") }
                }
                else -> _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun resendVerificationEmail() {
        val email = _uiState.value.verificationEmailSentTo ?: return
        val password = pendingVerificationPassword
        if (password == null) {
            _uiState.update { it.copy(errorMessage = "Please return to sign up and create the account again.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authRepository.resendEmailVerification(email, password)) {
                is Result.Success -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = "A new verification email was sent to $email.")
                }
                is Result.Error -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = result.message ?: "Could not resend verification email")
                }
                else -> _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun signInWithGoogle(idToken: String, onSuccess: () -> Unit, onAdminSuccess: () -> Unit = onSuccess) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authRepository.signInWithGoogle(idToken)) {
                is Result.Success -> {
                    val isAdmin = authRepository.isCurrentUserAdmin()
                    _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                    if (isAdmin) onAdminSuccess() else onSuccess()
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.message ?: "Google sign-in failed") }
                }
                else -> _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * When the last reset request was accepted, for [PasswordResetPolicy.resendBlockedReason].
     * Zero means "never", which is why the first send is never blocked.
     */
    private var lastResetSentAtMillis = 0L

    fun sendPasswordReset() {
        // Trimmed once and reused for the request, the field and the panel, so the copy
        // can never quote a different string from the one Firebase was given.
        val email = PasswordResetPolicy.normalize(_uiState.value.email)
        val problem = PasswordResetPolicy.validate(email)
        if (problem != null) {
            _uiState.update { it.copy(email = email, emailError = problem, isLoading = false) }
            return
        }

        val now = System.currentTimeMillis()
        PasswordResetPolicy.resendBlockedReason(now, lastResetSentAtMillis)?.let { reason ->
            _uiState.update { it.copy(isLoading = false, errorMessage = reason) }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    email = email,
                    isLoading = true,
                    emailError = null,
                    errorMessage = null
                )
            }
            when (val result = authRepository.sendPasswordReset(email)) {
                is Result.Success -> {
                    lastResetSentAtMillis = now
                    _uiState.update { it.copy(isLoading = false, resetSentTo = email) }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message ?: PasswordResetPolicy.FAILURE
                        )
                    }
                }
                // Not produced by this call, but leaving isLoading true would strand the
                // button in a spinner forever if it ever were.
                else -> _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /** Back from the "check your inbox" panel to the form, with the address kept. */
    fun editResetEmail() {
        _uiState.update { it.copy(resetSentTo = null, emailError = null, errorMessage = null) }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            authRepository.signOut()
            onDone()
        }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, isLoading = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
