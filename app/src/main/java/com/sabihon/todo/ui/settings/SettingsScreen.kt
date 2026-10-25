package com.sabihon.todo.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.datastore.ThemePref
import com.sabihon.todo.core.ui.components.BrandHeader
import com.sabihon.todo.core.ui.components.GroupedCard
import com.sabihon.todo.core.ui.components.GroupedRow
import com.sabihon.todo.core.ui.components.ProfileAvatar
import com.sabihon.todo.core.ui.components.SectionLabel
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.DaymarkSheetShape
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.ui.profile.ProfileViewModel

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

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                BrandHeader(
                    avatarInitials = uiState.displayName.take(2).uppercase().ifBlank { "DM" },
                    avatarUrl = uiState.localPhotoUri?.toString() ?: uiState.photoUrl,
                    onAvatarClick = onNavigateToProfile,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)
                )
            }

            item {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp)) {
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
