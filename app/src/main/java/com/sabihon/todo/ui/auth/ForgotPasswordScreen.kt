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
 * Reset password.
 *
 * Rebuilt on the shared auth frame so that stepping here from Sign in does not look like
 * leaving the app - the same brand row, headline treatment, labelled field and pill
 * action. The success message still arrives through the snackbar.
 */
@Composable
fun ForgotPasswordScreen(
    onNavigateBack: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage, uiState.successMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    AuthScaffold(
        headlineLead = "Forgot it?",
        headlineAccent = "We'll send a link.",
        supporting = "Enter the email you signed up with and we'll send a reset link.",
        snackbarHostState = snackbarHostState,
        onBack = onNavigateBack
    ) {
        AuthField(
            label = "Email address",
            value = uiState.email,
            onValueChange = viewModel::onEmailChange,
            placeholder = "you@example.com",
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            error = uiState.emailError
        )

        Spacer(Modifier.height(24.dp))

        AuthPrimaryButton(
            text = "Send reset link",
            isLoading = uiState.isLoading,
            onClick = { viewModel.sendPasswordReset() }
        )

        Spacer(Modifier.height(20.dp))

        AuthNote(
            text = "The link signs you in once, then you can set a new password from " +
                "Profile. Check your spam folder if it does not arrive in a minute."
        )

        Spacer(Modifier.height(6.dp))

        AuthSwitchLink(
            prefix = "Remembered it?",
            action = "Back to sign in",
            onClick = onNavigateBack
        )
    }
}
