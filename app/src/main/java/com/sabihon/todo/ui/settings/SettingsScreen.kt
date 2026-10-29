package com.sabihon.todo.ui.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sabihon.todo.core.datastore.ThemePref
import com.sabihon.todo.core.ui.components.GroupedCard
import com.sabihon.todo.core.ui.components.GroupedDivider
import com.sabihon.todo.core.ui.components.GroupedRow
import com.sabihon.todo.core.ui.components.ProfileAvatar
import com.sabihon.todo.core.ui.components.SectionLabel
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.DaymarkSheetShape
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.domain.reminder.ReminderAlertStyle
import com.sabihon.todo.domain.reminder.ReminderPolicy
import com.sabihon.todo.domain.reminder.ReminderSettings
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
    onNavigateToAdmin: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showThemeSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

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

                    ReminderSettingsSection(
                        enabled = uiState.remindersEnabled,
                        leadMinutes = uiState.reminderLeadMinutes,
                        alertStyle = uiState.reminderAlertStyle,
                        snoozeMinutes = uiState.reminderSnoozeMinutes,
                        onToggleEnabled = viewModel::setRemindersEnabled,
                        onLeadChange = viewModel::setReminderLead,
                        onStyleChange = viewModel::setReminderAlertStyle,
                        onSnoozeChange = viewModel::setReminderSnoozeMinutes
                    )

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
                                    text = "Prioritize tasks and smart-fill with Smart AI. Prompts go to a cloud AI service while this is on; offline smart rules always stay on-device.",
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
                        GroupedRow(
                            label = "Today's cloud requests",
                            // One number, no vendor names: how many calls today, and the
                            // budget behind it stays a detail of Admin.
                            supporting = "Tap to clear the cached focus plan",
                            value = "${uiState.aiDailyCalls + uiState.aiClaudeDailyCalls} today",
                            onClick = { viewModel.clearAiCache() }
                        )
                        if (uiState.isAdmin) {
                            // Credentials moved to the Admin Dashboard so a shipped build
                            // has no key or token surface in the everyday Settings page.
                            GroupedDivider()
                            GroupedRow(
                                label = "AI credentials & App Check",
                                supporting = "API keys now live in Admin",
                                value = "Admin",
                                onClick = onNavigateToAdmin
                            )
                        }
                    }

                    if (uiState.isAdmin) {
                        Spacer(Modifier.height(12.dp))

                        SectionLabel("ADMIN")
                        GroupedCard {
                            GroupedRow(
                                label = "Admin Dashboard",
                                supporting = "Platform analytics, users, AI config & audit log",
                                value = "Open",
                                onClick = onNavigateToAdmin
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

/**
 * Settings > REMINDERS.
 *
 * Four choices, each showing its current value on the row, plus two rows that only appear when
 * Android is standing in the way (notifications denied, exact alarms not granted). The system
 * rows are deliberately not switches inside the app: neither permission can be granted here, so
 * pretending otherwise would produce a toggle that quietly does nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderSettingsSection(
    enabled: Boolean,
    leadMinutes: Int,
    alertStyle: ReminderAlertStyle,
    snoozeMinutes: Int,
    onToggleEnabled: (Boolean) -> Unit,
    onLeadChange: (Int) -> Unit,
    onStyleChange: (ReminderAlertStyle) -> Unit,
    onSnoozeChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var exactAlarmsAllowed by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    var openSheet by remember { mutableStateOf(ReminderSheet.NONE) }

    // Both answers live in Android, not in this app, so they can only change while the user is
    // away in system settings. Re-reading on resume is what makes the row disappear once the
    // permission is actually granted, instead of leaving a stale "Allow" behind.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed =
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                exactAlarmsAllowed = canScheduleExactAlarms(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column {
        SectionLabel("REMINDERS")
        GroupedCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reminders",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (enabled) {
                            "Tasks you set a reminder on will notify you"
                        } else {
                            "Off - nothing is scheduled, existing reminders stay silent"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { onToggleEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentBlue
                    )
                )
            }

            GroupedDivider()
            GroupedRow(
                label = "Default reminder",
                supporting = "Applied to new and edited reminders",
                value = ReminderPolicy.describeLead(leadMinutes),
                onClick = { openSheet = ReminderSheet.LEAD }
            )

            GroupedDivider()
            GroupedRow(
                label = "Alert style",
                supporting = ReminderPolicy.describeStyle(alertStyle),
                value = alertStyle.label,
                onClick = { openSheet = ReminderSheet.STYLE }
            )

            GroupedDivider()
            GroupedRow(
                label = "Snooze length",
                supporting = "How long the Snooze button waits",
                value = "$snoozeMinutes min",
                onClick = { openSheet = ReminderSheet.SNOOZE }
            )

            if (!notificationsAllowed) {
                GroupedDivider()
                GroupedRow(
                    label = "Notifications are off",
                    supporting = "Reminders cannot be shown until Android allows them",
                    value = "Allow",
                    onClick = { openNotificationSettings(context) }
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAlarmsAllowed) {
                GroupedDivider()
                GroupedRow(
                    label = "Exact alarms not allowed",
                    supporting = "Reminders may arrive a few minutes late",
                    value = "Allow",
                    onClick = { requestExactAlarms(context) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (alertStyle == ReminderAlertStyle.ALARM) {
                "Alarm wakes the screen and shows the reminder over the lock screen."
            } else {
                "Sound and vibration are managed by Android once the channel is created."
            },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }

    when (openSheet) {
        ReminderSheet.NONE -> Unit
        ReminderSheet.LEAD -> OptionSheet(
            title = "Default reminder",
            options = ReminderSettings.LEAD_OPTIONS.map { ReminderPolicy.describeLead(it) },
            selectedIndex = ReminderSettings.LEAD_OPTIONS.indexOf(leadMinutes)
                .coerceAtLeast(0),
            onSelect = { index ->
                onLeadChange(ReminderSettings.LEAD_OPTIONS[index])
                openSheet = ReminderSheet.NONE
            },
            onDismiss = { openSheet = ReminderSheet.NONE }
        )
        ReminderSheet.STYLE -> OptionSheet(
            title = "Alert style",
            options = ReminderAlertStyle.entries.map { "${it.label} - ${it.summary}" },
            selectedIndex = ReminderAlertStyle.entries.indexOf(alertStyle).coerceAtLeast(0),
            onSelect = { index ->
                onStyleChange(ReminderAlertStyle.entries[index])
                openSheet = ReminderSheet.NONE
            },
            onDismiss = { openSheet = ReminderSheet.NONE }
        )
        ReminderSheet.SNOOZE -> OptionSheet(
            title = "Snooze length",
            options = ReminderSettings.SNOOZE_OPTIONS.map { "$it minutes" },
            selectedIndex = ReminderSettings.SNOOZE_OPTIONS.indexOf(snoozeMinutes)
                .coerceAtLeast(0),
            onSelect = { index ->
                onSnoozeChange(ReminderSettings.SNOOZE_OPTIONS[index])
                openSheet = ReminderSheet.NONE
            },
            onDismiss = { openSheet = ReminderSheet.NONE }
        )
    }
}

private enum class ReminderSheet { NONE, LEAD, STYLE, SNOOZE }

/** A plain radio list in a sheet: one choice, always exactly one selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionSheet(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = DaymarkSheetShape,
        containerColor = surfaces.card,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            options.forEachIndexed { index, label ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .selectable(
                            selected = index == selectedIndex,
                            role = Role.RadioButton,
                            onClick = { onSelect(index) }
                        )
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (index == selectedIndex) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** True when this app may fire an alarm at an exact minute. */
private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
    return manager.canScheduleExactAlarms()
}

/**
 * Opens the system screen that can grant exact alarms.
 *
 * The outcome is deliberately not reported here: it is only knowable after the user comes back,
 * and the section re-reads it on resume. Claiming a result on the way out would be a guess.
 */
private fun requestExactAlarms(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        android.util.Log.e("Settings", "No exact-alarm settings screen on this device", e)
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
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
