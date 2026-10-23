package com.sabihon.todo.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.theme.LocalIsDarkTheme
import com.sabihon.todo.core.ui.theme.DaymarkDarkCardFocus
import com.sabihon.todo.core.ui.theme.DaymarkNavy
import com.sabihon.todo.ui.home.WeekDay
import java.time.LocalDate

/**
 * Week strip – Monday..Sunday with the selected day in a rounded lavender/navy pill
 * and a small dot under today.
 */
@Composable
fun WeekStrip(
    days: List<WeekDay>,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    if (days.isEmpty()) return

    val isDark = LocalIsDarkTheme.current
    val selectedSurface = if (isDark) Color(0xFF232B45) else Color(0xFFE8EDFF)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        days.forEach { day ->
            val isSelected = day.date == selected
            DayCell(
                day = day,
                isSelected = isSelected,
                isDark = isDark,
                selectedSurface = selectedSurface,
                onClick = { onSelect(day.date) }
            )
        }
    }
}

@Composable
private fun DayCell(
    day: WeekDay,
    isSelected: Boolean,
    isDark: Boolean,
    selectedSurface: Color,
    onClick: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = if (isSelected) selectedSurface else Color.Transparent,
        animationSpec = tween(200),
        label = "dayBackground"
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            isSelected && isDark -> Color.White
            isSelected -> DaymarkNavy
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "dayLabel"
    )
    val numberColor by animateColorAsState(
        targetValue = when {
            isSelected && isDark -> Color.White
            isSelected -> DaymarkNavy
            // Unselected days stay muted in both themes (matches the reference)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "dayNumber"
    )
    val pillHeight by animateDpAsState(
        targetValue = if (isSelected) 68.dp else 58.dp,
        animationSpec = tween(220),
        label = "dayHeight"
    )

    Column(
        modifier = Modifier
            .width(44.dp)
            .height(pillHeight)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .selectable(
                selected = isSelected,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = day.letter,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            ),
            color = labelColor
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = "${day.dayOfMonth}",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            ),
            color = numberColor
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(3.dp)
                .clip(CircleShape)
                .background(
                    if (day.isToday) {
                        if (isSelected && !isDark) DaymarkNavy else Color(0xFF8FA6F0)
                    } else {
                        Color.Transparent
                    }
                )
        )
    }
}
