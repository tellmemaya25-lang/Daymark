package com.sabihon.todo.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.theme.DaymarkLavender
import com.sabihon.todo.core.ui.theme.DaymarkLavenderDeep
import com.sabihon.todo.core.ui.theme.DaymarkNavy

/**
 * "Your daily momentum" card – the lavender hero block on Home.
 *
 * total = tasks on today's list, done = how many are complete.
 */
@Composable
fun MomentumCard(
    total: Int,
    done: Int,
    modifier: Modifier = Modifier
) {
    val remaining = (total - done).coerceAtLeast(0)
    val percent = if (total == 0) 0 else ((done.toFloat() / total) * 100f).toInt()

    val stepsHeadline = when {
        total == 0 -> "A clean slate."
        remaining == 0 -> "All done for today."
        remaining == 1 -> "1 little step to go."
        else -> "$remaining little steps to go."
    }
    val subline = when {
        total == 0 -> "Nothing on your list yet. Add one small step."
        remaining == 0 -> "$done of $total tasks complete. Beautifully done."
        else -> "$done of $total tasks complete. You've got this."
    }

    val animatedPercent by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(durationMillis = 700),
        label = "momentumProgress"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFFCFDAFF), DaymarkLavender, DaymarkLavenderDeep)
                )
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "YOUR DAILY MOMENTUM",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.4.sp
                    ),
                    color = DaymarkNavy.copy(alpha = 0.65f)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stepsHeadline,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        lineHeight = 29.sp,
                        letterSpacing = (-0.4).sp
                    ),
                    color = DaymarkNavy
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subline,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    ),
                    color = DaymarkNavy.copy(alpha = 0.72f)
                )
            }
            Spacer(Modifier.width(12.dp))
            // Sparkle badge
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(1.dp, DaymarkNavy.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = DaymarkNavy,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(DaymarkNavy.copy(alpha = 0.20f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedPercent)
                        .clip(CircleShape)
                        .background(DaymarkNavy)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                ),
                color = DaymarkNavy,
                textAlign = TextAlign.End,
                modifier = Modifier.width(38.dp)
            )
        }
    }
}

/**
 * Small sparkle used by the momentum card when it appears inline (kept for reuse).
 */
@Composable
fun MomentumSparkle(tint: Color = DaymarkNavy, size: Int = 20) {
    Icon(
        Icons.Filled.AutoAwesome,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size.dp)
    )
}
