package com.sabihon.todo.core.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.sabihon.todo.core.ui.util.rememberSystemAnimationsEnabled

/**
 * Sabihon theme with Material 3 – full light & dark mode UI support
 * Light: white background, pastel containers
 * Dark: #121214 background, desaturated pastel containers, high contrast text
 */

private val LightColorScheme = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = SoftBlue,
    onPrimaryContainer = TextPrimaryLight,
    secondary = DeepGreen,
    onSecondary = Color.White,
    secondaryContainer = SoftMint,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = SoftLilac,
    onTertiary = TextPrimaryLight,
    tertiaryContainer = SoftPink,
    onTertiaryContainer = TextPrimaryLight,
    background = BgLight,
    onBackground = TextPrimaryLight,
    surface = BgLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainer = Color(0xFFF0F1F5),
    surfaceContainerHigh = Color(0xFFE8E9ED),
    outline = Color(0xFFD1D5DB),
    outlineVariant = DividerLight,
    scrim = Scrim,
    error = PriorityUrgent,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = SoftBlueDark,
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = SoftMint,
    onSecondary = Color(0xFF0A1F16),
    secondaryContainer = DeepGreenDark,
    onSecondaryContainer = Color(0xFFCFF5E7),
    tertiary = SoftLilac,
    onTertiary = Color(0xFF2D1B4E),
    tertiaryContainer = SoftLilacDark,
    onTertiaryContainer = Color(0xFFE5DBFF),
    background = BgDark,
    onBackground = TextPrimaryDark,
    surface = BgDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF2A2B33),
    surfaceContainerHighest = Color(0xFF35363F),
    outline = Color(0xFF3A3B44),
    outlineVariant = DividerDark,
    scrim = Scrim,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/**
 * The *app's* dark-mode flag — not the device's.
 *
 * daymark has its own System / Light / Dark switcher, so components must never call
 * [isSystemInDarkTheme] directly: with the app forced to Light on a dark-mode phone the
 * Material colour scheme is light while those components would render dark cards and a
 * black selected pill. Read this local instead.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * How long the light/dark switch takes.
 *
 * Short on purpose. Material's motion guidance is explicit that theme changes should
 * be brief and colour-driven rather than a slow dissolve, and anything longer starts
 * to feel like the app is thinking rather than responding.
 */
private const val THEME_SWITCH_MS = 280

/**
 * Channel-wise interpolation between two sRGB colours.
 *
 * Written out rather than using the graphics `lerp` helper so this file depends on
 * nothing beyond `Color` itself - both schemes are sRGB, so the per-channel result is
 * exact.
 */
private fun blend(start: Color, stop: Color, fraction: Float): Color = Color(
    red = start.red + (stop.red - start.red) * fraction,
    green = start.green + (stop.green - start.green) * fraction,
    blue = start.blue + (stop.blue - start.blue) * fraction,
    alpha = start.alpha + (stop.alpha - start.alpha) * fraction
)

/**
 * Blends the two schemes for the duration of the switch.
 *
 * Only the roles the app actually reads are interpolated - everything else would be
 * recomputed 60 times a second for no visible gain. The order matters: the start is
 * always the light scheme, so going dark is progress 0 -> 1 and coming back is 1 -> 0,
 * with no state to keep.
 */
private fun lerpScheme(start: ColorScheme, stop: ColorScheme, fraction: Float): ColorScheme {
    if (fraction <= 0f) return start
    if (fraction >= 1f) return stop
    return start.copy(
        primary = blend(start.primary, stop.primary, fraction),
        onPrimary = blend(start.onPrimary, stop.onPrimary, fraction),
        primaryContainer = blend(start.primaryContainer, stop.primaryContainer, fraction),
        onPrimaryContainer = blend(start.onPrimaryContainer, stop.onPrimaryContainer, fraction),
        secondaryContainer = blend(start.secondaryContainer, stop.secondaryContainer, fraction),
        onSecondaryContainer = blend(start.onSecondaryContainer, stop.onSecondaryContainer, fraction),
        background = blend(start.background, stop.background, fraction),
        onBackground = blend(start.onBackground, stop.onBackground, fraction),
        surface = blend(start.surface, stop.surface, fraction),
        onSurface = blend(start.onSurface, stop.onSurface, fraction),
        surfaceVariant = blend(start.surfaceVariant, stop.surfaceVariant, fraction),
        onSurfaceVariant = blend(start.onSurfaceVariant, stop.onSurfaceVariant, fraction),
        surfaceContainer = blend(start.surfaceContainer, stop.surfaceContainer, fraction),
        surfaceContainerHigh = blend(start.surfaceContainerHigh, stop.surfaceContainerHigh, fraction),
        surfaceContainerHighest =
            blend(start.surfaceContainerHighest, stop.surfaceContainerHighest, fraction),
        outline = blend(start.outline, stop.outline, fraction),
        outlineVariant = blend(start.outlineVariant, stop.outlineVariant, fraction),
        error = blend(start.error, stop.error, fraction),
        onError = blend(start.onError, stop.onError, fraction)
    )
}

/**
 * Theme, with an animated light/dark switch.
 *
 * The colours themselves are interpolated rather than crossfading two composited
 * trees: a `Crossfade` around the NavHost would keep two copies of the navigation
 * graph alive mid-transition, and Material's guidance warns against leaving
 * half-transparent layers of old and new content overlapping. Animating the scheme
 * means every surface, border and label moves together and the layout never changes.
 *
 * The first frame is already correct - `animateFloatAsState` starts at its target, so
 * launching the app in dark mode does not fade in from light.
 */
@Composable
fun SabihonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val animationsEnabled = rememberSystemAnimationsEnabled()
    val progress by animateFloatAsState(
        targetValue = if (darkTheme) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (animationsEnabled) THEME_SWITCH_MS else 0,
            easing = FastOutSlowInEasing
        ),
        label = "themeSwitch"
    )

    val colorScheme = lerpScheme(LightColorScheme, DarkColorScheme, progress)
    // Discrete choices (hand-picked dark cards, pill colours) flip at the midpoint,
    // where the background is closest to them, instead of at the start of the fade.
    CompositionLocalProvider(LocalIsDarkTheme provides (progress >= 0.5f)) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SabihonTypography,
            shapes = SabihonShapes,
            content = content
        )
    }
}
