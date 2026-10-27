package com.sabihon.todo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.components.PriorityChip
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.ai.SuggestedTaskCandidate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Calm, collapsible contextual next-task suggestions card (`Phase 4` —
 * `docs/02-daymark-ai-system-design.md` §11.4).
 *
 * Shared across `HomeScreen` and `CategoryDetailScreen`:
 *  - Tapping `+` creates the task immediately via `AddTaskUseCase`.
 *  - Tapping the row opens `AddEditTaskScreen` pre-seeded so the user can adjust details.
 *  - Tapping `×` permanently dismisses `suggestionId` in `AiPreferences`.
 */
@Composable
fun SuggestedTasksCard(
    candidates: List<SuggestedTaskCandidate>,
    categoryNames: Map<String, String>,
    source: AiSource?,
    isLoading: Boolean,
    onAddCandidate: (SuggestedTaskCandidate) -> Unit,
    onEditCandidate: (SuggestedTaskCandidate) -> Unit,
    onDismissCandidate: (String) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val surfaces = LocalDaymarkSurfaces.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(surfaces.card)
            .border(1.dp, surfaces.cardBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header row: sparkle + title + provenance pill + refresh + close
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Suggested next steps",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            SuggestionProvenanceBadge(source = source, isLoading = isLoading)

            Spacer(Modifier.width(2.dp))

            IconButton(
                onClick = onRefresh,
                enabled = !isLoading,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh suggestions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close suggestions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (isLoading && candidates.isEmpty()) {
            Text(
                text = "Looking at your projects and recent progress\u2026",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else if (candidates.isEmpty()) {
            Text(
                text = "You're caught up \u2014 no extra follow-up tasks needed right now.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            candidates.forEach { candidate ->
                SuggestedTaskRow(
                    candidate = candidate,
                    categoryName = candidate.categoryId?.let { categoryNames[it] },
                    onAdd = { onAddCandidate(candidate) },
                    onEdit = { onEditCandidate(candidate) },
                    onDismiss = { onDismissCandidate(candidate.suggestionId) }
                )
            }
        }
    }
}

@Composable
private fun SuggestionProvenanceBadge(
    source: AiSource?,
    isLoading: Boolean
) {
    val surfaces = LocalDaymarkSurfaces.current
    val label = when {
        isLoading -> "Thinking\u2026"
        source == AiSource.CLOUD -> "AI"
        source == AiSource.CACHE -> "AI \u00B7 Cached"
        source == AiSource.LOCAL_FALLBACK -> "Offline rules"
        else -> "Smart"
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(surfaces.accentSurface)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = AccentBlue
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = surfaces.onAccentSurface
        )
    }
}

@Composable
private fun SuggestedTaskRow(
    candidate: SuggestedTaskCandidate,
    categoryName: String?,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(surfaces.groupedCard)
            .border(1.dp, surfaces.cardBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onEdit)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(3.dp))

            Text(
                text = candidate.reason,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PriorityChip(priority = candidate.priority, selected = false)

                if (!categoryName.isNullOrBlank()) {
                    Text(
                        text = categoryName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = AccentBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                candidate.dueAt?.let { due ->
                    Text(
                        text = formatSuggestedDue(due),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(AccentBlue)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add suggested task",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(Modifier.width(2.dp))

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss suggestion",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

private fun formatSuggestedDue(epochMillis: Long): String =
    SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(epochMillis))
