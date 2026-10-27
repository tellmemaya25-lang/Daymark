package com.sabihon.todo.ui.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Create account.
 *
 * Same frame as Sign in. One password field, not two: the reference has one, and the
 * Show toggle is the check that matters - a second hidden field mostly catches the copy
 * of a typo rather than the typo itself. `AuthViewModel.validateSignUp` still compares
 * the two when a confirm value is supplied, so re-adding the field later needs no logic.
 *
 * The prototype disclaimer from the mock ("no account is created, nothing is saved") is
 * not reproduced: this screen creates a real Firebase account.
 */
@Composable
fun SignUpScreen(
    onNavigateBack: () -> Unit,
    onSignUpSuccess: () -> Unit,
    onSignUpSuccessAdmin: () -> Unit = onSignUpSuccess,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    AuthScaffold(
        headlineLead = "A fresh start.",
        headlineAccent = "All yours.",
        supporting = "Make space for your plans, one little step at a time.",
        snackbarHostState = snackbarHostState,
        onBack = onNavigateBack
    ) {
        AuthField(
            label = "Your name",
            value = uiState.displayName,
            onValueChange = viewModel::onDisplayNameChange,
            placeholder = "What should we call you?",
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
            error = uiState.displayNameError
        )

        Spacer(Modifier.height(14.dp))

        AuthField(
            label = "Email address",
            value = uiState.email,
            onValueChange = viewModel::onEmailChange,
            placeholder = "you@example.com",
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            error = uiState.emailError
        )

        Spacer(Modifier.height(14.dp))

        AuthField(
            label = "Password",
            value = uiState.password,
            onValueChange = viewModel::onPasswordChange,
            placeholder = "Create a password",
            isPassword = true,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            error = uiState.passwordError,
            helper = "Use at least 6 characters."
        )

        Spacer(Modifier.height(24.dp))

        AuthPrimaryButton(
            text = "Create account",
            isLoading = uiState.isLoading,
            onClick = { viewModel.signUp(onSuccess = onSignUpSuccess, onAdminSuccess = onSignUpSuccessAdmin) }
        )

        Spacer(Modifier.height(22.dp))

        AuthDivider()

        Spacer(Modifier.height(16.dp))

        GoogleAuthButton(
            onIdTokenReceived = { token ->
                viewModel.signInWithGoogle(
                    idToken = token,
                    onSuccess = onSignUpSuccess,
                    onAdminSuccess = onSignUpSuccessAdmin
                )
            },
            onError = { err -> viewModel.setError(err) },
            isLoading = uiState.isLoading,
            buttonText = "Sign up with Google"
        )

        Spacer(Modifier.height(6.dp))

        AuthSwitchLink(
            prefix = "Already have an account?",
            action = "Sign in",
            onClick = onNavigateBack
        )
    }
}
