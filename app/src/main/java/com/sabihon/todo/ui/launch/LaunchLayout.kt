package com.sabihon.todo.ui.launch

import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry for the branded launch screen, kept out of the composable so it can be
 * unit-tested on the JVM (no Compose, no Android, plain floats).
 *
 * Everything is expressed in dp as a float. The composable converts to `Dp` once and
 * the animation never touches layout again - it only moves graphics layers.
 *
 * The stage is a square that holds the orbit rings and the logo tile. Deriving
 * everything from one number is what keeps the composition from clipping on a short
 * landscape window or ballooning on a tablet: one clamp, every radius follows.
 */
internal object LaunchLayout {

    /** Cap for the whole composition, so a tablet shows the same calm, small-scale mark. */
    const val MAX_STAGE_DP = 340f

    /** Room left at the sides for the floating cards to sit on, outside the rings. */
    private const val HORIZONTAL_INSET_DP = 20f

    /** Room above and below - the cards live at the stage corners, so this is their margin. */
    private const val VERTICAL_INSET_DP = 28f

    /**
     * Diameter of the square stage, in dp.
     *
     * Never larger than the window in either axis, never larger than [MAX_STAGE_DP].
     * A degenerate window (0 dp, e.g. during a preview measure pass) yields 0 rather
     * than a negative size, which would crash `Modifier.size`.
     */
    fun stageSize(availableWidthDp: Float, availableHeightDp: Float): Float {
        val byWidth = availableWidthDp - HORIZONTAL_INSET_DP * 2f
        val byHeight = availableHeightDp - VERTICAL_INSET_DP * 2f
        return minOf(byWidth, byHeight, MAX_STAGE_DP).coerceAtLeast(0f)
    }

    /** Outer orbit ring, as a radius. */
    fun outerRadius(stageDp: Float): Float = stageDp * 0.40f

    /** Inner orbit ring - closer to the tile, same centre. */
    fun innerRadius(stageDp: Float): Float = outerRadius(stageDp) * 0.72f

    /**
     * The lavender tile. Its half-diagonal (~0.707 of its side) stays inside
     * [innerRadius], so the tile never touches the ring even at the corners.
     */
    fun tileSize(stageDp: Float): Float = outerRadius(stageDp) * 0.78f

    /** Horizontal offset of a point on the orbit, from the centre. */
    fun orbitOffsetX(angleDegrees: Float, radiusDp: Float): Float =
        radiusDp * cos(Math.toRadians(angleDegrees.toDouble())).toFloat()

    /** Vertical offset of a point on the orbit. Screen y grows downwards. */
    fun orbitOffsetY(angleDegrees: Float, radiusDp: Float): Float =
        radiusDp * sin(Math.toRadians(angleDegrees.toDouble())).toFloat()
}
