package com.sabihon.todo.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.components.DaymarkMark
import com.sabihon.todo.core.ui.components.DaymarkWordmark
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces
import com.sabihon.todo.core.ui.theme.DaymarkLavender
import com.sabihon.todo.core.ui.theme.DaymarkNavy
import com.sabihon.todo.core.ui.util.rememberSystemAnimationsEnabled

/**
 * Welcome / onboarding cover – "Make room for what matters."
 *
 * Shown once per install, for signed-out users, immediately after Android's system
 * splash (see Theme.Sabihon.Splash). Two launch screens in a row is the thing this
 * avoids: the system splash owns the cold start, this screen owns the introduction.
 *
 * Motion is decorative only:
 *  - the logo springs in once, then holds perfectly still
 *  - the dashed orbit rotates slowly and forever; the logo does not rotate with it
 *  - everything else fades and rises by a few dp, on a short stagger
 *
 * Every action is live from the first frame - nothing waits for an animation, and
 * nothing about the screen's meaning depends on having seen one. With the system
 * "Remove animations" setting on, the orbit stops and the content is simply there.
 */
@Composable
fun OnboardingScreen(
    onGetStarted: () -> Unit,
    onSignIn: () -> Unit,
    onSkip: (() -> Unit)? = null,
    /**
     * Tertiary action. Only rendered when a demo flow exists to open it - the app has
     * no anonymous/demo entry point today, so callers pass null and the button is
     * absent rather than pointing at nothing.
     */
    onExploreDemo: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val motionEnabled = rememberSystemAnimationsEnabled()

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
        val brand = rememberEntrance(delayMillis = 0, enabled = motionEnabled)
        Row(
            modifier = Modifier
                .padding(top = 12.dp)
                .entrance(brand),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DaymarkMark(size = 26.dp)
            Spacer(Modifier.width(8.dp))
            DaymarkWordmark(fontSize = 19, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            if (onSkip != null) {
                IconButton(
                    onClick = onSkip,
                    // 48.dp so the target meets the accessibility minimum; the icon
                    // inside stays visually small.
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Skip intro",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Orbit hero
        OrbitHero(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            pillColor = if (isDark) Color(0xFF1E2027) else Color(0xFFF1F3F9),
            pillBorder = if (isDark) Color(0xFF2C2F39) else Color(0xFFE1E5F0),
            pillText = MaterialTheme.colorScheme.onSurface,
            leftPill = rememberEntrance(delayMillis = 180, enabled = motionEnabled),
            rightPill = rememberEntrance(delayMillis = 260, enabled = motionEnabled),
            animationsEnabled = motionEnabled
        )

        Spacer(Modifier.height(20.dp))

        val eyebrow = rememberEntrance(delayMillis = 260, enabled = motionEnabled)
        Text(
            text = "LESS NOISE. MORE MOMENTUM.",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.6.sp
            ),
            color = LocalDaymarkSurfaces.current.eyebrow,
            modifier = Modifier.entrance(eyebrow)
        )

        Spacer(Modifier.height(14.dp))

        val heading = rememberEntrance(delayMillis = 320, enabled = motionEnabled)
        Column(modifier = Modifier.entrance(heading)) {
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
                color = LocalDaymarkSurfaces.current.action
            )
        }

        Spacer(Modifier.height(16.dp))

        val body = rememberEntrance(delayMillis = 390, enabled = motionEnabled)
        Text(
            text = "Your plans, your pace. Turn a busy mind into small steps worth taking.",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 23.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.entrance(body)
        )

        Spacer(Modifier.height(36.dp))

        // Primary CTA
        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .entrance(rememberEntrance(delayMillis = 460, enabled = motionEnabled)),
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
                .height(56.dp)
                .entrance(rememberEntrance(delayMillis = 520, enabled = motionEnabled)),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onBackground
            ),
            border = BorderStroke(
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

        if (onExploreDemo != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onExploreDemo,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .entrance(rememberEntrance(delayMillis = 580, enabled = motionEnabled))
            ) {
                Text(
                    text = "Explore the demo first",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/* ------------------------------------------------------------------ */
/* Motion                                                              */
/* ------------------------------------------------------------------ */

/** How far an element rises as it fades in. Small on purpose. */
private val EntranceRise = 10.dp

/**
 * One element's entrance progress: 0 at first frame, 1 once settled.
 *
 * Staggering is done with the animation's own start delay rather than a chain of
 * nested effects, so nothing blocks or sequences the work: every element starts its
 * own animation immediately and they simply overlap.
 */
@Composable
private fun rememberEntrance(delayMillis: Int, enabled: Boolean): Float {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }

    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(
            durationMillis = 320,
            delayMillis = delayMillis,
            easing = FastOutSlowInEasing
        ),
        label = "welcomeEntrance$delayMillis"
    )
    // Reduced motion: skip the travel and show the element at rest immediately.
    return if (enabled) progress else 1f
}

/** Fades an element in and lifts it the last few dp into place. */
private fun Modifier.entrance(progress: Float): Modifier = graphicsLayer {
    alpha = progress
    translationY = (1f - progress) * EntranceRise.toPx()
}

/**
 * Lavender mark inside two dashed orbits, with the two floating notes.
 *
 * Sized from the available width instead of a fixed height, so a narrow phone or a
 * large display font cannot clip the orbits or the pills.
 */
@Composable
private fun OrbitHero(
    pillColor: Color,
    pillBorder: Color,
    pillText: Color,
    leftPill: Float,
    rightPill: Float,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val ringColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f)

    // Entrance: fade and rise, then hold. A spring, so it settles rather than stops.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val logoProgress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessLow
        ),
        label = "welcomeLogo"
    )
    val logo = if (animationsEnabled) logoProgress else 1f

    // The orbit turns once a minute - slow enough to read as ambient, not as a
    // loading spinner. Not created at all when the user has asked for less motion.
    val orbitAngle: Float = if (animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "welcomeOrbit")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 60_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "welcomeOrbitAngle"
        )
        angle
    } else {
        0f
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val outerRadius = minOf(maxWidth * 0.34f, 132.dp)
        val innerRadius = outerRadius * 0.70f
        val heroHeight = outerRadius * 2 + 56.dp
        val markSize = minOf(132.dp, outerRadius * 1.40f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight),
            contentAlignment = Alignment.Center
        ) {
            // Orbits + orbiting dot share one rotation, so the dot tracks its ring.
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = logo
                        rotationZ = orbitAngle
                    }
            ) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 9f), 0f)
                val midpoint = center
                listOf(innerRadius, outerRadius).forEach { radius ->
                    drawCircle(
                        color = ringColor,
                        radius = radius.toPx(),
                        center = midpoint,
                        style = Stroke(width = 1.dp.toPx(), pathEffect = dash)
                    )
                }
                // The little green satellite, parked just off the outer ring.
                drawCircle(
                    color = Color(0xFF8BD4A6),
                    radius = 5.dp.toPx(),
                    center = Offset(
                        x = midpoint.x + outerRadius.toPx() * 0.44f,
                        y = midpoint.y - outerRadius.toPx() * 0.90f
                    )
                )
            }

            // The logo itself never rotates, and stops entirely once it has arrived.
            DaymarkMark(
                size = markSize,
                modifier = Modifier.graphicsLayer {
                    alpha = logo
                    scaleX = 0.90f + 0.10f * logo
                    scaleY = 0.90f + 0.10f * logo
                    translationY = (1f - logo) * 14.dp.toPx()
                }
            )

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
                    .offset(y = 24.dp)
                    .entrance(leftPill)
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
                    .offset(y = (-24).dp)
                    .entrance(rightPill)
            )
        }
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

@Preview(name = "Welcome – dark", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun OnboardingPreviewDark() {
    com.sabihon.todo.core.ui.theme.SabihonTheme(darkTheme = true) {
        OnboardingScreen(onGetStarted = {}, onSignIn = {}, onSkip = {})
    }
}

@Preview(name = "Welcome – light", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun OnboardingPreviewLight() {
    com.sabihon.todo.core.ui.theme.SabihonTheme(darkTheme = false) {
        OnboardingScreen(
            onGetStarted = {},
            onSignIn = {},
            onSkip = {},
            onExploreDemo = {}
        )
    }
}

@Preview(name = "Welcome – small phone, large font", showBackground = true, widthDp = 320, heightDp = 640)
@Composable
private fun OnboardingPreviewSmall() {
    com.sabihon.todo.core.ui.theme.SabihonTheme(darkTheme = false) {
        OnboardingScreen(onGetStarted = {}, onSignIn = {})
    }
}
