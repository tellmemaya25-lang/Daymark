package com.sabihon.todo.ui.reminder

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.R
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.domain.reminder.ReminderAlertStyle
import com.sabihon.todo.notifications.AlarmSignalPlayer
import com.sabihon.todo.notifications.DueReminder
import com.sabihon.todo.notifications.ReminderActionReceiver
import com.sabihon.todo.notifications.ReminderEvents
import kotlinx.coroutines.flow.collect

/**
 * The in-app popup for a reminder that comes due while the app is open.
 *
 * A heads-up banner over the screen that lists the task is noise, so the receiver routes
 * foreground reminders here instead. Mounted once, above the NavHost, so it survives navigation.
 *
 * Done and Snooze send the same broadcasts the notification buttons send - one implementation,
 * one behaviour, and the same habit/streak rules applied by `ToggleCompleteUseCase`.
 */
@Composable
fun InAppReminderHost(
    events: ReminderEvents,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // A queue, not a single slot: two reminders can come due in the same minute, and the
    // second one silently replacing the first would mean never being told about it.
    val pending = remember { mutableStateListOf<DueReminder>() }

    LaunchedEffect(events) {
        events.due.collect { reminder -> pending.add(reminder) }
    }

    val reminder = pending.firstOrNull() ?: return
    val surfaces = LocalDaymarkSurfaces.current
    val alarmSignals = remember(context) { AlarmSignalPlayer(context) }

    DisposableEffect(reminder.taskId, reminder.style) {
        if (reminder.style == ReminderAlertStyle.ALARM) alarmSignals.start()
        onDispose { alarmSignals.stop() }
    }

    fun send(action: String) {
        context.sendBroadcast(
            Intent(context, ReminderActionReceiver::class.java).apply {
                this.action = action
                putExtra(ReminderActionReceiver.EXTRA_TASK_ID, reminder.taskId)
                putExtra(ReminderActionReceiver.EXTRA_TITLE, reminder.title)
                putExtra(ReminderActionReceiver.EXTRA_DESCRIPTION, reminder.description)
            }
        )
        if (pending.isNotEmpty()) pending.removeAt(0)
    }

    AlertDialog(
        onDismissRequest = { if (pending.isNotEmpty()) pending.removeAt(0) },
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        containerColor = surfaces.card,
        tonalElevation = 0.dp,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(30.dp)
                        .height(30.dp)
                        .background(surfaces.accentSurface, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.NotificationsActive,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.width(18.dp).height(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.reminder),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = AccentBlue
                    )
                    Text(
                        text = reminder.title.ifBlank { "Task reminder" },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        text = {
            if (reminder.description.isNotBlank()) {
                Text(
                    text = reminder.description,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { send(ReminderActionReceiver.ACTION_MARK_DONE) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = Color.White
                ),
                modifier = Modifier.heightIn(min = 44.dp)
            ) {
                Text("Mark done", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { send(ReminderActionReceiver.ACTION_SNOOZE) }) {
                    Text("Snooze")
                }
                TextButton(onClick = { if (pending.isNotEmpty()) pending.removeAt(0) }) {
                    Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
}
