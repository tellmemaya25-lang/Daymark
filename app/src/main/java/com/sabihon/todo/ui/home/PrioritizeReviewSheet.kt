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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.components.PriorityChip
import com.sabihon.todo.core.ui.components.RoundedCheckbox
import com.sabihon.todo.core.ui.components.SectionLabel
import com.sabihon.todo.core.ui.theme.AccentBlue
import com.sabihon.todo.core.ui.theme.DaymarkSheetShape
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.ai.PrioritizationPlan
import com.sabihon.todo.domain.ai.TaskPriorityRecommendation
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task

/**
 * Human-in-the-loop review sheet for AI Task Prioritization (`docs/02-daymark-ai-system-design.md` §6 & §11.4).
 *
 * Opens immediately at `t = 0 ms` with `LocalPriorityScorer` rankings while Gemini
 * refines in the background, and never writes a priority change until the user confirms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrioritizeReviewSheet(
    plan: PrioritizationPlan,
    tasksById: Map<String, Task>,
    categoryNames: Map<String, String>,
    selectedChanges: Map<String, Priority>,
    isRefining: Boolean,
    onToggleProposal: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    val topFocus = plan.recommendations.filter { it.isTopFocus }.take(3)
    val proposals = plan.recommendations.filter { it.hasPriorityChange }
    val selectedCount = selectedChanges.size

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
            // Header row: title + provenance badge + refresh
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your focus plan",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                ProvenanceBadge(source = plan.source, isRefining = isRefining)

                Spacer(Modifier.width(4.dp))

                IconButton(
                    onClick = onRefresh,
                    enabled = !isRefining,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh focus plan",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = plan.summaryHeadline,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            // Scrollable middle body so compact / landscape phones keep the Apply button visible
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (topFocus.isNotEmpty()) {
                    SectionLabel("TOP FOCUS TODAY")
                    topFocus.forEach { rec ->
                        val task = tasksById[rec.taskId]
                        if (task != null) {
                            TopFocusRow(
                                rank = rec.focusRank,
                                title = task.title,
                                categoryName = task.categoryId?.let { categoryNames[it] },
                                reason = rec.reason
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                SectionLabel("SUGGESTED PRIORITY CHANGES")
                if (proposals.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(surfaces.groupedCard)
                            .border(1.dp, surfaces.cardBorder, RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = "Every task's priority already matches its deadline and streak.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    proposals.forEach { rec ->
                        val task = tasksById[rec.taskId]
                        if (task != null) {
                            val isChecked = rec.taskId in selectedChanges
                            PriorityProposalRow(
                                title = task.title,
                                recommendation = rec,
                                checked = isChecked,
                                onToggle = { onToggleProposal(rec.taskId, !isChecked) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = onApply,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = surfaces.action,
                    contentColor = surfaces.onAction
                )
            ) {
                val buttonText = when {
                    selectedCount == 1 -> "Apply 1 priority change"
                    selectedCount > 1 -> "Apply $selectedCount priority changes"
                    else -> "Use Smart focus order"
                }
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            Spacer(Modifier.height(6.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
            ) {
                Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProvenanceBadge(
    source: AiSource,
    isRefining: Boolean
) {
    val surfaces = LocalDaymarkSurfaces.current
    val label = when {
        isRefining -> "Refining with AI\u2026"
        source == AiSource.CLOUD -> "AI"
        source == AiSource.CACHE -> "AI \u00B7 Cached"
        else -> "Offline smart rules"
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(surfaces.accentSurface)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isRefining) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.6.dp,
                color = AccentBlue
            )
        } else {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(13.dp)
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = surfaces.onAccentSurface
        )
    }
}

@Composable
private fun TopFocusRow(
    rank: Int,
    title: String,
    categoryName: String?,
    reason: String
) {
    val surfaces = LocalDaymarkSurfaces.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(surfaces.cardHighlighted)
            .border(1.dp, AccentBlue.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(AccentBlue),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "#$rank",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!categoryName.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = categoryName,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = AccentBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PriorityProposalRow(
    title: String,
    recommendation: TaskPriorityRecommendation,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(surfaces.groupedCard)
            .border(1.dp, surfaces.cardBorder, RoundedCornerShape(16.dp))
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundedCheckbox(
                checked = checked,
                onCheckedChange = { onToggle() }
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = recommendation.reason,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.padding(start = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PriorityChip(priority = recommendation.currentPriority, selected = false)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "changes to",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            PriorityChip(priority = recommendation.suggestedPriority, selected = checked)
        }
    }
}
