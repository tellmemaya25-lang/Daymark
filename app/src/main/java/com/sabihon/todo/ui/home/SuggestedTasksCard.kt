package com.sabihon.todo.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.domain.ai.SuggestedTaskCandidate

/**
 * "Suggested next steps ✨" - a collapsible strip on Home and inside a project.
 *
 * Deliberately quiet: no title shouting, no badge, one line per suggestion and two
 * small actions. A suggestion card that looks like a task list gets read as a task list,
 * and then the user thinks the app has already decided for them.
 *
 * Collapsed state is remembered by the caller, not here, because the same card is shown
 * in two places with different lifetimes.
 */
@Composable
fun SuggestedTasksCard(
    suggestions: List<SuggestedTaskCandidate>,
    expanded: Boolean,
    isRefreshing: Boolean,
    onToggleExpanded: () -> Unit,
    onAccept: (SuggestedTaskCandidate) -> Unit,
    onDismiss: (SuggestedTaskCandidate) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Suggested next steps",
    onEdit: ((SuggestedTaskCandidate) -> Unit)? = null
) {
    if (suggestions.isEmpty() && !isRefreshing) return

    val surfaces = LocalDaymarkSurfaces.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(surfaces.accentSurface.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The whole header is the toggle, not just the chevron: a card that
                // collapses must collapse from wherever it is poked.
                .clickable(onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = surfaces.onAccentSurface,
                modifier = Modifier.weight(1f)
            )
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = AccentBlue
                )
            } else {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse suggestions" else "Expand suggestions",
                    tint = surfaces.onAccentSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160))
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (suggestions.isEmpty()) {
                    Text(
                        text = "Nothing worth adding right now.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = surfaces.onAccentSurface.copy(alpha = 0.75f),
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                suggestions.forEach { suggestion ->
                    SuggestionRow(
                        suggestion = suggestion,
                        onAccept = { onAccept(suggestion) },
                        onDismiss = { onDismiss(suggestion) },
                        onEdit = onEdit?.let { callback -> { callback(suggestion) } }
                    )
                }
                if (suggestions.isNotEmpty()) {
                    TextButton(
                        onClick = onRefresh,
                        enabled = !isRefreshing,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(
                            text = "Refresh",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                            color = AccentBlue
                        )
                    }
                }
            }
        }
    }
}

/**
 * One suggestion: what it is, why it is there, add, mute.
 *
 * The reason is not decoration - it is the difference between a suggestion and a guess.
 * If the line ever reads as noise, the suggestion should not have been made.
 */
@Composable
private fun SuggestionRow(
    suggestion: SuggestedTaskCandidate,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    val surfaces = LocalDaymarkSurfaces.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onEdit != null) {
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onEdit)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = surfaces.onAccentSurface
            )
            if (suggestion.reason.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = suggestion.reason,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = surfaces.onAccentSurface.copy(alpha = 0.65f)
                )
            }
        }

        IconButton(onClick = onAccept, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = Icons.Filled.Add,
                // Named after the task it adds: "Add" alone is six identical buttons to
                // anyone using a screen reader.
                contentDescription = "Add ${suggestion.title}",
                tint = AccentBlue,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss ${suggestion.title}",
                tint = surfaces.onAccentSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}
