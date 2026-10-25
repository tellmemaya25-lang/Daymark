package com.sabihon.todo.ui.auth

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions

/**
 * Reusable Google OAuth 2.0 sign-in button.
 *
 * Uses play-services-auth with Firebase Auth. Needs `default_web_client_id`, which the
 * google-services Gradle plugin generates from `oauth_client[client_type == 3]` in
 * google-services.json - i.e. only once the Google provider has been enabled in
 * Firebase Auth *and* the config re-downloaded. See app/README.md.
 *
 * The id is looked up by name rather than referenced as `R.string.default_web_client_id`
 * because the resource genuinely may not exist: a compile-time reference would fail to
 * build on exactly the misconfigured project that needs to run and explain itself.
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

    // Build GoogleSignInClient – requestIdToken is mandatory for Firebase.
    // Looked up by name: see the class doc for why this is not R.string.*.
    val defaultWebClientId = remember {
        try {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId != 0) context.getString(resId) else ""
        } catch (e: Exception) {
            ""
        }
    }
    val isConfigured = defaultWebClientId.isNotBlank()

    if (!isConfigured) {
        // Logcat only: whoever is building this needs the full console steps, a user
        // does not. Shown once per composition rather than per tap.
        Log.w(
            "GoogleAuth",
            "default_web_client_id is missing, so Google sign-in is disabled. " +
                "Fix: Firebase Console > Authentication > Sign-in method > Google > Enable, " +
                "then Project settings > Your apps > google-services.json > download and " +
                "replace app/google-services.json, then rebuild. " +
                "The file must contain oauth_client with \"client_type\": 3."
        )
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

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                if (!isConfigured) return@OutlinedButton
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
            modifier = Modifier.fillMaxWidth(),
            // Disabled rather than hidden: the option still exists, it just cannot
            // work in this build, and a tappable button that only ever errors is worse
            // than an obviously unavailable one.
            enabled = !isLoading && isConfigured,
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

        if (!isConfigured) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Google sign-in isn't set up in this build.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
