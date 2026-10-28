package com.sabihon.todo.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Sign in.
 *
 * Rebuilt to the reference: brand row, two-line display headline with the accent on the
 * second line, supporting copy, labelled fields, the switch at the foot. The previous
 * version opened straight into two floating-label boxes under a 28 sp title, so the page
 * read as a form first and a brand second.
 *
 * There is no back button here on purpose. Login is reached from onboarding with the
 * onboarding entry popped, so an arrow would drop the user out of the app - the mock has
 * one because it is a prototype switcher, not because there is somewhere to go.
 */
@Composable
fun LoginScreen(
    onNavigateToSignUp: () -> Unit,
    onNavigateToForgot: () -> Unit,
    onLoginSuccess: () -> Unit,
    onLoginSuccessAdmin: () -> Unit = onLoginSuccess,
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
        headlineLead = "Welcome back.",
        headlineAccent = "Find your focus.",
        supporting = "Pick up where you left off. Your next little step is waiting.",
        snackbarHostState = snackbarHostState
    ) {
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
            placeholder = "Enter your password",
            isPassword = true,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            error = uiState.passwordError,
            helper = "At least 6 characters."
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AuthInlineLink(text = "Forgot password?", onClick = onNavigateToForgot)
        }

        Spacer(Modifier.height(14.dp))

        AuthPrimaryButton(
            text = "Sign in",
            isLoading = uiState.isLoading,
            onClick = { viewModel.signIn(onSuccess = onLoginSuccess, onAdminSuccess = onLoginSuccessAdmin) }
        )

        Spacer(Modifier.height(22.dp))

        AuthDivider()

        Spacer(Modifier.height(16.dp))

        GoogleAuthButton(
            onIdTokenReceived = { token ->
                viewModel.signInWithGoogle(
                    idToken = token,
                    onSuccess = onLoginSuccess,
                    onAdminSuccess = onLoginSuccessAdmin
                )
            },
            onError = { err -> viewModel.setError(err) },
            isLoading = uiState.isLoading,
            buttonText = "Continue with Google"
        )

        Spacer(Modifier.height(6.dp))

        AuthSwitchLink(
            prefix = "New to daymark .?",
            action = "Create account",
            onClick = onNavigateToSignUp
        )
    }
}
