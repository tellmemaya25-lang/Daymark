package com.sabihon.todo.core.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.components.MomentumCard
import com.sabihon.todo.core.ui.components.SegmentedTabs
import com.sabihon.todo.core.ui.components.SwipeableTaskRow
import com.sabihon.todo.core.ui.components.TabItem
import com.sabihon.todo.core.ui.theme.SabihonTheme
import com.sabihon.todo.domain.model.SubTask

/**
 * Design preview for the rebuilt Home blocks – header, momentum card,
 * segmented tabs and the task cards. Open this file in Android Studio and use the
 * Preview pane to check the styling without signing in.
 */
@Preview(name = "Home blocks – dark", showBackground = true, widthDp = 400, heightDp = 820)
@Composable
private fun HomeBlocksDarkPreview() {
    SabihonTheme(darkTheme = true) {
        HomeBlocks()
    }
}

@Preview(name = "Home blocks – light", showBackground = true, widthDp = 400, heightDp = 820)
@Composable
private fun HomeBlocksLightPreview() {
    SabihonTheme(darkTheme = false) {
        HomeBlocks()
    }
}

@Composable
private fun HomeBlocks() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp)
        ) {
            Text(
                text = "A fresh start, Maya.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Make today count.",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 31.sp,
                    lineHeight = 36.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Friday, October 23",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        MomentumCard(
            total = 5,
            done = 2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        SegmentedTabs(
            tabs = listOf(
                TabItem("today", "Today", 3),
                TabItem("upcoming", "Upcoming", null),
                TabItem("all", "All tasks", 5)
            ),
            selectedId = "today",
            onSelect = {}
        )

        Spacer(Modifier.height(14.dp))

        SwipeableTaskRow(
            title = "Prepare for the AI webinar",
            isCompleted = false,
            timeLabel = "Today",
            description = "Bring your ideas to the conversation.",
            categoryLabel = "Work",
            priorityLabel = "High priority",
            priorityDotColor = Color(0xFFF2994A),
            isHighlighted = true,
            subTasks = listOf(
                SubTask("1", "Draft outline", true),
                SubTask("2", "Collect examples", true),
                SubTask("3", "Rehearse", false)
            )
        )

        SwipeableTaskRow(
            title = "Read cybersecurity notes",
            isCompleted = false,
            timeLabel = "Today",
            description = "A little learning goes a long way.",
            categoryLabel = "Learning",
            priorityLabel = "Medium",
            subTasks = listOf(SubTask("1", "Chapter 1", true))
        )

        SwipeableTaskRow(
            title = "Make time for a walk",
            isCompleted = false,
            timeLabel = "Today",
            description = "Step outside. Clear your mind.",
            categoryLabel = "Personal",
            priorityLabel = "Low"
        )
    }
}
