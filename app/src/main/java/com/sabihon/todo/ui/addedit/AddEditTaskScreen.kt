package com.sabihon.todo.ui.addedit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ManagedActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.dictation.DictationController
import com.sabihon.todo.core.dictation.DictationTarget
import com.sabihon.todo.core.dictation.label
import com.sabihon.todo.core.ui.components.AiConsentSheet
import com.sabihon.todo.core.ui.components.ConfirmDialog
import com.sabihon.todo.core.ui.components.DictationMic
import com.sabihon.todo.core.ui.components.DictationStrip
import com.sabihon.todo.core.ui.components.SubTaskRoundedCheckbox
import com.sabihon.todo.core.ui.components.SuggestedStepChip
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.usecase.RepeatScheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Add/Edit Task – fixed inconsistency and color contrast light/dark + 24.dp consistency
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTaskScreen(
    taskId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    initialTitle: String? = null,
    initialDescription: String? = null,
    initialCategoryId: String? = null,
    initialPriority: String? = null,
    initialDueAt: Long? = null,
    viewModel: AddEditViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // ---- dictation ----
    // The recogniser belongs to the screen, not the ViewModel: it is a service
    // connection with a microphone attached, and it must die with the form.
    val context = LocalContext.current
    val recogniserAvailable = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    val dictation = remember(context) { DictationController(context) }
    val dictationState by dictation.state.collectAsState()

    // A start that is waiting on the permission dialog. Held here so the sentence is
    // not lost while the system prompt is on screen.
    var pendingDictation by remember { mutableStateOf<(() -> Unit)?>(null) }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val next = pendingDictation
        pendingDictation = null
        if (granted) next?.invoke() else dictation.permissionDenied()
    }

    // Same "ask when it becomes relevant" rule for reminders: the prompt appears when the user
    // switches a reminder on, not on every launch. Denials are recoverable from Settings.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(dictation) {
        onDispose { dictation.release() }
    }

    fun isSpeaking(target: DictationTarget): Boolean =
        dictationState.isListening && dictationState.target == target

    /**
     * Mic tapped. Already listening here means stop; anywhere else means move.
     *
     * The permission prompt is asked for on the tap that needs it, never when the
     * screen opens, and dictation is never the only way to fill a field - the keyboard
     * is right there either way.
     */
    fun dictate(
        target: DictationTarget,
        baseText: String,
        multiline: Boolean,
        onText: (String) -> Unit
    ) {
        if (!recogniserAvailable) return
        if (isSpeaking(target)) {
            dictation.stop()
            return
        }
        val begin: () -> Unit = { dictation.start(target, baseText, multiline, onText) }
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            begin()
        } else {
            pendingDictation = begin
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(
        taskId,
        initialTitle,
        initialDescription,
        initialCategoryId,
        initialPriority,
        initialDueAt
    ) {
        if (taskId != null) {
            viewModel.loadTask(taskId)
        } else {
            viewModel.loadTask(null)
            viewModel.seedFromDraft(
                title = initialTitle,
                description = initialDescription,
                categoryId = initialCategoryId,
                priorityName = initialPriority,
                dueAt = initialDueAt
            )
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    val initialCal = remember(uiState.dueAt) {
        Calendar.getInstance().apply {
            if (uiState.dueAt != null) timeInMillis = uiState.dueAt!!
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isEditMode) "Edit Task" else "Add Task", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    if (uiState.isEditMode) {
                        IconButton(onClick = { viewModel.setShowDeleteConfirm(true) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Quick add fills the fields below from the sentence typed above. The
            // strip only appears once there is something to say.
            val quickAddMessage = uiState.quickAddSummary ?: uiState.quickAddError
            if (quickAddMessage != null) {
                QuickAddStrip(
                    message = quickAddMessage,
                    canUndo = uiState.quickAddSummary != null,
                    onUndo = viewModel::onUndoQuickAdd,
                    onDismiss = viewModel::onDismissQuickAdd
                )
            }

            // Dictation's one line: which field is listening, or why it stopped.
            val dictationNotice = dictationState.message
            if (dictationState.isListening || dictationNotice != null) {
                DictationStrip(
                    listening = dictationState.isListening,
                    label = dictationState.target?.label,
                    message = dictationNotice,
                    onDone = dictation::stop,
                    onCancel = dictation::cancel,
                    onDismiss = dictation::dismissMessage
                )
            }

            OutlinedTextField(
                value = uiState.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text("Title", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                isError = uiState.titleError != null,
                supportingText = { uiState.titleError?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                // Everything actionable sits on the right, next to the text it acts on:
                // dictate, clear, then fill. The left edge carries no icon any more, so
                // the title starts at the field's own padding and reads as one line of
                // typing rather than a toolbar with a caption.
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (recogniserAvailable) {
                            DictationMic(
                                listening = isSpeaking(DictationTarget.Title),
                                description = "Dictate title",
                                onClick = {
                                    dictate(
                                        target = DictationTarget.Title,
                                        baseText = uiState.title,
                                        multiline = false,
                                        onText = viewModel::onTitleChange
                                    )
                                }
                            )
                        }

                        // Only while there is something to clear: a permanently drawn
                        // "x" on an empty field is a button that does nothing.
                        if (uiState.title.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.onTitleChange("") },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = "Clear title",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        if (uiState.isQuickAddBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = AccentBlue
                            )
                        } else {
                            // Enabled only with text in the box: there is nothing to parse
                            // in an empty sentence, and a dead button is better than a
                            // button that answers "nothing found".
                            IconButton(
                                onClick = viewModel::onQuickAdd,
                                enabled = uiState.title.isNotBlank(),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Filled.AutoAwesome,
                                    contentDescription = "Fill the form from this sentence",
                                    tint = if (uiState.title.isNotBlank()) {
                                        AccentBlue
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = AccentBlue
                )
            )
            OutlinedTextField(
                value = uiState.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text("Description", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                placeholder = { Text("Add details... Cybersecurity is What type of thing...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                trailingIcon = {
                    if (recogniserAvailable) {
                        DictationMic(
                            listening = isSpeaking(DictationTarget.Description),
                            description = "Dictate description",
                            onClick = {
                                dictate(
                                    target = DictationTarget.Description,
                                    baseText = uiState.description,
                                    multiline = true,
                                    onText = viewModel::onDescriptionChange
                                )
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                minLines = 3,
                maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = AccentBlue
                )
            )

            // Category and Priority as dropdown side by side per request – 24.dp consistent, dark mode adapted
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                var categoryExpanded by remember { mutableStateOf(false) }
                val selectedCategoryName = uiState.categories.find { it.id == uiState.categoryId }?.name ?: "Uncategorized"
                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = !categoryExpanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = selectedCategoryName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false },
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Uncategorized") },
                            onClick = {
                                viewModel.onCategorySelected(null)
                                categoryExpanded = false
                            }
                        )
                        uiState.categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name) },
                                onClick = {
                                    viewModel.onCategorySelected(cat.id)
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }

                var priorityExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = priorityExpanded,
                    onExpandedChange = { priorityExpanded = !priorityExpanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = uiState.priority.name.lowercase().replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Priority", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = priorityExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = priorityExpanded,
                        onDismissRequest = { priorityExpanded = false },
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Priority.values().forEach { pri ->
                            DropdownMenuItem(
                                text = { Text(pri.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    viewModel.onPrioritySelected(pri)
                                    priorityExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Text("Due Date & Time", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { showDatePicker = true }, shape = RoundedCornerShape(24.dp)) {
                    Text(text = uiState.dueAt?.let { safeFormatDate(it) } ?: "Select Date", color = AccentBlue)
                }
                TextButton(onClick = { showTimePicker = true }, shape = RoundedCornerShape(24.dp)) {
                    Text(text = if (uiState.dueAt != null) safeFormatTime(uiState.dueAt!!) else "Select Time", color = AccentBlue)
                }
            }
            if (uiState.dueAt != null) {
                Text(
                    text = "Selected: ${safeFormatDate(uiState.dueAt!!)} • ${safeFormatTime(uiState.dueAt!!)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "No due date selected (optional)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("Repeat", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RepeatRule.entries.forEach { rule ->
                    RepeatChip(
                        label = when (rule) {
                            RepeatRule.NONE -> "None"
                            RepeatRule.DAILY -> "Daily"
                            RepeatRule.WEEKLY -> "Weekly"
                        },
                        selected = uiState.repeat == rule,
                        onClick = { viewModel.onRepeatSelected(rule) }
                    )
                }
            }
            if (uiState.repeat == RepeatRule.WEEKLY) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RepeatScheduler.weekdayLabels.forEachIndexed { index, label ->
                        val day = index + 1
                        DayChip(
                            label = label,
                            selected = day in uiState.repeatDays,
                            onClick = { viewModel.onRepeatDayToggled(day) },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                        )
                    }
                }
            }
            Text(
                text = if (uiState.repeat.isRecurring) {
                    "${RepeatScheduler.describe(uiState.repeat, uiState.repeatDays)}  ·  " +
                        "finishing it schedules the next one"
                } else {
                    "One-off task"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Reminder", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                Switch(
                    checked = uiState.reminderEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.onReminderToggle(enabled)
                        // Asked here, at the moment the user creates a reminder, rather than
                        // on every cold start: this is the first point where "allow
                        // notifications" has an obvious reason.
                        if (enabled && viewModel.shouldAskForNotificationPermission()) {
                            viewModel.onNotificationPermissionAsked()
                            askForNotificationPermission(context, notificationPermission)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentBlue)
                )
            }
            if (uiState.reminderEnabled && uiState.dueAt == null) {
                Text(
                    "Reminder needs due date. Please select date/time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (uiState.remindersMuted) {
                Text(
                    "Reminders are off in Settings, so this one will stay silent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Sub-tasks",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                SuggestStepsAction(
                    busy = uiState.isSuggestingSubTasks,
                    hasSuggestions = uiState.suggestedSubTasks.isNotEmpty(),
                    onSuggest = viewModel::onSuggestSubTasks
                )
            }

            SuggestedStepSection(
                steps = uiState.suggestedSubTasks,
                message = uiState.subTaskSuggestionMessage,
                onAccept = viewModel::onAcceptSuggestedSubTask,
                onAcceptAll = viewModel::onAcceptAllSuggestedSubTasks,
                onDismiss = viewModel::onDismissSuggestedSubTasks
            )

            var editingSubId by remember { mutableStateOf<String?>(null) }
            var editingSubText by remember { mutableStateOf("") }
            uiState.subTasks.forEach { sub ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(vertical = 4.dp),
                    // The checkbox and the title were touching; the row needs a real gap.
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SubTaskRoundedCheckbox(
                        checked = sub.isDone,
                        onCheckedChange = { viewModel.toggleSubTask(sub.id) }
                    )
                    if (editingSubId == sub.id) {
                        OutlinedTextField(
                            value = editingSubText,
                            onValueChange = { editingSubText = it },
                            trailingIcon = {
                                if (recogniserAvailable) {
                                    DictationMic(
                                        listening = isSpeaking(DictationTarget.ExistingSubTask(sub.id)),
                                        description = "Dictate sub-task",
                                        onClick = {
                                            dictate(
                                                target = DictationTarget.ExistingSubTask(sub.id),
                                                baseText = editingSubText,
                                                multiline = false,
                                                onText = { spoken -> editingSubText = spoken }
                                            )
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentBlue,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                        IconButton(
                            onClick = {
                                viewModel.editSubTask(sub.id, editingSubText)
                                editingSubId = null
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Save sub-task",
                                tint = AccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { editingSubId = null },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Cancel editing",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        Text(
                            text = sub.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = {
                                editingSubId = sub.id
                                editingSubText = sub.title
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Edit sub-task",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.removeSubTask(sub.id) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove sub-task",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = uiState.newSubTaskTitle,
                    onValueChange = viewModel::onNewSubTaskTitleChange,
                    label = { Text("New sub-task", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingIcon = {
                        if (recogniserAvailable) {
                            DictationMic(
                                listening = isSpeaking(DictationTarget.NewSubTask),
                                description = "Dictate new sub-task",
                                onClick = {
                                    dictate(
                                        target = DictationTarget.NewSubTask,
                                        baseText = uiState.newSubTaskTitle,
                                        multiline = false,
                                        onText = viewModel::onNewSubTaskTitleChange
                                    )
                                }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = AccentBlue
                    )
                )
                Button(
                    onClick = { viewModel.addSubTask() },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White)
                ) {
                    Text("Add", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.saveTask(onSaved) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isSaving,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White)
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                } else {
                    Text(if (uiState.isEditMode) "Update" else "Save", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            uiState.errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = uiState.dueDateMillis ?: uiState.dueAt ?: System.currentTimeMillis()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.onDueDateSelected(datePickerState.selectedDateMillis)
                        showDatePicker = false
                    }) { Text("OK", color = AccentBlue) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                },
                shape = RoundedCornerShape(24.dp)
            ) {
                DatePicker(state = datePickerState)
            }
        }

        if (showTimePicker) {
            val timePickerState = rememberTimePickerState(
                initialHour = initialCal.get(Calendar.HOUR_OF_DAY),
                initialMinute = initialCal.get(Calendar.MINUTE),
                is24Hour = false
            )
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showTimePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.onDueTimeSelected(timePickerState.hour, timePickerState.minute)
                        showTimePicker = false
                    }) { Text("OK", color = AccentBlue) }
                },
                dismissButton = {
                    TextButton(onClick = { showTimePicker = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                text = {
                    TimePicker(state = timePickerState)
                }
            )
        }

        if (uiState.showDeleteConfirm) {
            ConfirmDialog(
                title = "Delete Task",
                message = "Are you sure you want to delete this task? This cannot be undone.",
                confirmText = "Delete",
                onConfirm = {
                    viewModel.setShowDeleteConfirm(false)
                    viewModel.deleteTask(onSaved)
                },
                onDismiss = { viewModel.setShowDeleteConfirm(false) }
            )
        }

        if (uiState.showAiConsentSheet) {
            AiConsentSheet(
                onEnableCloudAi = { viewModel.onAiConsentDecided(true) },
                onUseOfflineRules = { viewModel.onAiConsentDecided(false) },
                onDismiss = viewModel::onDismissAiConsent
            )
        }
    }
}

private fun safeFormatDate(millis: Long): String {
    return try {
        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        fmt.format(Date(millis))
    } catch (e: Exception) {
        "Invalid date"
    }
}

private fun safeFormatTime(millis: Long): String {
    return try {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        fmt.format(Date(millis))
    } catch (e: Exception) {
        "Invalid time"
    }
}

/**
 * Asks for POST_NOTIFICATIONS only when a reminder is being created, and only on Android 13+.
 *
 * Deliberately silent otherwise: Android stops re-prompting after two denials, so spending the
 * prompt at a moment with no context is how an app loses the ability to ever ask again.
 */
private fun askForNotificationPermission(
    context: Context,
    launcher: ManagedActivityResultLauncher<String, Boolean>
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
}

/**
 * Pill used for the None / Daily / Weekly choice.
 */
/* ------------------------------------------------------------------ */
/* Suggested steps                                                     */
/* ------------------------------------------------------------------ */

/**
 * The ✨ half of the Sub-tasks header.
 *
 * It turns into a spinner rather than disabling silently, because a button that does
 * nothing for two seconds reads as a broken button. While busy it ignores taps: the use
 * case has its own single-flight lock, but the lock quietly refusing is not something to
 * rely on when the alternative is one line of `if`.
 */
@Composable
private fun SuggestStepsAction(
    busy: Boolean,
    hasSuggestions: Boolean,
    onSuggest: () -> Unit
) {
    TextButton(
        onClick = { if (!busy) onSuggest() },
        enabled = !busy,
        shape = RoundedCornerShape(100.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = AccentBlue
            )
        } else {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = if (hasSuggestions) "Suggest more" else "Suggest steps",
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = AccentBlue
        )
    }
}

/**
 * Offered steps, or the one line explaining why there are none.
 *
 * The message is deliberately specific - "Smart AI is off", "AI didn't answer", "Daily
 * AI limit reached" - because a single "couldn't suggest steps" is what made the
 * quick-add fallback impossible to diagnose for weeks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestedStepSection(
    steps: List<String>,
    message: String?,
    onAccept: (String) -> Unit,
    onAcceptAll: () -> Unit,
    onDismiss: () -> Unit
) {
    if (steps.isEmpty() && message == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (steps.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                steps.forEach { step ->
                    SuggestedStepChip(text = step, onClick = { onAccept(step) })
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Adding six steps one tap at a time is the kind of friction that makes
                // people stop using the feature, so the whole row is one tap away.
                if (steps.size > 1) {
                    TextButton(
                        onClick = onAcceptAll,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "Add all ${steps.size}",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                            color = AccentBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        "Dismiss",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RepeatChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) AccentBlue
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One weekday toggle for a weekly repeat. Kept square so the seven fit a phone
 * width without wrapping.
 */
@Composable
private fun DayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                if (selected) AccentBlue
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The quick-add feedback line.
 *
 * Deliberately not a snackbar and not a dialog: the fields it changed are on screen,
 * so the message belongs next to them, and Undo has to stay reachable while the user
 * looks at what changed. "AI filled" vs "Filled offline" is the honest half - a cloud
 * parse and an on-device one are not the same promise.
 */
@Composable
private fun QuickAddStrip(
    message: String,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.weight(1f)
        )
        if (canUndo) {
            TextButton(onClick = onUndo) {
                Text(
                    text = "Undo",
                    color = AccentBlue,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp)
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}
