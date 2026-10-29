package com.sabihon.todo.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.DaymarkSheetShape
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces

/**
 * One-time consent sheet shown on the user's first tap of an AI sparkle action.
 *
 * Explains what leaves the device, discloses free-tier terms plainly, and keeps
 * offline smart rules one tap away so saying "no" still completes the action cleanly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConsentSheet(
    onEnableCloudAi: () -> Unit,
    onUseOfflineRules: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(surfaces.accentSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Turn on Smart AI?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Daymark sends task text to Gemini through Firebase to rank priorities and read quick-add " +
                    "sentences. If Gemini cannot answer and an Anthropic (Claude) key is configured, the same " +
                    "prompt goes to Claude instead. On the free tier, Google may process prompts to improve its services.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Emails and phone numbers are scrubbed on-device first, nothing is saved without your confirmation, " +
                    "and you can change this anytime in Settings.",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onEnableCloudAi,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = surfaces.action,
                    contentColor = surfaces.onAction
                )
            ) {
                Text(
                    text = "Enable Smart AI",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            Spacer(Modifier.height(6.dp))

            TextButton(
                onClick = onUseOfflineRules,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Stay offline (use smart rules on device)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
