package com.sabihon.todo.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.theme.AccentBlue

/**
 * One offered step: "Buy milk  +".
 *
 * Outlined rather than filled so a row of them does not read as a row of buttons the
 * user has already pressed. The whole chip is the tap target - the `+` is a hint that
 * tapping adds it, not a separate control - and the spoken label says so, because a
 * chip that announces only its text gives no clue what tapping it does.
 */
@Composable
fun SuggestedStepChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(
                width = 1.dp,
                color = AccentBlue.copy(alpha = 0.35f),
                shape = RoundedCornerShape(100.dp)
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Add step: $text" }
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(13.dp)
            )
        }
    }
}
