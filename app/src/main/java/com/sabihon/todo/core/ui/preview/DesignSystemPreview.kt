package com.sabihon.todo.core.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sabihon.todo.core.ui.components.CategoryCard
import com.sabihon.todo.core.ui.components.CircleCheckbox
import com.sabihon.todo.core.ui.components.EmptyState
import com.sabihon.todo.core.ui.components.LoadingShimmer
import com.sabihon.todo.core.ui.components.PastelStatTile
import com.sabihon.todo.core.ui.components.PillChip
import com.sabihon.todo.core.ui.components.PriorityChip
import com.sabihon.todo.core.ui.components.SabihonFab
import com.sabihon.todo.core.ui.components.SabihonTopBar
import com.sabihon.todo.core.ui.components.SectionHeader
import com.sabihon.todo.core.ui.components.StatType
import com.sabihon.todo.core.ui.components.StatTileByType
import com.sabihon.todo.core.ui.components.TaskRow
import com.sabihon.todo.core.ui.theme.SabihonTheme
import com.sabihon.todo.core.ui.theme.SoftBlue
import com.sabihon.todo.core.ui.theme.SoftYellow
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.SubTask

/**
 * Preview screen rendering every component – light + dark.
 * Visually matches reference screenshot layout/spacing/colors.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignSystemContent() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SabihonTopBar(title = "All Task List", showBack = true, showSearch = true)
        }
        item {
            SectionHeader(title = "Today's Task")
        }
        item {
            Text("Stat Tiles – 2x2 grid", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTileByType(type = StatType.TODAY, count = 5, modifier = Modifier.weight(1f))
                    StatTileByType(type = StatType.SCHEDULED, count = 12, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTileByType(type = StatType.ALL, count = 28, modifier = Modifier.weight(1f))
                    StatTileByType(type = StatType.OVERDUE, count = 3, modifier = Modifier.weight(1f))
                }
            }
        }
        item {
            Text("Category Cards", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryCard(name = "Grocery", colorHex = "#D6E4FF", taskCount = 8, completedCount = 3)
                CategoryCard(name = "Educational", colorHex = "#FDF3A0", taskCount = 12, completedCount = 5)
                CategoryCard(name = "Home Related", colorHex = "#CFF5E7", taskCount = 6, completedCount = 2)
                CategoryCard(name = "Work Related", colorHex = "#2F6B4F", taskCount = 15, completedCount = 7)
                CategoryCard(name = "Mandatory Work", colorHex = "#FBD7EA", taskCount = 4, completedCount = 1)
                CategoryCard(name = "Personal Notes", colorHex = "#E5DBFF", taskCount = 9, completedCount = 9)
            }
        }
        item {
            Text("Task Rows", style = MaterialTheme.typography.titleMedium)
        }
        item {
            TaskRow(
                title = "Buy groceries for dinner",
                isCompleted = false,
                timeLabel = "Today • 4:50 PM",
                description = "Milk, eggs, bread, cheese",
                subTasks = listOf(
                    SubTask(id = "1", title = "Buy milk", isDone = true),
                    SubTask(id = "2", title = "Buy eggs", isDone = false)
                )
            )
        }
        item {
            TaskRow(
                title = "Complete UI design for daymark .",
                isCompleted = true,
                timeLabel = "Yesterday • 2:30 PM"
            )
        }
        item {
            Text("Chips", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(text = "Today")
                PillChip(text = "Yesterday • 4:50 PM")
                PriorityChip(priority = Priority.LOW)
                PriorityChip(priority = Priority.MEDIUM, selected = true)
                PriorityChip(priority = Priority.HIGH)
                PriorityChip(priority = Priority.URGENT)
            }
        }
        item {
            Text("Checkboxes", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircleCheckbox(checked = false, onCheckedChange = {})
                CircleCheckbox(checked = true, onCheckedChange = {})
            }
        }
        item {
            Text("Empty & Loading", style = MaterialTheme.typography.titleMedium)
            EmptyState(title = "No tasks yet", description = "Tap + to create your first task and stay organized!")
            LoadingShimmer()
        }
        item {
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Preview(name = "Design System Light", showBackground = true, widthDp = 400, heightDp = 2000)
@Composable
fun DesignSystemPreviewLight() {
    SabihonTheme(darkTheme = false) {
        Scaffold(
            floatingActionButton = { SabihonFab(onClick = {}) }
        ) { padding ->
            Column(Modifier.padding(padding)) {
                DesignSystemContent()
            }
        }
    }
}

@Preview(name = "Design System Dark", showBackground = true, widthDp = 400, heightDp = 2000)
@Composable
fun DesignSystemPreviewDark() {
    SabihonTheme(darkTheme = true) {
        Scaffold(
            floatingActionButton = { SabihonFab(onClick = {}) }
        ) { padding ->
            Column(Modifier.padding(padding)) {
                DesignSystemContent()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StatTilePreview() {
    SabihonTheme {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PastelStatTile(label = "Today", count = 5, backgroundColor = SoftBlue, icon = Icons.Filled.Today, modifier = Modifier.weight(1f))
            PastelStatTile(label = "Scheduled", count = 12, backgroundColor = SoftYellow, icon = Icons.Filled.Event, modifier = Modifier.weight(1f))
        }
    }
}
