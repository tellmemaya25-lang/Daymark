package com.sabihon.todo.ui.reminder

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.datastore.ReminderPreferences
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.SabihonTheme
import com.sabihon.todo.domain.reminder.ReminderSettings
import com.sabihon.todo.domain.security.InputSecurityValidator
import com.sabihon.todo.notifications.ReminderActionReceiver
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The screen an **Alarm**-style reminder opens, including over the lock screen.
 *
 * A notification alone cannot do this: on a sleeping device it lights the screen for a second
 * and sleeps again. `showWhenLocked` + `turnScreenOn` is what makes it an alarm instead of a
 * banner.
 *
 * The actions are the same broadcasts the notification buttons send, so there is one
 * implementation of "done" and one of "snooze" however the user reaches them.
 */
@AndroidEntryPoint
class ReminderAlarmActivity : ComponentActivity() {

    @Inject
    lateinit var preferences: ReminderPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // setShowWhenLocked/setTurnScreenOn only exist from API 27. minSdk is 24, and the
        // window flags are the documented equivalent below that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val rawTaskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val taskId = rawTaskId.takeIf { InputSecurityValidator.isValidFirestoreDocId(it) }.orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Task reminder" }
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()

        setContent {
            SabihonTheme {
                // Read after the first frame, never before it: this screen may be racing the
                // lock screen to wake the device, and a blocking disk read in onCreate is how
                // an alarm ends up as an ANR. The label corrects itself a moment later.
                var snoozeMinutes by remember { mutableStateOf(ReminderSettings.DEFAULT.snoozeMinutes) }
                LaunchedEffect(Unit) {
                    snoozeMinutes = runCatching { preferences.current().snoozeMinutes }
                        .getOrDefault(ReminderSettings.DEFAULT.snoozeMinutes)
                }
                AlarmContent(
                    title = title,
                    description = description,
                    snoozeMinutes = snoozeMinutes,
                    onDone = { sendAction(ReminderActionReceiver.ACTION_MARK_DONE, taskId, title, description) },
                    onSnooze = { sendAction(ReminderActionReceiver.ACTION_SNOOZE, taskId, title, description) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun sendAction(action: String, taskId: String, title: String, description: String) {
        if (taskId.isNotEmpty()) {
            sendBroadcast(
                Intent(this, ReminderActionReceiver::class.java).apply {
                    this.action = action
                    putExtra(ReminderActionReceiver.EXTRA_TASK_ID, taskId)
                    putExtra(ReminderActionReceiver.EXTRA_TITLE, title)
                    putExtra(ReminderActionReceiver.EXTRA_DESCRIPTION, description)
                }
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "taskTitle"
        const val EXTRA_DESCRIPTION = "taskDescription"
    }
}

@Composable
private fun AlarmContent(
    title: String,
    description: String,
    snoozeMinutes: Int,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "REMINDER",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            ),
            color = AccentBlue
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        if (description.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(36.dp))

        Button(
            onClick = onDone,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentBlue,
                contentColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        ) {
            Text("Mark done", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onSnooze,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        ) {
            Text("Snooze $snoozeMinutes min")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
