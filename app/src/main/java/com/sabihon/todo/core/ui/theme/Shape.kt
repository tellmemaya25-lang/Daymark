package com.sabihon.todo.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape tokens – cards 24.dp, chips/pills 100.dp (full), buttons 16.dp
 */
val SabihonShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(16.dp), // buttons
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp), // cards
    extraLarge = RoundedCornerShape(100.dp) // pills / chips
)

/**
 * Modal bottom sheets.
 *
 * Material's default is a 28 dp top radius, which on a phone reads as a near-pill and
 * fights the 18 dp grouped cards sitting directly behind it. 20 dp is one step down
 * Material's scale (the "large increased" stop) and lines the sheet up with the card
 * radius underneath, so the sheet looks like it belongs to the page. Only the top
 * corners are rounded - the sheet is anchored to the bottom edge.
 */
val DaymarkSheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
