package com.sabihon.todo.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.components.DaymarkMark
import com.sabihon.todo.core.ui.components.DaymarkWordmark
import com.sabihon.todo.core.ui.theme.DaymarkEyebrow
import com.sabihon.todo.core.ui.theme.DaymarkLavender
import com.sabihon.todo.core.ui.theme.DaymarkNavy

/**
 * Onboarding cover – "Make room for what matters."
 *
 * Shown once per install, right after the branded splash, for signed-out users.
 * Mirrors the reference: mark lockup top-left, orbiting mark with two callout pills,
 * lavender eyebrow, two-tone headline, then the two CTAs.
 */
@Composable
fun OnboardingScreen(
    onGetStarted: () -> Unit,
    onSignIn: () -> Unit,
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminanceIsDark()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        // Brand lockup
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DaymarkMark(size = 26.dp)
            Spacer(Modifier.width(8.dp))
            DaymarkWordmark(fontSize = 19, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            if (onSkip != null) {
                IconButton(onClick = onSkip, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Skip intro",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Orbit hero
        OrbitHero(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            pillColor = if (isDark) Color(0xFF1E2027) else Color(0xFFF1F3F9),
            pillBorder = if (isDark) Color(0xFF2C2F39) else Color(0xFFE1E5F0),
            pillText = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "LESS NOISE. MORE MOMENTUM.",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.6.sp
            ),
            color = if (isDark) DaymarkEyebrow else DaymarkNavy.copy(alpha = 0.75f)
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = "Make room for",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 38.sp,
                lineHeight = 43.sp,
                letterSpacing = (-1).sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "what matters.",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 38.sp,
                lineHeight = 43.sp,
                letterSpacing = (-1).sp
            ),
            color = if (isDark) DaymarkLavender else DaymarkNavy
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Your plans, your pace. Turn a busy mind into small steps worth taking.",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 23.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(36.dp))

        // Primary CTA
        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DaymarkLavender,
                contentColor = DaymarkNavy
            )
        ) {
            Text(
                text = "Get started",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // Secondary CTA
        OutlinedButton(
            onClick = onSignIn,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onBackground
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.9f else 0.6f)
            )
        ) {
            Text(
                text = "I already have an account",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Lavender mark card inside two dashed orbits, with the two floating callouts.
 */
@Composable
private fun OrbitHero(
    pillColor: Color,
    pillBorder: Color,
    pillText: Color,
    modifier: Modifier = Modifier
) {
    val ringColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f)

    Box(
        modifier = modifier.height(300.dp),
        contentAlignment = Alignment.Center
    ) {
        // Orbits
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 9f), 0f)
            val center = center
            listOf(96.dp, 140.dp).forEach { radiusDp ->
                drawCircle(
                    color = ringColor,
                    radius = radiusDp.toPx(),
                    center = center,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = dash)
                )
            }
        }

        // Little orbit dot
        Box(
            modifier = Modifier
                .offset(x = 62.dp, y = (-128).dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(0xFF8BD4A6))
        )

        // Floating mark
        DaymarkMark(size = 132.dp)

        // Callout – left
        CalloutPill(
            text = "A little progress.",
            icon = {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = DaymarkNavy,
                    modifier = Modifier.size(13.dp)
                )
            },
            background = pillColor,
            border = pillBorder,
            textColor = pillText,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = 34.dp)
        )

        // Callout – right
        CalloutPill(
            text = "A little more you.",
            icon = {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = DaymarkLavender,
                    modifier = Modifier.size(13.dp)
                )
            },
            background = pillColor,
            border = pillBorder,
            textColor = pillText,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(y = (-34).dp)
        )
    }
}

@Composable
private fun CalloutPill(
    text: String,
    icon: @Composable () -> Unit,
    background: Color,
    border: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        icon()
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = textColor
        )
    }
}

/** Cheap luminance test so the hero can pick dark/light pill styling. */
private fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

@Preview(name = "Onboarding – dark", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun OnboardingPreviewDark() {
    com.sabihon.todo.core.ui.theme.SabihonTheme(darkTheme = true) {
        OnboardingScreen(onGetStarted = {}, onSignIn = {}, onSkip = {})
    }
}

@Preview(name = "Onboarding – light", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun OnboardingPreviewLight() {
    com.sabihon.todo.core.ui.theme.SabihonTheme(darkTheme = false) {
        OnboardingScreen(onGetStarted = {}, onSignIn = {}, onSkip = {})
    }
}
