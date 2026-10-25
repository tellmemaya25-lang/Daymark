package com.sabihon.todo

import com.sabihon.todo.ui.launch.LaunchLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Geometry for the branded launch screen.
 *
 * These are the guarantees the composition's layout rests on: the rings must fit the
 * window at every size, the tile must sit inside the inner ring, and the satellite
 * must actually travel on the outer ring. No Compose here - the maths is deliberately
 * plain floats so it can be checked on the JVM.
 *
 * Window sizes are dp, as reported by `BoxWithConstraints`.
 */
class LaunchLayoutTest {

    private val phone = 360f to 800f
    private val smallPhone = 320f to 568f
    private val landscape = 800f to 360f
    private val tablet = 1200f to 1600f

    private val windows = listOf(phone, smallPhone, landscape, tablet)

    /** JUnit 4 has no float overload, and Kotlin will not widen one for us. */
    private fun assertClose(expected: Float, actual: Float, message: String = "") {
        assertEquals(message, expected.toDouble(), actual.toDouble(), 0.01)
    }

    @Test
    fun `stage fits inside the window`() {
        windows.forEach { (width, height) ->
            val stage = LaunchLayout.stageSize(width, height)
            assertTrue("stage $stage must fit width $width", stage <= width)
            assertTrue("stage $stage must fit height $height", stage <= height)
        }
    }

    @Test
    fun `stage is the shorter axis minus its insets`() {
        // 360 - 2*20 = 320 of width, 800 - 2*28 = 744 of height, so width wins.
        assertClose(320f, LaunchLayout.stageSize(phone.first, phone.second))
        // Landscape: 360 - 56 of height is now the shorter axis.
        assertClose(304f, LaunchLayout.stageSize(landscape.first, landscape.second))
    }

    @Test
    fun `stage is capped on a tablet`() {
        assertClose(
            LaunchLayout.MAX_STAGE_DP,
            LaunchLayout.stageSize(tablet.first, tablet.second)
        )
    }

    @Test
    fun `degenerate window yields zero rather than a negative size`() {
        assertClose(0f, LaunchLayout.stageSize(0f, 0f))
        assertClose(0f, LaunchLayout.stageSize(20f, 20f))
    }

    @Test
    fun `orbits stay inside the stage`() {
        windows.forEach { (width, height) ->
            val stage = LaunchLayout.stageSize(width, height)
            assertTrue(
                "outer ring must fit the stage",
                2f * LaunchLayout.outerRadius(stage) <= stage
            )
            assertTrue(
                "inner ring must be inside the outer",
                LaunchLayout.innerRadius(stage) < LaunchLayout.outerRadius(stage)
            )
        }
    }

    @Test
    fun `tile sits inside the inner ring including its corners`() {
        windows.forEach { (width, height) ->
            val stage = LaunchLayout.stageSize(width, height)
            val halfDiagonal = LaunchLayout.tileSize(stage) * sqrt(2f) / 2f
            assertTrue(
                "tile corner $halfDiagonal must clear the inner ring",
                halfDiagonal < LaunchLayout.innerRadius(stage)
            )
        }
    }

    @Test
    fun `satellite rides the outer ring at every angle`() {
        val radius = LaunchLayout.outerRadius(LaunchLayout.stageSize(phone.first, phone.second))
        listOf(0f, 45f, 90f, 135f, 180f, 270f, 359f).forEach { angle ->
            val x = LaunchLayout.orbitOffsetX(angle, radius)
            val y = LaunchLayout.orbitOffsetY(angle, radius)
            assertClose(radius, hypot(x, y), "radius at $angle degrees")
        }
    }

    @Test
    fun `satellite is never pushed outside the stage box`() {
        windows.forEach { (width, height) ->
            val stage = LaunchLayout.stageSize(width, height)
            assertTrue(
                "orbit plus the dot's own radius must stay inside the stage",
                LaunchLayout.outerRadius(stage) + SATELLITE_HALF <= stage / 2f
            )
        }
    }

    private companion object {
        /** Half of the 18 dp field the satellite is drawn in. */
        const val SATELLITE_HALF = 9f
    }
}
