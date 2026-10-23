package com.sabihon.todo.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens – pastel palette as per spec.
 * Light + dark variants tonally adjusted.
 */

// Base palette – light
val SoftBlue = Color(0xFFD6E4FF)
val SoftYellow = Color(0xFFFDF3A0)
val SoftMint = Color(0xFFCFF5E7)
val SoftPink = Color(0xFFFBD7EA)
val SoftLilac = Color(0xFFE5DBFF)
val DeepGreen = Color(0xFF2F6B4F)
val AccentBlue = Color(0xFF2F6BFF)

val BgLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFF5F6FA)
val TextPrimaryLight = Color(0xFF101114)
val TextSecondaryLight = Color(0xFF6B7280)

// Dark adjustments – tonally adjusted pastels (less saturated, darker)
val SoftBlueDark = Color(0xFF2D3D5E)
val SoftYellowDark = Color(0xFF5A5428)
val SoftMintDark = Color(0xFF244A3C)
val SoftPinkDark = Color(0xFF5A3A4A)
val SoftLilacDark = Color(0xFF3D2E5E)
val DeepGreenDark = Color(0xFF1E4A35)
val BgDark = Color(0xFF121214)
val SurfaceVariantDark = Color(0xFF1E1F25)
val TextPrimaryDark = Color(0xFFF5F5F5)
val TextSecondaryDark = Color(0xFF9CA3AF)

// Priority colors
val PriorityLow = Color(0xFF8BC48A)
val PriorityMedium = Color(0xFFF2C94C)
val PriorityHigh = Color(0xFFF2994A)
val PriorityUrgent = Color(0xFFEB5757)

// Priority background tints
val PriorityLowBg = Color(0xFFE8F5E9)
val PriorityMediumBg = Color(0xFFFFF8E1)
val PriorityHighBg = Color(0xFFFFF3E0)
val PriorityUrgentBg = Color(0xFFFFEBEE)

// Category palette list for reuse
val CategoryPalette = listOf(
    SoftBlue, SoftYellow, SoftMint, SoftPink, SoftLilac, DeepGreen
)

val CategoryPaletteHex = listOf(
    "#D6E4FF", "#FDF3A0", "#CFF5E7", "#FBD7EA", "#E5DBFF", "#2F6B4F"
)

// Additional semantic colors
val DividerLight = Color(0xFFE5E7EB)
val DividerDark = Color(0xFF2E2F38)
val Scrim = Color(0x80000000)

/**
 * Helper to parse hex color string.
 */
fun parseColorHex(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: Exception) {
        SoftBlue
    }
}

/**
 * daymark brand tokens – derived from the splash / home / task-list design refs.
 * The lavender + navy pair is the same one used by the launcher icon.
 */
val DaymarkLavender = Color(0xFFC4D2FF)
val DaymarkLavenderSoft = Color(0xFFD9E2FF)
val DaymarkLavenderDeep = Color(0xFFAFC0F5)
val DaymarkNavy = Color(0xFF243B71)
val DaymarkEyebrow = Color(0xFFA9B8F0)

// Dark surfaces used by the home mock (near-black canvas, slightly raised cards)
val DaymarkDarkCanvas = Color(0xFF101114)
val DaymarkDarkCard = Color(0xFF1A1C22)
val DaymarkDarkCardRaised = Color(0xFF20232C)
val DaymarkDarkCardFocus = Color(0xFF232B45)
val DaymarkDarkHairline = Color(0xFF2A2D36)
