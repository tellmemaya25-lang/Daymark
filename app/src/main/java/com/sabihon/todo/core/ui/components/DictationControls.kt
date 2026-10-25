package com.sabihon.todo.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.theme.AccentBlue

/**
 * The microphone that lives in a text field.
 *
 * Nothing pulses. Listening is a colour change plus the strip's words, which is also
 * why this needs no reduced-motion variant: there is no motion to suppress.
 *
 * Shared by Add Task and Search - the same control, the same promise, wherever a field
 * can be filled by speaking.
 */
@Composable
fun DictationMic(
    listening: Boolean,
    description: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = description,
            tint = if (listening) MaterialTheme.colorScheme.error else AccentBlue,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Dictation's one line, in the same strip quick add already uses.
 *
 * While listening it names the field and offers Done (keep what was heard) and Cancel
 * (put the field back exactly as it was). Afterwards it carries the reason it stopped,
 * which is never a dialog and never a toast.
 */
@Composable
fun DictationStrip(
    listening: Boolean,
    label: String?,
    message: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
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
            imageVector = if (listening) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = null,
            tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = if (listening) "Listening for ${label ?: "title"}…" else message.orEmpty(),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.weight(1f)
        )
        if (listening) {
            TextButton(onClick = onDone) {
                Text(
                    text = "Done",
                    color = AccentBlue,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp)
                )
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cancel dictation",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        } else {
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
}
