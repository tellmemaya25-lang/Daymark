package com.sabihon.todo.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.theme.AccentBlue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Celebratory splash shown the moment a task is ticked off.
 *
 * Three concentric rings expand from the centre behind an accent badge that pops
 * in on a spring and the whole overlay fades away on its own after ~900ms. It is
 * purely visual: no clicks are consumed, so the list stays usable underneath.
 */
@Composable
fun CompletionSplash(
    visible: Boolean,
    title: String = "Task complete",
    subtitle: String? = null,
    onFinished: () -> Unit = {}
) {
    if (!visible) return

    val haptics = LocalHapticFeedback.current
    val pop = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    val currentOnFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        coroutineScope {
            launch {
                pop.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow)
                )
            }
            launch { ring.animateTo(1f, tween(760)) }
            launch {
                fade.animateTo(1f, tween(110))
                delay(620)
                fade.animateTo(0f, tween(240))
            }
        }
        currentOnFinished()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Soft scrim – dark enough to lift the badge off the list, light enough to
        // keep the card that was just completed visible behind it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fade.value }
                .background(Color.Black.copy(alpha = 0.22f))
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            listOf(0f, 0.16f, 0.32f).forEach { start ->
                val t = ((ring.value - start) / (1f - start)).coerceIn(0f, 1f)
                if (t > 0f) {
                    drawCircle(
                        color = AccentBlue.copy(alpha = 0.20f * (1f - t) * fade.value),
                        radius = 44.dp.toPx() + t * size.minDimension * 0.42f
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = fade.value
                scaleX = 0.55f + 0.45f * pop.value
                scaleY = 0.55f + 0.45f * pop.value
            }
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(AccentBlue),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
