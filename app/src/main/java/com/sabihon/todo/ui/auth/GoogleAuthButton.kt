package com.sabihon.todo.ui.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions

/**
 * Reusable Google OAuth 2.0 sign-in button.
 * Uses play-services-auth with Firebase Auth.
 * Requires google-services.json and default_web_client_id.
 */
@Composable
fun GoogleAuthButton(
    onIdTokenReceived: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    buttonText: String = "Continue with Google"
) {
    val context = LocalContext.current

    // Build GoogleSignInClient – requestIdToken is mandatory for Firebase
    // Use getIdentifier to avoid compile-time dependency on generated string
    val defaultWebClientId = remember {
        try {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId != 0) context.getString(resId) else ""
        } catch (e: Exception) {
            ""
        }
    }

    val gso = remember(defaultWebClientId) {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        if (defaultWebClientId.isNotBlank()) {
            builder.requestIdToken(defaultWebClientId)
        }
        builder.build()
    }
    val googleSignInClient = remember(gso) { GoogleSignIn.getClient(context, gso) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            val idToken = account?.idToken
            if (idToken != null) {
                onIdTokenReceived(idToken)
            } else {
                onError("Google sign-in failed: No ID token. Check Firebase Console > Auth > Google provider enabled and SHA-1 configured.")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Google sign-in failed"
            // Common: ApiException 10 = DEVELOPER_ERROR – usually SHA-1 missing or client ID mismatch
            if (msg.contains("10") || msg.contains("DEVELOPER_ERROR")) {
                onError("Google sign-in config error (10). Add SHA-1 in Firebase Console > Project Settings > Your apps.")
            } else {
                onError("Google sign-in failed: $msg")
            }
        }
    }

    OutlinedButton(
        onClick = {
            if (defaultWebClientId.isBlank()) {
                onError("Missing default_web_client_id. Enable Google Sign-In in Firebase Console > Authentication > Sign-in method > Google > Enable, then re-download google-services.json.")
                return@OutlinedButton
            }
            try {
                // Sign out previous to force account chooser
                googleSignInClient.signOut().addOnCompleteListener {
                    val intent = googleSignInClient.signInIntent
                    launcher.launch(intent)
                }
            } catch (e: Exception) {
                onError("Failed to launch Google sign-in: ${e.message}")
            }
        },
        modifier = modifier.fillMaxWidth(),
        enabled = !isLoading,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Icon(
            imageVector = Icons.Filled.AccountCircle,
            contentDescription = "Google",
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(buttonText)
    }
}
