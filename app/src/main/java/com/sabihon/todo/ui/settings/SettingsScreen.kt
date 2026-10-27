package com.sabihon.todo.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.SabihonApp
import com.sabihon.todo.core.datastore.AiPreferences
import com.sabihon.todo.core.datastore.ThemePref
import com.sabihon.todo.core.ui.components.GroupedCard
import com.sabihon.todo.core.ui.components.GroupedDivider
import com.sabihon.todo.core.ui.components.GroupedRow
import com.sabihon.todo.core.ui.components.ProfileAvatar
import com.sabihon.todo.core.ui.components.SectionLabel
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.DaymarkSheetShape
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.ui.profile.ProfileViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings.
 *
 * Rebuilt around what Android's own settings guidance asks for: a handful of groups
 * with a heading each, one card per group, whitespace instead of dividers between
 * groups, and the current value of every choice shown on its row. The previous version
 * was four visually different card styles - a theme picker with three preview tiles, a
 * second "live preview" card that repeated the same information, list cards with
 * 40 dp icon chips, and an About card holding a tech-stack and feature dump. It also
 * had a "More info" row wired to a callback nobody passed, so it did nothing.
 *
 * Concretely:
 *  - the four theme tiles collapse to one row whose value reads System / Light / Dark;
 *    tapping opens a short sheet so the page itself stays calm
 *  - Profile and Activity become plain rows in grouped cards, with the account's name
 *    and email carried on the row instead of a generic "Edit profile and picture"
 *  - the version lives in one quiet row, and the tech stack is gone - it belongs on a
 *    store listing, not in settings
 *  - the back arrow is gone too: Settings is a top-level tab, the tab bar is the way out
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToHistory: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showThemeSheet by remember { mutableStateOf(false) }
    var showApiKeySheet by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDebugBuild = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    val firebaseProjectId = remember(context) { SabihonApp.firebaseProjectId(context) }
    var debugAppCheckToken by remember(context) {
        mutableStateOf(if (isDebugBuild) SabihonApp.readAppCheckDebugToken(context) else null)
    }

    LaunchedEffect(isDebugBuild) {
        if (isDebugBuild && debugAppCheckToken == null) {
            delay(600L)
            debugAppCheckToken = SabihonApp.readAppCheckDebugToken(context)
        }
    }

    LaunchedEffect(uiState.successMessage) {
        val message = uiState.successMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
                    Text(
                        text = "Your app, your way.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Settings.",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 30.sp,
                            lineHeight = 35.sp,
                            letterSpacing = (-0.8).sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SectionLabel("APPEARANCE")
                    GroupedCard {
                        GroupedRow(
                            label = "Theme",
                            supporting = "System, light or dark",
                            value = uiState.theme.label,
                            onClick = { showThemeSheet = true }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    SectionLabel("AI")
                    GroupedCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Smart AI",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "Prioritize tasks and smart-fill with Gemini. Free-tier prompts may be processed by Google; offline smart rules always stay on-device.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = uiState.aiCloudEnabled,
                                onCheckedChange = viewModel::setAiCloudEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = AccentBlue
                                )
                            )
                        }
                        GroupedDivider()
                        val configuredKey = uiState.geminiApiKey.trim()
                        val keyStatus = if (configuredKey.length > 8) {
                            "${configuredKey.take(4)}…${configuredKey.takeLast(4)}"
                        } else if (configuredKey.isNotEmpty()) {
                            "Saved"
                        } else {
                            "Set key"
                        }
                        GroupedRow(
                            label = "Gemini API key",
                            supporting = "Free at aistudio.google.com/apikey — no Blaze plan needed",
                            value = keyStatus,
                            onClick = {
                                apiKeyDraft = uiState.geminiApiKey
                                apiKeyVisible = false
                                showApiKeySheet = true
                            }
                        )
                        GroupedDivider()
                        GroupedRow(
                            label = "Today's cloud requests",
                            supporting = "Tap to clear cached focus plan",
                            value = "${uiState.aiDailyCalls} / ${AiPreferences.MAX_DAILY_CLOUD_CALLS}",
                            onClick = { viewModel.clearAiCache() }
                        )
                        if (isDebugBuild) {
                            GroupedDivider()
                            val shortToken = debugAppCheckToken?.let { token ->
                                if (token.length > 12) "${token.take(8)}…${token.takeLast(4)}" else token
                            } ?: "Copy"
                            val projectLine = firebaseProjectId?.let { "Project: $it · " }.orEmpty()
                            GroupedRow(
                                label = "App Check debug token",
                                supporting = "${projectLine}Tap to copy for Firebase Console > App Check",
                                value = shortToken,
                                onClick = {
                                    scope.launch {
                                        val token = SabihonApp.readAppCheckDebugToken(context)
                                            ?: run {
                                                delay(500L)
                                                SabihonApp.readAppCheckDebugToken(context)
                                            }
                                        debugAppCheckToken = token
                                        if (!token.isNullOrBlank()) {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            clipboard?.setPrimaryClip(ClipData.newPlainText("App Check Debug Token", token))
                                            snackbarHostState.showSnackbar(
                                                message = "Copied App Check debug token ($token)",
                                                duration = SnackbarDuration.Short
                                            )
                                        } else {
                                            snackbarHostState.showSnackbar(
                                                message = "Generating debug token — tap again in a second",
                                                duration = SnackbarDuration.Short
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    SectionLabel("ACCOUNT")
                    GroupedCard {
                        GroupedRow(
                            label = uiState.displayName.ifBlank { "Profile" },
                            supporting = uiState.email.ifBlank { "Not signed in" },
                            leading = {
                                ProfileAvatar(
                                    initials = uiState.displayName.take(2).uppercase()
                                        .ifBlank { "DM" },
                                    photoUrl = uiState.localPhotoUri?.toString() ?: uiState.photoUrl
                                )
                            },
                            onClick = onNavigateToProfile
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    SectionLabel("ACTIVITY")
                    GroupedCard {
                        GroupedRow(
                            label = "Activity",
                            supporting = "Completed tasks and your log",
                            onClick = onNavigateToHistory
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    SectionLabel("ABOUT")
                    GroupedCard {
                        // Read-only on purpose: no chevron, because there is nowhere to go
                        // and a dead arrow is worse than no arrow.
                        GroupedRow(
                            label = "daymark .",
                            supporting = "A quiet place for what matters",
                            value = "v1.0",
                            onClick = null
                        )
                    }

                    Spacer(Modifier.height(110.dp))
                }
            }
        }
    }

    if (showApiKeySheet) {
        ModalBottomSheet(
            onDismissRequest = { showApiKeySheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = DaymarkSheetShape,
            containerColor = LocalDaymarkSurfaces.current.card,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 28.dp)
            ) {
                Text(
                    "Free Gemini API key",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Skip the Firebase Blaze upgrade: create a free key at aistudio.google.com/apikey (no credit card required) and paste it below. Stored only on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    placeholder = { Text("AIzaSy...") },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector = if (apiKeyVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (apiKeyVisible) {
                                    "Hide API key"
                                } else {
                                    "Show API key"
                                },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        viewModel.saveGeminiApiKey(apiKeyDraft)
                        showApiKeySheet = false
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(
                        text = if (apiKeyDraft.isBlank()) "Clear key" else "Save Gemini key",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = { showApiKeySheet = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }

    if (showThemeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showThemeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = DaymarkSheetShape,
            containerColor = LocalDaymarkSurfaces.current.card,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 28.dp)
            ) {
                Text(
                    "Theme",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "System follows your device. The choice applies straight away.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemePref.entries.forEach { pref ->
                        ThemeOptionRow(
                            pref = pref,
                            selected = uiState.theme == pref,
                            onSelect = {
                                viewModel.setTheme(pref)
                                showThemeSheet = false
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Selecting applies immediately, so this is a "close", not a "cancel" -
                // and it keeps the sheet dismissible without a gesture.
                TextButton(
                    onClick = { showThemeSheet = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The value shown on the Theme row. */
private val ThemePref.label: String
    get() = when (this) {
        ThemePref.SYSTEM -> "System"
        ThemePref.LIGHT -> "Light"
        ThemePref.DARK -> "Dark"
    }

private val ThemePref.supporting: String
    get() = when (this) {
        ThemePref.SYSTEM -> "Following your device"
        ThemePref.LIGHT -> "Always light"
        ThemePref.DARK -> "Always dark"
    }

/**
 * One choice in the theme sheet.
 *
 * The swatch is the preview the old three-tile picker was trying to give - as a 32 dp
 * chip inside the row rather than as a card the size of a business card. "System" is
 * split light/dark because that is literally what it does.
 */
@Composable
private fun ThemeOptionRow(
    pref: ThemePref,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                else Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemeSwatch(pref)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pref.label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = pref.supporting,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ThemeSwatch(pref: ThemePref) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(shape)
            .border(1.dp, outline, shape)
    ) {
        when (pref) {
            ThemePref.SYSTEM -> Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFFF5F6FA))
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF121214))
                )
            }
            ThemePref.LIGHT -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF5F6FA))
            )
            ThemePref.DARK -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121214))
            )
        }
    }
}
