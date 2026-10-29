package com.sabihon.todo.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.firebase.FirebaseApp
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces

/**
 * Reset password.
 *
 * Rebuilt on the shared auth frame so that stepping here from Sign in does not look like
 * leaving the app - the same brand row, headline treatment, labelled field and pill
 * action.
 *
 * Two states, because a snackbar was the wrong shape for this screen:
 *
 *  - **form** — enter an address, get it validated locally before anything is sent.
 *  - **sent** — a persistent "check your inbox" panel. Firebase resolves
 *    `sendPasswordResetEmail` even when it sends nothing (no such account, or a
 *    Google-only account), so a disappearing ticker is the worst possible feedback: it
 *    claims success, then leaves the user staring at a form with no explanation. The
 *    panel instead says what is actually known, and what to try if nothing arrives.
 */
@Composable
fun ForgotPasswordScreen(
    onNavigateBack: () -> Unit,
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

    // The From: address the mail will carry, so the user knows what to search for. Read
    // from the initialised Firebase app; null on any failure just means generic copy.
    val projectId = remember {
        runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull()
    }

    val sentTo = uiState.resetSentTo

    AuthScaffold(
        headlineLead = if (sentTo == null) "Forgot it?" else "Check your",
        headlineAccent = if (sentTo == null) "We'll send a link." else "inbox.",
        supporting = if (sentTo == null) {
            "Enter the email you signed up with and we'll send a reset link."
        } else {
            "One more step and you're back in."
        },
        snackbarHostState = snackbarHostState,
        onBack = onNavigateBack
    ) {
        if (sentTo == null) {
            ResetForm(
                email = uiState.email,
                emailError = uiState.emailError,
                isLoading = uiState.isLoading,
                onEmailChange = viewModel::onEmailChange,
                onSend = { viewModel.sendPasswordReset() },
                onBack = onNavigateBack
            )
        } else {
            ResetSentPanel(
                email = sentTo,
                projectId = projectId,
                isLoading = uiState.isLoading,
                onResend = { viewModel.sendPasswordReset() },
                onEditEmail = { viewModel.editResetEmail() },
                onBack = onNavigateBack
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Form                                                                */
/* ------------------------------------------------------------------ */

@Composable
private fun ResetForm(
    email: String,
    emailError: String?,
    isLoading: Boolean,
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit
) {
    AuthField(
        label = "Email address",
        value = email,
        onValueChange = onEmailChange,
        placeholder = "you@example.com",
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Done,
        error = emailError
    )

    Spacer(Modifier.height(24.dp))

    AuthPrimaryButton(
        text = "Send reset link",
        isLoading = isLoading,
        onClick = onSend
    )

    Spacer(Modifier.height(20.dp))

    AuthNote(
        text = "The link opens a page where you set a new password, then you can sign " +
            "in here with it. Check your spam folder if it does not arrive in a minute."
    )

    Spacer(Modifier.height(6.dp))

    AuthSwitchLink(
        prefix = "Remembered it?",
        action = "Back to sign in",
        onClick = onBack
    )
}

/* ------------------------------------------------------------------ */
/* Sent                                                                */
/* ------------------------------------------------------------------ */

@Composable
private fun ResetSentPanel(
    email: String,
    projectId: String?,
    isLoading: Boolean,
    onResend: () -> Unit,
    onEditEmail: () -> Unit,
    onBack: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(surfaces.card)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(surfaces.action),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = surfaces.onAction,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Request sent",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = PasswordResetPolicy.sentDetail(email),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(18.dp))

    AuthNote(
        text = PasswordResetPolicy.troubleshooting(projectId)
            .mapIndexed { index, line -> "${index + 1}.  $line" }
            .joinToString("\n\n")
    )

    Spacer(Modifier.height(22.dp))

    AuthPrimaryButton(
        text = "Send it again",
        isLoading = isLoading,
        onClick = onResend
    )

    Spacer(Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        AuthInlineLink(text = "Use a different email", onClick = onEditEmail)
    }

    Spacer(Modifier.height(2.dp))

    AuthSwitchLink(
        prefix = "Remembered it?",
        action = "Back to sign in",
        onClick = onBack
    )
}
