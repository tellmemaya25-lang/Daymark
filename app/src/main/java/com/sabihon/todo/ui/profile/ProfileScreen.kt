package com.sabihon.todo.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.sabihon.todo.core.ui.components.GroupedCard
import com.sabihon.todo.core.ui.components.GroupedDivider
import com.sabihon.todo.core.ui.components.GroupedRow
import com.sabihon.todo.core.ui.components.SectionLabel
import com.sabihon.todo.core.ui.components.ConfirmDialog
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.DaymarkSheetShape
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces

/**
 * Profile, laid out as an identity block plus grouped settings rows.
 *
 * The previous version was a stack of equal-weight cards holding a permanent name field
 * with its own loud Save button - so the least-used action on the screen was the most
 * prominent, a read-only email looked editable, and in dark mode the cards had no edge
 * against the #121214 canvas and dissolved into it.
 *
 * What changed and why:
 *  - identity first: photo, name and email read as one block, no card
 *  - name and password edits both happen in the same bottom sheet, which removes the
 *    standing primary button and means the saved values are never half-edited on screen
 *  - email is a plain row with no chevron, so it stops inviting typing
 *  - rows, dividers and labels come from the shared grouped-list components, and every
 *    colour comes from the animated token set - so nothing on this screen jumps when
 *    the theme changes
 *  - destructive actions are separated and demoted below the account rows
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit = {},
    onSignOut: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            viewModel.onPhotoPicked(uri)
        }
    }

    val fallbackPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            viewModel.onPhotoPicked(uri)
        }
    }

    val pickPhoto = {
        try {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (e: Exception) {
            fallbackPickerLauncher.launch("image/*")
        }
        Unit
    }

    LaunchedEffect(uiState.error, uiState.successMessage) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Profile",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(horizontal = 20.dp)
        ) {
            ProfileHero(
                displayName = uiState.displayName,
                email = uiState.email,
                photoUrl = uiState.localPhotoUri?.toString() ?: uiState.photoUrl,
                isUploading = uiState.isUploadingPhoto,
                onPickPhoto = pickPhoto
            )

            SectionLabel("ACCOUNT")
            GroupedCard {
                GroupedRow(
                    label = "Display name",
                    value = uiState.displayName.ifBlank { "Not set" },
                    onClick = viewModel::openNameEditor
                )
                GroupedDivider()
                GroupedRow(
                    label = "Email",
                    value = uiState.email.ifBlank { "—" },
                    // No chevron: there is nothing to open, and the arrow is what made
                    // people try to edit it.
                    onClick = null
                )
                GroupedDivider()
                GroupedRow(
                    label = "Password",
                    value = "••••••••",
                    onClick = viewModel::openPasswordSheet
                )
            }

            Spacer(Modifier.height(24.dp))

            SectionLabel("ACCOUNT ACTIONS")
            OutlinedButton(
                onClick = { viewModel.signOut(onSignOut) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text("Sign out", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }

            Spacer(Modifier.height(8.dp))

            // Demoted from a filled red button to a text button: destructive, but not
            // an action to reach for by accident, and still a 48 dp target.
            TextButton(
                onClick = { viewModel.setShowDeleteConfirm(true) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(
                    "Delete account",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (uiState.showNameEditor) {
        DaymarkSheet(onDismiss = viewModel::cancelNameEditor) {
            NameEditorSheet(
                draft = uiState.nameDraft,
                isSaving = uiState.isLoading,
                onDraftChange = viewModel::onNameDraftChange,
                onSave = viewModel::saveNameDraft,
                onCancel = viewModel::cancelNameEditor
            )
        }
    }

    if (uiState.showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete account",
            message = "This will permanently delete your account and all data. This cannot be undone.",
            confirmText = "Delete",
            onConfirm = {
                viewModel.setShowDeleteConfirm(false)
                viewModel.deleteAccount(onSignOut)
            },
            onDismiss = { viewModel.setShowDeleteConfirm(false) }
        )
    }

    if (uiState.showPasswordSheet) {
        DaymarkSheet(onDismiss = { viewModel.setShowPasswordSheet(false) }) {
            PasswordSheet(
                currentPassword = uiState.currentPassword,
                newPassword = uiState.newPassword,
                confirmNewPassword = uiState.confirmNewPassword,
                passwordError = uiState.passwordError,
                isSaving = uiState.isLoading,
                onCurrentPasswordChange = viewModel::onCurrentPasswordChange,
                onNewPasswordChange = viewModel::onNewPasswordChange,
                onConfirmNewPasswordChange = viewModel::onConfirmNewPasswordChange,
                onSubmit = { viewModel.changePassword() },
                onCancel = { viewModel.setShowPasswordSheet(false) }
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Identity                                                            */
/* ------------------------------------------------------------------ */

/**
 * Photo, name and email as one block rather than three separate bands.
 *
 * The avatar keeps a 48 dp tap target for the camera badge; the whole circle is
 * clickable as well, so a mis-aimed tap still opens the picker.
 */
@Composable
private fun ProfileHero(
    displayName: String,
    email: String,
    photoUrl: String?,
    isUploading: Boolean,
    onPickPhoto: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(104.dp)) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onPickPhoto),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isUploading -> CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = AccentBlue
                    )
                    photoUrl != null -> AsyncImage(
                        model = photoUrl,
                        contentDescription = "Profile photo",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    else -> Text(
                        text = displayName.take(2).uppercase().ifBlank { "DM" },
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(AccentBlue)
                    .align(Alignment.BottomEnd)
                    .clickable(onClick = onPickPhoto),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = "Change profile photo",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = displayName.ifBlank { "Your name" },
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                letterSpacing = (-0.4).sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = email.ifBlank { "Not signed in" },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/* ------------------------------------------------------------------ */
/* Sheets                                                              */
/* ------------------------------------------------------------------ */

/**
 * The one sheet shell used by Profile.
 *
 * Shape and container colour are set once here, which is how both sheets ended up with
 * the same 20 dp top corners instead of inheriting Material's 28 dp default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaymarkSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = DaymarkSheetShape,
        containerColor = LocalDaymarkSurfaces.current.card,
        tonalElevation = 0.dp,
        content = content
    )
}

/** Title + one line of supporting text, shared by both sheets. */
@Composable
private fun SheetHeading(title: String, supporting: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(6.dp))
    Text(
        supporting,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun NameEditorSheet(
    draft: String,
    isSaving: Boolean,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 28.dp)
    ) {
        SheetHeading(
            title = "Display name",
            supporting = "This is the name shown on your Home and Activity headers."
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = AccentBlue
            )
        )

        Spacer(Modifier.height(20.dp))

        SheetPrimaryAction(
            text = "Save",
            enabled = draft.isNotBlank() && !isSaving,
            isLoading = isSaving,
            onClick = onSave
        )

        SheetCancelAction(onCancel)
    }
}

@Composable
private fun PasswordSheet(
    currentPassword: String,
    newPassword: String,
    confirmNewPassword: String,
    passwordError: String?,
    isSaving: Boolean,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmNewPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 28.dp)
    ) {
        SheetHeading(
            title = "Change password",
            supporting = "Use at least 6 characters. You stay signed in on this device."
        )
        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PasswordField(
                value = currentPassword,
                onValueChange = onCurrentPasswordChange,
                label = "Current password"
            )
            PasswordField(
                value = newPassword,
                onValueChange = onNewPasswordChange,
                label = "New password"
            )
            PasswordField(
                value = confirmNewPassword,
                onValueChange = onConfirmNewPasswordChange,
                label = "Confirm new password"
            )
            passwordError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // The button stays clickable with empty fields - the ViewModel owns the
        // validation messages ("Current password required", length, mismatch) and a
        // disabled button would hide the reason nothing happened.
        Button(
            onClick = onSubmit,
            enabled = !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = surfaces.action,
                contentColor = surfaces.onAction
            )
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = surfaces.onAction
                )
            } else {
                Text("Change password", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        SheetCancelAction(onCancel)
    }
}

/** Filled action at the bottom of a sheet. */
@Composable
private fun SheetPrimaryAction(
    text: String,
    enabled: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = surfaces.action,
            contentColor = surfaces.onAction
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = surfaces.onAction
            )
        } else {
            Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun SheetCancelAction(onCancel: () -> Unit) {
    TextButton(
        onClick = onCancel,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentBlue,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent
        )
    )
}
