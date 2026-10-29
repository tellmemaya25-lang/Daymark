package com.sabihon.todo.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sabihon.todo.core.ui.theme.AccentBlue

/**
 * Empty state for the everyday lists.
 *
 * "No tasks yet" is the first-run case and gets the brand surface; anything else keeps the
 * plain icon-and-caption treatment, because a full illustration under every filter is noise.
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    // Only the no-tasks case is first-run. "No categories" is a filter result, not a first
    // launch, and giving it the brand illustration would make every empty list look like a
    // welcome screen.
    val isFirstRun = title.contains("No tasks", ignoreCase = true)

    if (isFirstRun) {
        DaymarkEmptyState(modifier = modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.TaskAlt,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * The first-run state: the daymark mark, one instruction, and an arrow to the + button.
 *
 * Two things are deliberate. The mark is the real `ic_daymark_logo` drawable rather than a
 * hand-drawn imitation, so the first screen someone sees is actually the app's logo. And the
 * copy only promises what this app does: it used to read "Add notes, calendar events, tasks,
 * files and more", which describes a different app - here there is exactly one thing to add,
 * and it is a task.
 */
@Composable
fun DaymarkEmptyState(
    modifier: Modifier = Modifier,
    title: String = "Get started with daymark",
    subtitle: String = "Tap the + button to add your first task."
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(32.dp))

        // The brand mark, used as-is: a redrawn approximation inside a fake card was
        // starting to look like a second, worse logo.
        DaymarkMark(size = 96.dp)

        Spacer(Modifier.height(24.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(24.dp))

        // Curved arrow to the + button in the dock.
        val arrowColor = AccentBlue.copy(alpha = 0.55f)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val path = Path().apply {
                moveTo(size.width * 0.5f, 0f)
                cubicTo(
                    size.width * 0.45f, size.height * 0.3f,
                    size.width * 0.7f, size.height * 0.4f,
                    size.width * 0.6f, size.height * 0.8f
                )
            }
            drawPath(path = path, color = arrowColor, style = Stroke(width = 2f))

            val arrowEndX = size.width * 0.6f
            val arrowEndY = size.height * 0.8f
            val arrowPath = Path().apply {
                moveTo(arrowEndX, arrowEndY)
                lineTo(arrowEndX - 12f, arrowEndY - 3f)
                moveTo(arrowEndX, arrowEndY)
                lineTo(arrowEndX - 3f, arrowEndY - 12f)
            }
            drawPath(path = arrowPath, color = arrowColor, style = Stroke(width = 2f))
        }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Empty state for a filter that is not first-run: Today, Completed, Pending, Overdue.
 */
@Composable
fun FilterEmptyState(
    filterName: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.TaskAlt,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No $filterName tasks",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (filterName.lowercase()) {
                "today" -> "No tasks for today. Tap + to create!"
                "completed" -> "No completed tasks yet"
                "pending" -> "No pending tasks - you're all caught up!"
                "overdue" -> "No overdue tasks - great job!"
                else -> "Tap + to create your first task"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
