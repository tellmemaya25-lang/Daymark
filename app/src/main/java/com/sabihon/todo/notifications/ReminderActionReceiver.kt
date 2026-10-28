package com.sabihon.todo.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import com.sabihon.todo.domain.security.InputSecurityValidator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject

/**
 * Handles notification actions – Mark Done.
 */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var firestore: FirebaseFirestore

    @Inject
    lateinit var auth: FirebaseAuth

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "MARK_DONE") {
            val taskId = intent.getStringExtra("taskId") ?: return
            val uid = auth.currentUser?.uid ?: return
            if (!InputSecurityValidator.isValidFirestoreDocId(taskId) ||
                !InputSecurityValidator.isValidFirestoreDocId(uid)
            ) {
                return
            }

            // Cancel notification
            NotificationManagerCompat.from(context).cancel(taskId.hashCode())

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    firestore.collection("users").document(uid).collection("tasks")
                        .document(taskId)
                        .update(
                            mapOf(
                                "completed" to true,
                                "completedAt" to Timestamp(Date()),
                                "updatedAt" to Timestamp(Date())
                            )
                        ).await()
                } catch (_: Exception) {
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
