package com.sabihon.todo.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.ui.components.BrandHeader
import com.sabihon.todo.core.ui.theme.AccentBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity – a quiet feed of what you've moved forward.
 *
 * Brand row on top, then a two-tone headline, then one row per completed (or
 * created / reopened / deleted) item with a green dot and a relative timestamp.
 * Long-press a row to restore or delete it for good.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onTaskClick: (String) -> Unit = {},
    onProfileClick: () -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var sheetItem by remember { mutableStateOf<ActivityFeedItem?>(null) }
    val sheetState = rememberModalBottomSheetState()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                BrandHeader(
                    avatarInitials = uiState.userInitials,
                    avatarUrl = uiState.userPhotoUrl,
                    onAvatarClick = onProfileClick,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)
                )
            }

            item {
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp)
                ) {
                    Text(
                        text = "Small steps. Real progress.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Your activity.",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 30.sp,
                            lineHeight = 35.sp,
                            letterSpacing = (-0.8).sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "A look back at what you've moved forward.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }

            if (uiState.feed.isEmpty() && !uiState.isLoading) {
                item { EmptyFeed() }
            } else {
                itemsIndexed(uiState.feed, key = { _, item -> item.id }) { index, item ->
                    FeedRow(
                        item = item,
                        showDivider = index < uiState.feed.lastIndex,
                        onClick = { if (item.taskId.isNotBlank()) onTaskClick(item.taskId) },
                        onLongClick = { sheetItem = item }
                    )
                }
            }

            item { Spacer(Modifier.height(110.dp)) }
        }
    }

    // Long-press actions: restore or delete for good
    if (sheetItem != null) {
        ModalBottomSheet(
            onDismissRequest = { sheetItem = null },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(bottom = 28.dp)) {
                Text(
                    text = sheetItem?.taskTitle.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                SheetAction(
                    label = "Reopen task",
                    onClick = {
                        sheetItem?.taskId?.let { viewModel.restoreTask(it) }
                        sheetItem = null
                    }
                )
                SheetAction(
                    label = "Delete forever",
                    destructive = true,
                    onClick = {
                        sheetItem?.taskId?.let { viewModel.permanentlyDelete(it) }
                        sheetItem = null
                    }
                )
            }
        }
    }
}

@Composable
private fun FeedRow(
    item: ActivityFeedItem,
    showDivider: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dotColor = if (item.isWin) {
        androidx.compose.ui.graphics.Color(0xFF4CAF50)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${item.verb} \u201C${item.taskTitle}\u201D",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = relativeTime(item.timestamp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            )
        }
    }
}

@Composable
private fun EmptyFeed() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AccentBlue)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Nothing here yet",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Finish a task and it will show up here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SheetAction(
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (destructive) {
        androidx.compose.ui.graphics.Color(0xFFE57373)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/**
 * "Earlier today" / "Yesterday" / "3 days ago" / "Sep 16".
 */
private fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return ""
    val diff = now - epochMillis
    val day = 24 * 60 * 60 * 1000L
    val calendar = java.util.Calendar.getInstance()
    val today = calendar.clone() as java.util.Calendar
    setStartOfDay(today)
    val startToday = today.timeInMillis

    return when {
        epochMillis >= startToday -> "Earlier today"
        epochMillis >= startToday - day -> "Yesterday"
        epochMillis >= startToday - 2 * day -> "2 days ago"
        diff < 7 * day -> "${(diff / day).toInt()} days ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis))
    }
}

private fun setStartOfDay(calendar: java.util.Calendar) {
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
}
