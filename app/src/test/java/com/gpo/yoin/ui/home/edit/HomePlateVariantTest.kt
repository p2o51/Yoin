package com.gpo.yoin.ui.home.edit

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The edit-mode plate (HomePlateVariant.kt): V1 ships; V0 must stay the pre-decision plate for QA comparison. */
class HomePlateVariantTest {

    @Test
    fun should_shipV1_when_noLookIsGiven() {
        val look = HomePlateLook.Default

        assertEquals(HomePlateVariant.V1, look.variant)
        assertTrue(look.clipsShelves)
        assertFalse(look.titleRowOnly)
        // At rest: today's spacing, the phone's 12dp outset (16dp page margin − 4dp).
        assertEquals(18f, look.spacing(18.dp).value, 0.001f)
        assertEquals(12f, look.plateOutsetH().value, 0.001f)
    }

    @Test
    fun should_keepPreDecisionPlate_when_variantV0() {
        val look = HomePlateLook.Legacy

        assertEquals(HomePlateVariant.V0, look.variant)
        assertEquals(HomeEditTokens.PlateOutsetH, look.plateOutsetH())
        assertEquals(plateOutsetVDp(18f).dp, look.outsetV(18.dp))
        assertEquals(plateOutsetVDp(10f).dp, look.outsetV(10.dp))
        assertEquals(18.dp, look.spacing(18.dp))
        assertFalse(look.spacingMoving())
        assertFalse(look.clipsShelves)
        assertFalse(look.titleRowOnly)
        // V0 never reads the ripple: no extra state read in layout or draw.
        assertEquals(0.dp, look.contentInset { error("V0 must not read the ripple") })
    }

    @Test
    fun should_growSpacingAndOutsets_when_variantV1() {
        var p = 0f
        var margin = 16.dp
        val look = HomePlateLook(HomePlateVariant.V1, progress = { p }, pageMargin = { margin })

        assertEquals(18f, look.spacing(18.dp).value, 0.001f)
        // (18 − 4) / 2 = 7dp while the spacing is still at rest.
        assertEquals(7f, look.outsetV(18.dp).value, 0.001f)
        assertFalse(look.spacingMoving())

        p = 0.5f
        assertTrue(look.spacingMoving())
        assertEquals(25f, look.spacing(18.dp).value, 0.001f)
        // The pure form agrees with the progress-read one.
        assertEquals(look.spacing(18.dp), look.spacingAt(18.dp, p))
        assertEquals(18.dp, HomePlateLook.Legacy.spacingAt(18.dp, 1f))

        p = 1f
        assertFalse(look.spacingMoving())
        assertEquals(32f, look.spacing(18.dp).value, 0.001f)
        assertEquals(12f, look.outsetV(18.dp).value, 0.001f)
        // The landscape phone's tighter feed grows to the same 32dp.
        assertEquals(32f, look.spacing(10.dp).value, 0.001f)

        // Page margin − 4dp, between today's 8dp and 24dp.
        assertEquals(12f, look.plateOutsetH().value, 0.001f)
        margin = 56.dp
        assertEquals(24f, look.plateOutsetH().value, 0.001f)
        margin = 10.dp
        assertEquals(8f, look.plateOutsetH().value, 0.001f)
        assertTrue(look.clipsShelves)
        assertEquals(0.dp, look.contentInset { 1f })
    }

    @Test
    fun should_insetContentInsidePlate_when_variantV2() {
        val look = HomePlateLook(HomePlateVariant.V2)

        assertEquals(0.dp, look.plateOutsetH())
        assertEquals(0f, look.contentInset { 0f }.value, 0.001f)
        assertEquals(12f, look.contentInset { 1f }.value, 0.001f)
        assertEquals(6f, look.contentInset { 0.5f }.value, 0.001f)
        assertEquals(18.dp, look.spacing(18.dp))
        assertTrue(look.clipsShelves)
    }

    @Test
    fun should_drawTitleRowOnly_when_variantV3() {
        val look = HomePlateLook(HomePlateVariant.V3)

        assertTrue(look.titleRowOnly)
        assertFalse(look.clipsShelves)
        assertEquals(HomeEditTokens.PlateOutsetH, look.plateOutsetH())
        assertEquals(plateOutsetVDp(18f).dp, look.outsetV(18.dp))
    }

    @Test
    fun should_keepPlateInsideOpaqueAndEdgesFaded_when_buildingMask() {
        val stops = plateMaskStops(left = 20f, right = 300f, fade = 12f, width = 400f, outside = 0.25f)

        // Ordered positions, all in 0..1.
        stops.map { it.first }.zipWithNext().forEach { (a, b) -> assertTrue("$a > $b", a <= b) }
        assertTrue(stops.all { it.first in 0f..1f })
        assertEquals(0.25f, stops.first().second.alpha, 0.001f)
        assertEquals(0.25f, stops.last().second.alpha, 0.001f)
        // Fully opaque from 12dp inside each plate edge.
        val inside = stops.filter { it.first in (32f / 400f)..(288f / 400f) }
        assertTrue(inside.isNotEmpty())
        assertTrue(inside.all { it.second.alpha > 0.999f })
        // The plate edges themselves sit at the outside alpha.
        assertEquals(0.25f, stops.first { it.first == 20f / 400f }.second.alpha, 0.001f)
    }

    @Test
    fun should_placeLikeSpacedBy_when_arrangingAtPlateSpacing() {
        val look = HomePlateLook(HomePlateVariant.V1, progress = { 1f })
        val arrangement = HomePlateSpacing(look, 18.dp)
        val positions = IntArray(3)

        with(arrangement) {
            Density(1f).arrange(totalSize = 1000, sizes = intArrayOf(100, 50, 70), outPositions = positions)
        }

        assertEquals(32f, arrangement.spacing.value, 0.001f)
        assertArrayEquals(intArrayOf(0, 132, 214), positions)
    }
}
