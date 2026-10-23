package com.sabihon.todo.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.sabihon.todo.core.datastore.ThemePreferences
import com.sabihon.todo.data.local.AvatarStore
import com.sabihon.todo.core.datastore.ThemePref
import com.sabihon.todo.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class ProfileUiState(
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val localPhotoUri: Uri? = null,
    val localPhotoPath: String? = null,
    val theme: ThemePref = ThemePref.SYSTEM,
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val showDeleteConfirm: Boolean = false,
    val showChangePasswordDialog: Boolean = false,
    val isUploadingPhoto: Boolean = false,
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmNewPassword: String = "",
    val passwordError: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val authRepository: AuthRepository,
    private val themePreferences: ThemePreferences,
    private val avatarStore: AvatarStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
        observeTheme()
    }

    private fun loadProfile() {
        val user = auth.currentUser
        viewModelScope.launch {
            try {
                val uid = user?.uid
                val localPath = loadLocalPhotoPath(uid)

                if (uid != null) {
                    val doc = firestore.collection("users").document(uid).get().await()
                    val firestorePhoto = doc.getString("photoUrl")
                    val firestoreName = doc.getString("displayName")

                    // Photos saved while Firebase Storage was unavailable live in the
                    // document as a base64 JPEG; rebuild the local cache file from it.
                    val embedded = doc.getString("photoData")
                    val restored = if (localPath == null && !embedded.isNullOrBlank()) {
                        restoreEmbeddedPhoto(uid, embedded)
                    } else {
                        null
                    }
                    val effectivePath = localPath ?: restored
                    val effectiveUri = effectivePath
                        ?.let { File(it).takeIf { f -> f.exists() } }
                        ?.let { Uri.fromFile(it) }

                    _uiState.update {
                        it.copy(
                            displayName = firestoreName ?: user?.displayName ?: "",
                            email = user?.email ?: "",
                            photoUrl = firestorePhoto ?: user?.photoUrl?.toString(),
                            localPhotoPath = effectivePath,
                            localPhotoUri = effectiveUri ?: it.localPhotoUri
                        )
                    }
                    // Home reads the same cache file through AvatarStore.
                    avatarStore.publishLocal(uid)
                } else {
                    val localUri = localPath
                        ?.let { File(it).takeIf { f -> f.exists() } }
                        ?.let { Uri.fromFile(it) }
                    _uiState.update {
                        it.copy(
                            displayName = user?.displayName ?: "",
                            email = user?.email ?: "",
                            photoUrl = user?.photoUrl?.toString(),
                            localPhotoPath = localPath,
                            localPhotoUri = localUri ?: it.localPhotoUri
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        displayName = user?.displayName ?: "",
                        email = user?.email ?: "",
                        photoUrl = user?.photoUrl?.toString(),
                        error = e.message
                    )
                }
            }
        }
    }

    private fun getLocalPhotoFile(uid: String?): File {
        val dir = File(appContext.filesDir, "profile_images").apply { mkdirs() }
        return File(dir, "${uid ?: "default"}_profile.jpg")
    }

    private fun loadLocalPhotoPath(uid: String?): String? {
        return try {
            val file = getLocalPhotoFile(uid)
            if (file.exists()) file.absolutePath else null
        } catch (_: Exception) { null }
    }

    private suspend fun saveImageLocally(uri: Uri, uid: String?): String? = withContext(Dispatchers.IO) {
        try {
            val input = appContext.contentResolver.openInputStream(uri) ?: return@withContext null
            val outFile = getLocalPhotoFile(uid)
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
            input.close()
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun observeTheme() {
        viewModelScope.launch {
            themePreferences.themeFlow.collectLatest { theme ->
                _uiState.update { it.copy(theme = theme) }
            }
        }
    }

    fun onDisplayNameChange(name: String) {
        _uiState.update { it.copy(displayName = name, error = null) }
    }

    fun onPhotoPicked(uri: Uri) {
        _uiState.update { it.copy(localPhotoUri = uri, error = null) }
        // Store locally immediately for offline access + then upload to Firebase
        viewModelScope.launch {
            val uid = auth.currentUser?.uid
            val localPath = saveImageLocally(uri, uid)
            if (localPath != null) {
                _uiState.update { it.copy(localPhotoPath = localPath, localPhotoUri = Uri.fromFile(File(localPath))) }
            }
        }
        uploadPhoto(uri)
    }

    /**
     * Saves the avatar.
     *
     * Firebase Storage is the preferred home, but this project may not have a bucket
     * provisioned ("Object does not exist at location" on downloadUrl). Rather than
     * failing the upload, fall back to storing a compressed JPEG in the user document
     * as base64 — a Firestore doc holds 1 MiB and a 512px/ladder-compressed avatar is
     * a fraction of that. The local cache file is still written either way, so the
     * photo shows up even before any network round-trip finishes.
     */
    private fun uploadPhoto(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingPhoto = true, error = null) }
            val uid = auth.currentUser?.uid
            if (uid == null) {
                _uiState.update { it.copy(isUploadingPhoto = false, error = "Not authenticated") }
                return@launch
            }

            // 1. Preferred path: Firebase Storage, when it is available.
            val downloadUrl = tryUploadToStorage(uri, uid)
            if (downloadUrl != null) {
                try {
                    val user = auth.currentUser
                    val profileUpdate = UserProfileChangeRequest.Builder()
                        .setPhotoUri(Uri.parse(downloadUrl))
                        .build()
                    user?.updateProfile(profileUpdate)?.await()
                } catch (_: Exception) {
                    // Cosmetic – the Firestore copy is what the app reads back.
                }
                try {
                    firestore.collection("users").document(uid)
                        .set(
                            mapOf("photoUrl" to downloadUrl, "photoData" to null),
                            com.google.firebase.firestore.SetOptions.merge()
                        )
                        .await()
                } catch (_: Exception) {
                }
                _uiState.update {
                    it.copy(
                        photoUrl = downloadUrl,
                        localPhotoUri = null,
                        isUploadingPhoto = false,
                        successMessage = "Profile photo updated"
                    )
                }
                avatarStore.publishLocal(uid)
                return@launch
            }

            // 2. Fallback: embed the image in the user document.
            val bytes = compressForEmbedding(uri)
            if (bytes == null) {
                _uiState.update {
                    it.copy(
                        isUploadingPhoto = false,
                        error = "Couldn't read that image. Try another photo."
                    )
                }
                return@launch
            }
            try {
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                firestore.collection("users").document(uid)
                    .set(
                        mapOf("photoData" to base64, "photoUrl" to null),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
                    .await()
                _uiState.update {
                    it.copy(
                        photoUrl = null,
                        localPhotoUri = null,
                        isUploadingPhoto = false,
                        successMessage = "Profile photo updated"
                    )
                }
                avatarStore.publishLocal(uid)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isUploadingPhoto = false,
                        error = "Photo save failed: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    /** Uploads to Firebase Storage; null on any failure so the caller can fall back. */
    private suspend fun tryUploadToStorage(uri: Uri, uid: String): String? = try {
        val storageRef = storage.reference.child("users/$uid/profile_${System.currentTimeMillis()}.jpg")
        storageRef.putFile(uri).await()
        var url: String? = null
        repeat(3) { attempt ->
            try {
                url = storageRef.downloadUrl.await().toString()
            } catch (_: Exception) {
                if (attempt < 2) kotlinx.coroutines.delay(400)
            }
            if (url != null) return@repeat
        }
        url
    } catch (e: Exception) {
        android.util.Log.w("ProfileVM", "Storage upload unavailable, embedding instead: ${e.message}")
        null
    }

    /** Downscale + quality ladder so the base64 blob stays well under the doc limit. */
    private suspend fun compressForEmbedding(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val input = appContext.contentResolver.openInputStream(uri) ?: return@withContext null
            val original = BitmapFactory.decodeStream(input)
            input.close()
            if (original == null) return@withContext null

            val maxSide = 512
            val longest = maxOf(original.width, original.height)
            val scaled = if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                Bitmap.createScaledBitmap(
                    original,
                    (original.width * scale).toInt().coerceAtLeast(1),
                    (original.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                original
            }

            val out = ByteArrayOutputStream()
            var quality = 80
            var bytes: ByteArray
            do {
                out.reset()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bytes = out.toByteArray()
                quality -= 15
            } while (bytes.size > 300_000 && quality >= 35)
            bytes
        } catch (e: Exception) {
            null
        }
    }

    /** Writes the embedded base64 back to the local cache file and returns its path. */
    private suspend fun restoreEmbeddedPhoto(uid: String?, data: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                if (bytes.isEmpty()) return@withContext null
                val file = getLocalPhotoFile(uid)
                FileOutputStream(file).use { it.write(bytes) }
                file.absolutePath
            } catch (e: Exception) {
                null
            }
        }

    fun saveDisplayName() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val user = auth.currentUser ?: return@launch
                val update = UserProfileChangeRequest.Builder()
                    .setDisplayName(_uiState.value.displayName)
                    .build()
                user.updateProfile(update).await()
                firestore.collection("users").document(user.uid)
                    .update("displayName", _uiState.value.displayName).await()
                _uiState.update { it.copy(isLoading = false, successMessage = "Display name saved") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun setTheme(theme: ThemePref) {
        viewModelScope.launch {
            themePreferences.setTheme(theme)
            try {
                val uid = auth.currentUser?.uid ?: return@launch
                firestore.collection("users").document(uid)
                    .update("themePref", theme.name).await()
            } catch (_: Exception) {}
        }
    }

    // Change password
    fun setShowChangePassword(show: Boolean) {
        _uiState.update { it.copy(showChangePasswordDialog = show, passwordError = null, currentPassword = "", newPassword = "", confirmNewPassword = "") }
    }

    fun onCurrentPasswordChange(pwd: String) {
        _uiState.update { it.copy(currentPassword = pwd, passwordError = null) }
    }

    fun onNewPasswordChange(pwd: String) {
        _uiState.update { it.copy(newPassword = pwd, passwordError = null) }
    }

    fun onConfirmNewPasswordChange(pwd: String) {
        _uiState.update { it.copy(confirmNewPassword = pwd, passwordError = null) }
    }

    fun changePassword() {
        val state = _uiState.value
        if (state.currentPassword.isBlank()) {
            _uiState.update { it.copy(passwordError = "Current password required") }
            return
        }
        if (state.newPassword.length < 6) {
            _uiState.update { it.copy(passwordError = "New password must be at least 6 characters") }
            return
        }
        if (state.newPassword != state.confirmNewPassword) {
            _uiState.update { it.copy(passwordError = "New passwords do not match") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, passwordError = null) }
            try {
                val user = auth.currentUser ?: throw IllegalStateException("Not authenticated")
                val email = user.email ?: throw IllegalStateException("No email")

                // Re-authenticate
                val credential = EmailAuthProvider.getCredential(email, state.currentPassword)
                user.reauthenticate(credential).await()

                // Update password
                user.updatePassword(state.newPassword).await()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        showChangePasswordDialog = false,
                        successMessage = "Password changed successfully",
                        currentPassword = "",
                        newPassword = "",
                        confirmNewPassword = ""
                    )
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to change password"
                val friendly = when {
                    msg.contains("wrong-password", true) || msg.contains("invalid-credential", true) -> "Current password is incorrect"
                    msg.contains("requires recent login", true) -> "Please sign out and sign in again, then try"
                    else -> msg
                }
                _uiState.update { it.copy(isLoading = false, passwordError = friendly) }
            }
        }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            authRepository.signOut()
            onDone()
        }
    }

    fun setShowDeleteConfirm(show: Boolean) {
        _uiState.update { it.copy(showDeleteConfirm = show) }
    }

    fun deleteAccount(onDone: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = authRepository.deleteAccount()) {
                is com.sabihon.todo.core.util.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                    onDone()
                }
                is com.sabihon.todo.core.util.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, successMessage = null, passwordError = null) }
    }
}
