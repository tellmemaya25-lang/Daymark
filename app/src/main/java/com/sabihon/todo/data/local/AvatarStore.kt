package com.sabihon.todo.data.local

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the profile photo, shared by every screen that shows it.
 *
 * Before this existed, Profile rewrote its own local cache and Home only ever saw
 * `FirebaseAuth.photoUrl` — which is empty while Firebase Storage is unavailable —
 * so the avatar stayed as initials on Home even after a photo was picked.
 *
 * The photo lives in two places:
 *  - a cache file under `filesDir/profile_images/<uid>_profile.jpg`
 *  - a base64 JPEG in the user's Firestore document (`photoData`) for the case where
 *    the project has no Storage bucket
 *
 * [avatar] emits a `file://` URI whenever a cache file exists, so Coil can load it.
 */
@Singleton
class AvatarStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore
) {
    private val _avatar = MutableStateFlow<String?>(null)
    val avatar: StateFlow<String?> = _avatar.asStateFlow()

    fun localFile(uid: String?): File {
        val dir = File(context.filesDir, "profile_images").apply { mkdirs() }
        return File(dir, "${uid ?: "default"}_profile.jpg")
    }

    /**
     * Publishes the cache file if one exists; otherwise clears the avatar.
     *
     * The `?v=` query carries the file's modified time: the path never changes when a
     * new photo is picked, so without it Coil would serve the old image from cache.
     */
    fun publishLocal(uid: String?) {
        val file = localFile(uid)
        _avatar.value = if (file.exists() && file.length() > 0) {
            Uri.fromFile(file)
                .buildUpon()
                .appendQueryParameter("v", file.lastModified().toString())
                .build()
                .toString()
        } else {
            null
        }
    }

    /**
     * Makes sure the cache file exists (rebuilding it from the embedded copy when the
     * app was reinstalled or the photo came from another device), then publishes it.
     * Falls back to [remoteUrl] only when there is no local image at all.
     */
    suspend fun refresh(uid: String?, remoteUrl: String? = null) = withContext(Dispatchers.IO) {
        val file = uid?.let { localFile(it) }
        if (file != null && (!file.exists() || file.length() == 0L)) {
            try {
                val doc = firestore.collection("users").document(uid).get().await()
                val data = doc.getString("photoData")
                if (!data.isNullOrBlank()) {
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    if (bytes.isNotEmpty()) FileOutputStream(file).use { it.write(bytes) }
                }
            } catch (_: Exception) {
                // Offline or no document – the avatar simply stays as initials.
            }
        }
        publishLocal(uid)
        if (_avatar.value == null && !remoteUrl.isNullOrBlank()) _avatar.value = remoteUrl
        Unit
    }

    /** Writes raw bytes (a picked or downloaded image) into the cache file. */
    suspend fun writeLocal(uid: String?, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val file = localFile(uid)
            FileOutputStream(file).use { it.write(bytes) }
            Uri.fromFile(file).toString().also { _avatar.value = it }
        } catch (_: Exception) {
            null
        }
    }
}
