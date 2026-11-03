package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The focus field (gradient lab round 4, owner 2026-11-03). */
class FocusFieldTest {

    private val red = argb(220, 40, 40)
    private val blue = argb(40, 60, 220)

    /** A [size]² cover: left half red, right half blue. */
    private fun splitCover(size: Int = 48) = IntArray(size * size) { i -> if (i % size < size / 2) red else blue }

    @Test
    fun should_readTheCoverByPosition_when_built() {
        val field = buildCoverField(splitCover(), 48, 48)!!

        assertEquals(red, field.at(0.1f, 0.5f))
        assertEquals(blue, field.at(0.9f, 0.5f))
        assertEquals(CoverField.GridSize * CoverField.GridSize, field.grid.size)
    }

    @Test
    fun should_findBothHuesAndPadThePalette_when_theCoverHasTwo() {
        val field = buildCoverField(splitCover(), 48, 48)!!

        assertEquals(CoverField.PaletteSize, field.palette.size)
        assertTrue(red in field.palette.toList())
        assertTrue(blue in field.palette.toList())
    }

    @Test
    fun should_returnNothing_when_thereIsNoImage() {
        assertNull(buildCoverField(IntArray(0), 0, 0))
        assertNull(buildCoverField(IntArray(10), 10, 10))
    }

    @Test
    fun should_keepTheHueButPinTheLightness_when_toningForTheRoom() {
        val hsl = FloatArray(3)
        argbToHsl(toneForRoom(red, dark = false), hsl)
        assertEquals(0f, hsl[0], 2f)
        assertTrue(hsl[2] in 0.70f..0.94f)

        argbToHsl(toneForRoom(argb(250, 250, 250), dark = true), hsl)
        assertTrue(hsl[2] <= 0.13f + 0.42f + 0.001f)
    }

    @Test
    fun should_openOutFromTheCover_when_focusSpreads() {
        val field = buildCoverField(splitCover(), 48, 48)!!
        fun points(spread: Float) = focusFieldPoints(
            field, dark = false, width = 400f, height = 800f,
            coverCenterX = 200f, coverCenterY = 300f, coverSize = 300f,
            spread = spread, timeMs = 0f,
        )
        val packed = points(0f)
        val open = points(1f)

        assertEquals(16, open.size)
        // Every point starts closer to the cover's centre than where it ends.
        packed.zip(open).forEach { (a, b) ->
            assertTrue(dist(a, 200f, 300f) <= dist(b, 200f, 300f) + 0.01f)
            assertTrue(a.reach < b.reach)
        }
        // The wanderer is last and carries the cover's most vivid colour.
        assertEquals(toneForRoom(field.accent, dark = false, bias = -0.02f), open.wandererColor())
    }

    @Test
    fun should_holdStill_when_timeIsHeld() {
        val field = buildCoverField(splitCover(), 48, 48)!!
        fun at(t: Float) = focusFieldPoints(field, true, 400f, 800f, 200f, 300f, 300f, 1f, t)

        assertEquals(at(5_000f), at(5_000f))
        assertNotEquals(at(0f), at(5_000f))
    }

    @Test
    fun should_paintThePointsColour_when_aCellSitsOnIt() {
        val points = listOf(
            FieldPoint(x = 50f, y = 50f, color = red, reach = 20f, weight = 1f),
            FieldPoint(x = 350f, y = 50f, color = blue, reach = 20f, weight = 1f),
        )
        val out = IntArray(8 * 2)
        renderFocusField(points, base = argb(0, 0, 0), width = 400f, height = 100f, gridWidth = 8, gridHeight = 2, out = out)

        // Cell (0, 0) is centred on (25, 25), near the red point.
        assertEquals(220f, ((out[0] shr 16) and 0xFF).toFloat(), 25f)
        // The last cell of the row is near the blue point.
        assertEquals(220f, (out[7] and 0xFF).toFloat(), 25f)
    }

    private fun dist(p: FieldPoint, x: Float, y: Float): Float {
        val dx = p.x - x
        val dy = p.y - y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
