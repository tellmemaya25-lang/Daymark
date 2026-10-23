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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sabihon.todo.core.ui.theme.AccentBlue

/**
 * Empty state – classic simple + Notely-inspired onboarding for empty tasks.
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    // For "No tasks yet" use Notely style
    if (title.contains("No tasks", ignoreCase = true) || description.contains("Tap +", ignoreCase = true)) {
        NotelyEmptyState(
            title = "Get started with daymark .",
            subtitle = "Add notes, calendar events, tasks, files and more with the action bar",
            modifier = modifier
        )
    } else {
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
}

/**
 * Notely-inspired empty state – stacked notes illustration, title, subtitle, curved arrow to + button.
 * Matches screenshot: dark cards with fold, logo, arrow pointing to action bar.
 */
@Composable
fun NotelyEmptyState(
    title: String = "Get started with Notely",
    subtitle: String = "Add notes, calendar events, tasks files and more with the action bar",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(32.dp))

        // Stacked notes illustration – like screenshot
        Box(
            modifier = Modifier
                .width(180.dp)
                .height(220.dp),
            contentAlignment = Alignment.Center
        ) {
            // Behind card – slightly rotated, offset
            Box(
                modifier = Modifier
                    .size(width = 140.dp, height = 180.dp)
                    .offset(x = (-12).dp, y = 8.dp)
                    .rotate(-8f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            )

            // Front card – main
            Box(
                modifier = Modifier
                    .size(width = 140.dp, height = 180.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                // Logo in center – Notely style (two slanted bars)
                Canvas(
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center)
                ) {
                    val barWidth = size.width * 0.6f
                    val barHeight = size.height * 0.18f
                    val gap = size.height * 0.15f

                    // Draw 3 slanted rectangles (parallelogram) like logo
                    val path1 = Path().apply {
                        moveTo(size.width * 0.2f, size.height * 0.3f)
                        lineTo(size.width * 0.2f + barWidth, size.height * 0.3f - 8f)
                        lineTo(size.width * 0.2f + barWidth, size.height * 0.3f - 8f + barHeight)
                        lineTo(size.width * 0.2f, size.height * 0.3f + barHeight)
                        close()
                    }
                    val path2 = Path().apply {
                        moveTo(size.width * 0.2f, size.height * 0.3f + barHeight + gap)
                        lineTo(size.width * 0.2f + barWidth, size.height * 0.3f + barHeight + gap - 8f)
                        lineTo(size.width * 0.2f + barWidth, size.height * 0.3f + barHeight + gap - 8f + barHeight)
                        lineTo(size.width * 0.2f, size.height * 0.3f + barHeight + gap + barHeight)
                        close()
                    }

                    drawPath(path1, color = Color.Gray.copy(alpha = 0.6f))
                    drawPath(path2, color = Color.Gray.copy(alpha = 0.6f))
                }

                // Folded corner bottom right – triangle
                Canvas(
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.BottomEnd)
                ) {
                    val foldPath = Path().apply {
                        moveTo(size.width, size.height - 24f)
                        lineTo(size.width - 24f, size.height)
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(foldPath, color = Color.Black.copy(alpha = 0.15f))
                    // Inner highlight
                    val innerPath = Path().apply {
                        moveTo(size.width, size.height - 22f)
                        lineTo(size.width - 22f, size.height)
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(innerPath, color = Color.White.copy(alpha = 0.08f))
                }
            }
        }

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

        // Curved arrow pointing to + button (center bottom nav)
        val arrowColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            // Curved path from top center to bottom center-right (where + is)
            val path = Path().apply {
                moveTo(size.width * 0.5f, 0f)
                cubicTo(
                    size.width * 0.45f, size.height * 0.3f,
                    size.width * 0.7f, size.height * 0.4f,
                    size.width * 0.6f, size.height * 0.8f
                )
            }
            drawPath(
                path = path,
                color = arrowColor,
                style = Stroke(width = 2f)
            )

            // Arrow head at end
            val arrowEndX = size.width * 0.6f
            val arrowEndY = size.height * 0.8f
            val arrowPath = Path().apply {
                moveTo(arrowEndX, arrowEndY)
                lineTo(arrowEndX - 12f, arrowEndY - 3f)
                moveTo(arrowEndX, arrowEndY)
                lineTo(arrowEndX - 3f, arrowEndY - 12f)
            }
            drawPath(
                path = arrowPath,
                color = arrowColor,
                style = Stroke(width = 2f)
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Simplified version for other filters – keeps title/description but with Notely illustration small.
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
                "pending" -> "No pending tasks – you're all caught up!"
                "overdue" -> "No overdue tasks – great job!"
                else -> "Tap + to create your first task"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
