package com.sabihon.todo.ui.launch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.sabihon.todo.core.ui.components.DaymarkMark
import com.sabihon.todo.core.ui.theme.BgDark
import com.sabihon.todo.core.ui.theme.DaymarkDarkCard
import com.sabihon.todo.core.ui.theme.DaymarkDarkHairline
import com.sabihon.todo.core.ui.theme.DaymarkLavender
import com.sabihon.todo.core.ui.theme.DaymarkLavenderSoft
import com.sabihon.todo.core.ui.theme.SabihonTheme
import com.sabihon.todo.core.ui.util.rememberSystemAnimationsEnabled
import kotlinx.coroutines.delay

/**
 * Branded launch screen.
 *
 * Sits between Android's system splash and the start destination: the system splash
 * shows the launcher mark on the same charcoal this screen paints, then this screen
 * grows the same mark inside two fine orbit rings, drops the mint satellite onto the
 * outer ring and floats the two notes in from the corners.
 *
 * It is a *screen*, not an animation played over one: every element ends up in a
 * readable resting state, all of it is visible at t = 0 for anyone using TalkBack, and
 * a user who has asked the system for less motion sees the finished composition with
 * no waiting at all.
 *
 * Callers own the hand-off. [onIntroSettled] fires once the sequence has played (or
 * immediately when motion is off), and the caller decides whether that is enough to
 * navigate - see `ui/auth/SplashScreen.kt`, which also waits for auth/onboarding state
 * to resolve.
 */
@Composable
fun LaunchScreen(
    modifier: Modifier = Modifier,
    /**
     * False on a configuration change: the intro has already been seen in this process,
     * so the finished composition is drawn and [onIntroSettled] fires at once.
     */
    animate: Boolean = true,
    onIntroSettled: () -> Unit = {}
) {
    val motion = animate && rememberSystemAnimationsEnabled()

    // One clock for the whole intro. Every element reads its own window out of this
    // single value inside a graphicsLayer block, so a frame updates layer properties
    // only - no recomposition, no measure, no layout.
    //
    // Starts at its end value when motion is off, so the very first frame is already
    // the finished composition. Starting at 0 and snapping in a LaunchedEffect would
    // paint one blank charcoal frame first, which is the flash this screen exists to
    // avoid.
    val intro = remember(motion) { Animatable(if (motion) 0f else 1f) }
    val progress: () -> Float = { intro.value }
    val settled = rememberUpdatedState(onIntroSettled)

    LaunchedEffect(motion) {
        if (motion) {
            intro.animateTo(
                targetValue = 1f,
                animationSpec = tween(LaunchMotion.INTRO_MS, easing = FastOutSlowInEasing)
            )
            // A beat after the last card lands, so the composition is seen before the
            // cross-fade. Deliberately short: the system splash has already been on
            // screen for however long the process took to start.
            delay((LaunchMotion.HOLD_MS - LaunchMotion.INTRO_MS).toLong())
        } else {
            intro.snapTo(1f)
        }
        settled.value()
    }

    // The satellite's angle. Only the draw phase reads it; the rings themselves never
    // move. With less motion it is a constant, so nothing ticks in the background.
    val stillAngle = remember { mutableStateOf(LaunchMotion.STATIC_ORBIT_DEGREES) }
    val orbitAngle: State<Float> = if (motion) {
        val transition = rememberInfiniteTransition(label = "launchOrbit")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(LaunchMotion.ORBIT_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "launchOrbitAngle"
        )
    } else {
        stillAngle
    }

    // The canvas is charcoal whatever the app theme is, so the system bar icons have to
    // be light for as long as this is on screen. MainActivity sets them from the app's
    // own light/dark flag, which is right for every other screen and wrong here in
    // light mode: dark clock and battery on a near-black bar. Restored on dispose, so
    // the destination gets exactly the appearance it asked for.
    KeepSystemBarsLight()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(LaunchCanvas),
        contentAlignment = Alignment.Center
    ) {
        val stageDp = LaunchLayout.stageSize(maxWidth.value, maxHeight.value)
        val outerRadius = LaunchLayout.outerRadius(stageDp).dp
        val innerRadius = LaunchLayout.innerRadius(stageDp).dp
        val tileSize = LaunchLayout.tileSize(stageDp).dp

        Box(
            modifier = Modifier.size(stageDp.dp),
            contentAlignment = Alignment.Center
        ) {
            // Halo + rings. Behind everything, and fades in as one group.
            LaunchOrbits(
                innerRadius = innerRadius,
                outerRadius = outerRadius,
                progress = progress
            )

            // The satellite. Translated, never re-laid-out: its box stays at the centre
            // and the layer carries it out to the ring.
            Box(
                modifier = Modifier
                    .size(LaunchMotion.SATELLITE_FIELD)
                    .graphicsLayer {
                        alpha = ramp(
                            progress(),
                            LaunchMotion.RINGS_START,
                            LaunchMotion.RINGS_END
                        )
                        translationX = LaunchLayout.orbitOffsetX(
                            orbitAngle.value,
                            outerRadius.value
                        ).dp.toPx()
                        translationY = LaunchLayout.orbitOffsetY(
                            orbitAngle.value,
                            outerRadius.value
                        ).dp.toPx()
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(LaunchMotion.SATELLITE_FIELD)) {
                    drawCircle(
                        color = LaunchMint.copy(alpha = 0.18f),
                        radius = size.minDimension / 2f
                    )
                    drawCircle(color = LaunchMint, radius = 3.2.dp.toPx())
                }
            }

            // The mark. The asset already carries the pale-lavender tile, so it is
            // drawn as-is rather than re-tiled: a second lavender square behind it
            // would show its rotated edges through the corners.
            DaymarkMark(
                size = tileSize,
                modifier = Modifier.graphicsLayer {
                    val t = ramp(progress(), 0f, LaunchMotion.LOGO_END)
                    alpha = t
                    val scale = 0.92f + 0.08f * t
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - t) * 8.dp.toPx()
                }
            )

            LaunchCard(
                text = "A little progress.",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .graphicsLayer {
                        val t = ramp(
                            progress(),
                            LaunchMotion.CARD_ONE_START,
                            LaunchMotion.CARD_ONE_END
                        )
                        alpha = t
                        translationX = (1f - t) * -10.dp.toPx()
                    },
                badge = {
                    LaunchBadge(tint = LaunchMint) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = LaunchMint,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            )

            LaunchCard(
                text = "A little more you",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .graphicsLayer {
                        val t = ramp(
                            progress(),
                            LaunchMotion.CARD_TWO_START,
                            LaunchMotion.CARD_TWO_END
                        )
                        alpha = t
                        translationX = (1f - t) * 10.dp.toPx()
                    },
                badge = {
                    LaunchBadge(tint = DaymarkLavender) {
                        Sparkle(
                            color = DaymarkLavenderSoft,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            )
        }
    }
}

/**
 * Light system-bar icons while this screen is on screen, restored on dispose.
 *
 * Degrades silently when there is no Activity to talk to (a preview, an embedded
 * composition), because losing the bar tint is cosmetic and must never be a crash.
 */
@Composable
private fun KeepSystemBarsLight() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
            ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val lightStatusBars = controller.isAppearanceLightStatusBars
        val lightNavigationBars = controller.isAppearanceLightNavigationBars

        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        onDispose {
            controller.isAppearanceLightStatusBars = lightStatusBars
            controller.isAppearanceLightNavigationBars = lightNavigationBars
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/* ------------------------------------------------------------------ */
/* Pieces                                                              */
/* ------------------------------------------------------------------ */

/**
 * The pale-lavender halo plus the two fine rings.
 *
 * Drawn from one Canvas: the halo is a radial gradient rather than a blur, so it costs
 * one gradient fill and keeps the tile from looking pasted onto the charcoal.
 */
@Composable
private fun LaunchOrbits(
    innerRadius: Dp,
    outerRadius: Dp,
    progress: () -> Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = ramp(progress(), LaunchMotion.RINGS_START, LaunchMotion.RINGS_END)
            }
    ) {
        val haloRadius = outerRadius.toPx() * 0.95f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    DaymarkLavender.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = center,
                radius = haloRadius
            ),
            radius = haloRadius,
            center = center
        )

        val stroke = 1.dp.toPx()
        drawCircle(
            color = LaunchRing,
            radius = innerRadius.toPx(),
            center = center,
            style = Stroke(width = stroke)
        )
        drawCircle(
            color = LaunchRing,
            radius = outerRadius.toPx(),
            center = center,
            style = Stroke(width = stroke)
        )
    }
}

/** One floating note: a tinted badge, a short line, a hairline edge on charcoal. */
@Composable
private fun LaunchCard(
    text: String,
    badge: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .widthIn(max = 210.dp)
            .clip(LaunchCardShape)
            .background(LaunchCardSurface)
            .border(1.dp, LaunchCardBorder, LaunchCardShape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        badge()
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp
            ),
            color = LaunchInk
        )
    }
}

@Composable
private fun LaunchBadge(tint: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Four-point sparkle, drawn rather than taken from the icon set: the filled icons are
 * too heavy at 12 dp and would read as a star next to the checkmark.
 */
@Composable
private fun Sparkle(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val reach = size.minDimension / 2f
        val waist = reach * 0.16f
        val path = Path().apply {
            moveTo(center.x, center.y - reach)
            quadraticBezierTo(center.x + waist, center.y - waist, center.x + reach, center.y)
            quadraticBezierTo(center.x + waist, center.y + waist, center.x, center.y + reach)
            quadraticBezierTo(center.x - waist, center.y + waist, center.x - reach, center.y)
            quadraticBezierTo(center.x - waist, center.y - waist, center.x, center.y - reach)
            close()
        }
        drawPath(path, color)
    }
}

/* ------------------------------------------------------------------ */
/* Motion                                                              */
/* ------------------------------------------------------------------ */

/**
 * The intro, as fractions of one clock, plus the two durations around it.
 *
 * Each element owns a window in the same 0..1 sweep rather than its own delayed
 * animation. That is what lets the whole sequence be a single [Animatable]: a window
 * that has not opened yet reports 0, one that has closed reports 1, and the frame
 * budget never depends on how many elements are on screen.
 */
private object LaunchMotion {

    /**
     * Logo in, rings on, both cards landed: 700 ms.
     *
     * Short on purpose. The system splash has already been on screen for however long
     * the process took to start, so everything this screen adds is on top of that. The
     * sequence still reads as a sequence - each beat starts before the previous one
     * finishes - rather than as one long dissolve.
     */
    const val INTRO_MS = 700

    /**
     * When the caller is told the sequence is over - the last card lands at 0.78 of the
     * sweep, so the composition rests for ~230 ms before the cross-fade.
     *
     * The same for the reduced-motion path: that path is not motion-free, it is
     * *animation*-free. Holding a static frame for a couple of system frames and then
     * cross-fading would read as a flash rather than as a launch screen.
     */
    const val HOLD_MS = 780

    /** One revolution of the satellite. Slow enough to read as ambient, not a spinner. */
    const val ORBIT_PERIOD_MS = 60_000

    /** Where the satellite is parked with less motion: upper right, off the vertical. */
    const val STATIC_ORBIT_DEGREES = -62f

    /** Box around the satellite, so its glow has room and the dot is not clipped. */
    val SATELLITE_FIELD = 18.dp

    const val LOGO_END = 0.26f

    const val RINGS_START = 0.06f
    const val RINGS_END = 0.46f

    const val CARD_ONE_START = 0.30f
    const val CARD_ONE_END = 0.66f

    const val CARD_TWO_START = 0.42f
    const val CARD_TWO_END = 0.78f
}

/** Maps the master clock onto one element's window, clamped at both ends. */
private fun ramp(value: Float, from: Float, to: Float): Float =
    ((value - from) / (to - from)).coerceIn(0f, 1f)

/* ------------------------------------------------------------------ */
/* Palette                                                             */
/* ------------------------------------------------------------------ */

/**
 * Fixed, not themed: the launch surface is charcoal in light mode too, matching the
 * system splash. Everything here is a brand token where one exists, so the screen
 * cannot drift from the rest of the app.
 */
private val LaunchCanvas = BgDark
private val LaunchRing = Color(0xFF3A3F4A)
private val LaunchCardSurface = DaymarkDarkCard
private val LaunchCardBorder = DaymarkDarkHairline
private val LaunchInk = Color(0xFFE8EAF2)
private val LaunchMint = Color(0xFF8BD4A6)
private val LaunchCardShape = RoundedCornerShape(16.dp)

/* ------------------------------------------------------------------ */
/* Previews                                                            */
/* ------------------------------------------------------------------ */

@Preview(name = "Launch – phone", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun LaunchScreenPreview() {
    SabihonTheme(darkTheme = true) {
        LaunchScreen(animate = false)
    }
}

@Preview(name = "Launch – small phone", showBackground = true, widthDp = 320, heightDp = 568)
@Composable
private fun LaunchScreenSmallPreview() {
    SabihonTheme(darkTheme = true) {
        LaunchScreen(animate = false)
    }
}

@Preview(name = "Launch – landscape", showBackground = true, widthDp = 800, heightDp = 360)
@Composable
private fun LaunchScreenLandscapePreview() {
    SabihonTheme(darkTheme = true) {
        LaunchScreen(animate = false)
    }
}
