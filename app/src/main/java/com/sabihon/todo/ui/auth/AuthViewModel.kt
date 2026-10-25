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

    fun signIn(onSuccess: () -> Unit) {
        if (!validateLogin()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authRepository.signIn(_uiState.value.email.trim(), _uiState.value.password)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                    onSuccess()
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.message ?: "Login failed") }
                }
                else -> {}
            }
        }
    }

    fun signUp(onSuccess: () -> Unit) {
        if (!validateSignUp()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authRepository.signUp(
                email = _uiState.value.email.trim(),
                password = _uiState.value.password,
                displayName = _uiState.value.displayName.trim()
            )) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                    onSuccess()
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.message ?: "Sign up failed") }
                }
                else -> {}
            }
        }
    }

    fun signInWithGoogle(idToken: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authRepository.signInWithGoogle(idToken)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                    onSuccess()
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.message ?: "Google sign-in failed") }
                }
                else -> _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun sendPasswordReset() {
        if (_uiState.value.email.isBlank()) {
            _uiState.update { it.copy(emailError = "Enter your email") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            when (val result = authRepository.sendPasswordReset(_uiState.value.email.trim())) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, successMessage = "Reset link sent to ${_uiState.value.email}") }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.message ?: "Failed to send reset email") }
                }
                else -> {}
            }
        }
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
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
