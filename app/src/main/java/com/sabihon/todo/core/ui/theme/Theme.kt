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
import androidx.compose.runtime.Immutable
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
    inversePrimary = Color(0xFFAFC0F5),
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
    surfaceTint = AccentBlue,
    inverseSurface = Color(0xFF2A2B33),
    inverseOnSurface = Color(0xFFF5F5F5),
    // Neutral surface scale. The M3 baseline values are purple-tinted, which reads
    // off-brand next to the navy/lavender palette, so all five steps are set here.
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE8E9ED),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = Color(0xFFF0F1F5),
    surfaceContainerHigh = Color(0xFFE8E9ED),
    surfaceContainerHighest = Color(0xFFE2E4E9),
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
    inversePrimary = DaymarkNavy,
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
    surfaceTint = AccentBlue,
    inverseSurface = Color(0xFFF5F5F5),
    inverseOnSurface = Color(0xFF1E1F25),
    surfaceBright = Color(0xFF35363F),
    surfaceDim = BgDark,
    surfaceContainerLowest = Color(0xFF0E0E10),
    surfaceContainerLow = Color(0xFF17181C),
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
 * Brand surfaces that the Material scheme has no role for.
 *
 * These used to be `if (isDark) DaymarkDarkCard else Color(0xFFF5F6FA)` at the call
 * site. That was the visible defect in the light/dark switch: [LocalIsDarkTheme] flips
 * at the 50 % mark, so every hard-coded card, hairline and eyebrow *snapped* to its
 * other value halfway through an otherwise smooth 280 ms fade — a near-black card
 * landing on a half-faded grey canvas. Supplying the same pair of values already
 * interpolated by the theme removes the jump and removes the branching from screens.
 */
@Immutable
data class DaymarkSurfaces(
    /** Task rows, grouped cards' neighbours and sheet containers. */
    val card: Color,
    /** Grouped settings/profile cards — a touch softer than a task row in light. */
    val groupedCard: Color,
    /** Highlighted / freshly-completed task row. */
    val cardHighlighted: Color,
    /** 1 dp edge. Dark cards are only ~2 values away from #121214 without it. */
    val cardBorder: Color,
    /** Selected day pill in the week strip. */
    val accentSurface: Color,
    val onAccentSurface: Color,
    /** Small all-caps section labels. */
    val eyebrow: Color,
    /** Filled primary action inside a sheet (Save / Change password). */
    val action: Color,
    val onAction: Color
)

private val LightDaymarkSurfaces = DaymarkSurfaces(
    card = Color.White,
    groupedCard = SurfaceVariantLight,
    cardHighlighted = Color(0xFFEDF2FF),
    cardBorder = DividerLight,
    accentSurface = Color(0xFFE8EDFF),
    onAccentSurface = DaymarkNavy,
    eyebrow = DaymarkNavy.copy(alpha = 0.7f),
    action = DaymarkNavy,
    onAction = Color.White
)

private val DarkDaymarkSurfaces = DaymarkSurfaces(
    card = DaymarkDarkCard,
    groupedCard = DaymarkDarkCard,
    cardHighlighted = DaymarkDarkCardFocus,
    cardBorder = DaymarkDarkHairline,
    accentSurface = DaymarkDarkCardFocus,
    onAccentSurface = Color.White,
    eyebrow = DaymarkEyebrow,
    action = DaymarkLavender,
    onAction = DaymarkNavy
)

val LocalDaymarkSurfaces = staticCompositionLocalOf { LightDaymarkSurfaces }

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

private fun blendSurfaces(
    start: DaymarkSurfaces,
    stop: DaymarkSurfaces,
    fraction: Float
): DaymarkSurfaces {
    if (fraction <= 0f) return start
    if (fraction >= 1f) return stop
    return DaymarkSurfaces(
        card = blend(start.card, stop.card, fraction),
        groupedCard = blend(start.groupedCard, stop.groupedCard, fraction),
        cardHighlighted = blend(start.cardHighlighted, stop.cardHighlighted, fraction),
        cardBorder = blend(start.cardBorder, stop.cardBorder, fraction),
        accentSurface = blend(start.accentSurface, stop.accentSurface, fraction),
        onAccentSurface = blend(start.onAccentSurface, stop.onAccentSurface, fraction),
        eyebrow = blend(start.eyebrow, stop.eyebrow, fraction),
        action = blend(start.action, stop.action, fraction),
        onAction = blend(start.onAction, stop.onAction, fraction)
    )
}

/**
 * Blends the two schemes for the duration of the switch.
 *
 * Every role is interpolated, not just the handful the app reads by name: Material
 * components resolve their own defaults (`Card` reads `surfaceContainerHighest`, a
 * bottom sheet reads `surfaceContainerLow`, an outlined button reads `outline`), so a
 * partial list is exactly what produces the "most of the screen fades, a few parts
 * jump" effect at the end of the transition. The `*Fixed` roles are left to the
 * defaults on purpose - by definition they are the same in both schemes.
 *
 * The order matters: the start is always the light scheme, so going dark is progress
 * 0 -> 1 and coming back is 1 -> 0, with no state to keep.
 */
private fun lerpScheme(start: ColorScheme, stop: ColorScheme, fraction: Float): ColorScheme {
    if (fraction <= 0f) return start
    if (fraction >= 1f) return stop
    return start.copy(
        primary = blend(start.primary, stop.primary, fraction),
        onPrimary = blend(start.onPrimary, stop.onPrimary, fraction),
        primaryContainer = blend(start.primaryContainer, stop.primaryContainer, fraction),
        onPrimaryContainer = blend(start.onPrimaryContainer, stop.onPrimaryContainer, fraction),
        inversePrimary = blend(start.inversePrimary, stop.inversePrimary, fraction),
        secondary = blend(start.secondary, stop.secondary, fraction),
        onSecondary = blend(start.onSecondary, stop.onSecondary, fraction),
        secondaryContainer = blend(start.secondaryContainer, stop.secondaryContainer, fraction),
        onSecondaryContainer = blend(start.onSecondaryContainer, stop.onSecondaryContainer, fraction),
        tertiary = blend(start.tertiary, stop.tertiary, fraction),
        onTertiary = blend(start.onTertiary, stop.onTertiary, fraction),
        tertiaryContainer = blend(start.tertiaryContainer, stop.tertiaryContainer, fraction),
        onTertiaryContainer = blend(start.onTertiaryContainer, stop.onTertiaryContainer, fraction),
        background = blend(start.background, stop.background, fraction),
        onBackground = blend(start.onBackground, stop.onBackground, fraction),
        surface = blend(start.surface, stop.surface, fraction),
        onSurface = blend(start.onSurface, stop.onSurface, fraction),
        surfaceVariant = blend(start.surfaceVariant, stop.surfaceVariant, fraction),
        onSurfaceVariant = blend(start.onSurfaceVariant, stop.onSurfaceVariant, fraction),
        surfaceTint = blend(start.surfaceTint, stop.surfaceTint, fraction),
        inverseSurface = blend(start.inverseSurface, stop.inverseSurface, fraction),
        inverseOnSurface = blend(start.inverseOnSurface, stop.inverseOnSurface, fraction),
        error = blend(start.error, stop.error, fraction),
        onError = blend(start.onError, stop.onError, fraction),
        errorContainer = blend(start.errorContainer, stop.errorContainer, fraction),
        onErrorContainer = blend(start.onErrorContainer, stop.onErrorContainer, fraction),
        outline = blend(start.outline, stop.outline, fraction),
        outlineVariant = blend(start.outlineVariant, stop.outlineVariant, fraction),
        scrim = blend(start.scrim, stop.scrim, fraction),
        surfaceBright = blend(start.surfaceBright, stop.surfaceBright, fraction),
        surfaceDim = blend(start.surfaceDim, stop.surfaceDim, fraction),
        surfaceContainerLowest = blend(start.surfaceContainerLowest, stop.surfaceContainerLowest, fraction),
        surfaceContainerLow = blend(start.surfaceContainerLow, stop.surfaceContainerLow, fraction),
        surfaceContainer = blend(start.surfaceContainer, stop.surfaceContainer, fraction),
        surfaceContainerHigh = blend(start.surfaceContainerHigh, stop.surfaceContainerHigh, fraction),
        surfaceContainerHighest = blend(start.surfaceContainerHighest, stop.surfaceContainerHighest, fraction)
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
 * Both locals flip together, so a component that asks "am I dark?" and one that asks
 * "give me a card colour" never disagree mid-switch. Discrete values (the dark flag)
 * change at the midpoint, where the canvas is closest to them.
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
    val surfaces = blendSurfaces(LightDaymarkSurfaces, DarkDaymarkSurfaces, progress)

    CompositionLocalProvider(
        LocalIsDarkTheme provides (progress >= 0.5f),
        LocalDaymarkSurfaces provides surfaces
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SabihonTypography,
            shapes = SabihonShapes,
            content = content
        )
    }
}
