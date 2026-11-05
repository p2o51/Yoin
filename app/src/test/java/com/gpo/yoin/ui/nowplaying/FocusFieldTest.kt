package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

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
        assertTrue(hsl[2] in LightRoomFloor..(LightRoomFloor + LightRoomSpan))

        argbToHsl(toneForRoom(argb(250, 250, 250), dark = true), hsl)
        assertTrue(hsl[2] <= DarkRoomFloor + DarkRoomSpan + 0.001f)
    }

    @Test
    fun should_darkenBrightHues_when_toningForTheDarkRoom() {
        val hsl = FloatArray(3)
        val lime = toneForRoom(argb(180, 220, 60), dark = true, bias = 0.12f)
        val red = toneForRoom(argb(200, 40, 40), dark = true)
        argbToHsl(lime, hsl)
        val limeLightness = hsl[2]
        argbToHsl(red, hsl)
        // Same hue kept, but the lime sits lower than HSL alone would put it.
        assertTrue(limeLightness < DarkRoomFloor + DarkRoomSpan * 0.55f + 0.12f)
        assertTrue(((lime shr 8) and 0xFF) < 170)
        assertTrue(hsl[2] > 0.25f)
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

    @Test
    fun should_keepTheGeometryAndCrossTheColours_when_theCoverChangesInFocus() {
        val from = listOf(FieldPoint(x = 10f, y = 20f, color = red, reach = 5f, weight = 1f))
        val to = listOf(FieldPoint(x = 30f, y = 40f, color = blue, reach = 7f, weight = 2f))

        assertEquals(to.map { it.copy(color = red) }, crossFieldColors(from, to, 0f))
        assertEquals(to, crossFieldColors(from, to, 1f))
        val half = crossFieldColors(from, to, 0.5f).single()
        assertEquals(30f, half.x, 0f)
        assertEquals(130f, ((half.color shr 16) and 0xFF).toFloat(), 1f)
    }

    @Test
    fun should_paintOnlyThePalettesColours_when_aRoomPaletteIsGiven() {
        val field = buildCoverField(splitCover(), 48, 48)!!
        val room = RoomPalette(
            colors = intArrayOf(argb(30, 40, 90), argb(60, 30, 70), argb(20, 70, 60), argb(40, 40, 50)),
            accent = argb(90, 80, 160),
        )
        val withRoom = focusFieldPoints(field, true, 400f, 800f, 200f, 300f, 300f, 1f, 12_000f, room)
        val without = focusFieldPoints(field, true, 400f, 800f, 200f, 300f, 300f, 1f, 12_000f)

        // Same points, same motion…
        assertEquals(without.map { it.x to it.y }, withRoom.map { it.x to it.y })
        // …the room points and the corner take the palette as it is, the wanderer its accent.
        assertEquals(room.colors[0], withRoom[6].color)
        assertEquals(room.colors[1], withRoom[7].color)
        assertEquals(room.colors[1], withRoom[14].color)
        assertEquals(room.accent, withRoom.wandererColor())
        // The rim creeps between neighbouring palette colours.
        assertEquals(room.colors[2], room.around(2f))
        assertEquals(room.colors[0], room.around(4f))
    }

    @Test
    fun should_keepWhiteTextReadableButColourful_when_theRoomComesFromABrightDarkScheme() {
        // The 2025 expressive dark scheme's primary sits near tone 90.
        val room = darkRoomOf(
            primary = argb(220, 240, 150),
            secondary = argb(200, 200, 180),
            tertiary = argb(150, 230, 220),
        )
        room.colors.forEach { c ->
            assertTrue("white on ${Integer.toHexString(c)}", 1.05f / (luminance(c) + 0.05f) >= 8f)
        }
        // The wanderer is the lit point, still readable under white.
        assertTrue(1.05f / (luminance(room.accent) + 0.05f) >= 4.5f)
        val hsl = FloatArray(3)
        // The hue survives: the primary room colour is still yellow-green…
        argbToHsl(room.colors[0], hsl)
        assertEquals(75f, hsl[0], 20f)
        // …and every room colour is a colour, not a grey.
        room.colors.forEach { c ->
            argbToHsl(c, hsl)
            assertTrue("saturation of ${Integer.toHexString(c)}", hsl[1] > 0.3f)
        }
    }

    @Test
    fun should_startEverywhereAtNothingAndEndEverywhereFull_when_theSongPlaysThrough() {
        for (x in listOf(0f, 100f, 200f, 300f, 400f)) {
            assertEquals(0f, journeyAt(x, 400f, 0f, fromRight = true), 0f)
            assertEquals(1f, journeyAt(x, 400f, 1f, fromRight = true), 0f)
            assertEquals(1f, journeyAt(x, 400f, 1f, fromRight = false), 0f)
        }
    }

    @Test
    fun should_enterFromTheSideTheSongCameFrom_when_halfwayThrough() {
        // Skip forward / auto-advance: right first. Skip back: left first.
        assertTrue(journeyAt(380f, 400f, 0.4f, fromRight = true) > journeyAt(20f, 400f, 0.4f, fromRight = true))
        assertTrue(journeyAt(20f, 400f, 0.4f, fromRight = false) > journeyAt(380f, 400f, 0.4f, fromRight = false))
        assertEquals(
            journeyAt(380f, 400f, 0.4f, fromRight = true),
            journeyAt(20f, 400f, 0.4f, fromRight = false),
            0.0001f,
        )
    }

    @Test
    fun should_moveTheColoursOnlyTowardTheSecondHue_when_journeying() {
        val base = listOf(
            FieldPoint(x = 0f, y = 0f, color = red, reach = 5f, weight = 1f),
            FieldPoint(x = 400f, y = 0f, color = red, reach = 5f, weight = 1f),
        )
        val toward = base.map { it.copy(x = it.x + 1f, color = blue) }

        assertEquals(base, journeyFieldColors(base, toward, 400f, 0f, fromRight = true))
        val mid = journeyFieldColors(base, toward, 400f, 0.5f, fromRight = true)
        // Geometry stays the base's; the right point has gone further than the left.
        assertEquals(base.map { it.x }, mid.map { it.x })
        assertTrue((mid[1].color and 0xFF) > (mid[0].color and 0xFF))
        // At the end the room holds JourneyMaxMix of the second hue, everywhere.
        val end = journeyFieldColors(base, toward, 400f, 1f, fromRight = true)
        end.forEach { assertEquals(40f + (220f - 40f) * JourneyMaxMix, (it.color and 0xFF).toFloat(), 1f) }
    }

    @Test
    fun should_journeyToTheMostDifferentHue_when_journeyed() {
        val peach = argb(240, 170, 120)
        val apricot = argb(245, 185, 130)
        val teal = argb(40, 160, 160)
        val order = journeyOrder(intArrayOf(peach, apricot, teal, argb(90, 90, 90)), candidates = 3)
        // Teal, not the peach's near twin, leads; nothing is lost.
        assertEquals(teal, order[0])
        assertEquals(listOf(peach, apricot, argb(90, 90, 90)), order.drop(1))

        val room = RoomPalette(intArrayOf(peach, teal, apricot, argb(20, 20, 30)), accent = red)
        val j = room.journeyed()
        assertEquals(teal, j.colors[0])
        assertEquals(argb(20, 20, 30), j.colors[3])
        assertEquals(red, j.accent)
    }

    @Test
    fun should_sweepInFromTheSideTheSongCameFrom_when_theCoverChangesInFocus() {
        val from = listOf(0f, 400f).map { FieldPoint(x = it, y = 0f, color = red, reach = 5f, weight = 1f) }
        val to = from.map { it.copy(color = blue) }

        // Uniform without a side, as before.
        val even = crossFieldColors(from, to, 0.5f)
        assertEquals(even[0].color, even[1].color)
        // Forward: the right point has turned while the left still holds the old colour.
        val fwd = crossFieldColors(from, to, 0.5f, fromRight = true, width = 400f)
        assertEquals(red, fwd[0].color)
        assertEquals(blue, fwd[1].color)
        // Back: the other way round.
        val back = crossFieldColors(from, to, 0.5f, fromRight = false, width = 400f)
        assertEquals(blue, back[0].color)
        assertEquals(red, back[1].color)
        // Done at the end either way.
        assertEquals(to, crossFieldColors(from, to, 1f, fromRight = true, width = 400f))
    }

    @Test
    fun should_keepTheLyricReadable_when_theRoomIsTooDeep() {
        val accent = argb(103, 80, 164) // a tone-40 purple, like a light scheme's primary
        val deep = FieldPoint(x = 0f, y = 0f, color = argb(230, 120, 170), reach = 5f, weight = 1f)
        val pale = deep.copy(color = argb(250, 240, 245))
        val out = listOf(deep, pale).readableUnder(accent)

        val contrast = (luminance(out[0].color) + 0.05f) / (luminance(accent) + 0.05f)
        assertTrue("contrast $contrast", contrast >= FocusLyricContrast - 0.01f)
        // Lifted, not greyed: the hue survives.
        val hsl = FloatArray(3)
        argbToHsl(out[0].color, hsl)
        val before = FloatArray(3)
        argbToHsl(deep.color, before)
        assertEquals(before[0], hsl[0], 3f)
        // Already readable colours are left alone.
        assertEquals(pale, out[1])
    }

    private fun luminance(c: Int): Float {
        fun ch(v: Int): Float {
            val x = v / 255f
            return if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
        return 0.2126f * ch((c shr 16) and 0xFF) + 0.7152f * ch((c shr 8) and 0xFF) + 0.0722f * ch(c and 0xFF)
    }

    private fun dist(p: FieldPoint, x: Float, y: Float): Float {
        val dx = p.x - x
        val dy = p.y - y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
