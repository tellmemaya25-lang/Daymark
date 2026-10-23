package com.sabihon.todo.core.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sabihon.todo.core.ui.theme.AccentBlue

/**
 * Round checkbox – fully circular per user request "make the check boxes round"
 * Blue when checked, animated scale
 */
@Composable
fun RoundedCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scale by animateFloatAsState(
        targetValue = if (checked) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "checkScale"
    )
    Box(
        modifier = modifier
            .size(22.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (checked) AccentBlue else Color.Transparent)
            .border(
                width = 1.8.dp,
                color = if (checked) AccentBlue else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * Round version for sub-tasks – matches screenshot but circular per request
 * Used in the sub-task checklist – larger icons per request use 1st image for consistency
 */
@Composable
fun SubTaskRoundedCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (checked) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "subCheckScale"
    )
    val bgAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(250),
        label = "bgAlpha"
    )
    Box(
        modifier = modifier
            .size(28.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (checked) AccentBlue.copy(alpha = bgAlpha) else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (checked) AccentBlue else Color.Gray.copy(alpha = 0.4f),
                shape = CircleShape
            )
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
