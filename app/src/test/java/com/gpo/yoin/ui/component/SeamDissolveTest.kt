package com.gpo.yoin.ui.component

import androidx.compose.ui.graphics.Color
import kotlin.math.exp
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamDissolveTest {
    private val lattice = SeamHalftone.lattice(width = 300f, pitch = 15f)
    private val out = FloatArray(3)

    private fun top(seam: Float, band: Float = 36f, front: Float = 9f, flowShift: Float = 0f) =
        SeamHalftone.Top().also {
            it.seam = seam
            it.band = band
            it.front = front
            it.flowShift = flowShift
        }

    /** A field whose approach runs 120..240 (yb), the bar's top at 200, the screen's edge at 400. */
    private fun tail(reveal: Float = 1f, length: Float = 120f, flowShift: Float = 0f) = SeamHalftone.Tail().also {
        it.yb = 240f
        it.len = length
        it.y0 = it.yb - length
        it.end = 400f
        it.guard = 200f - 28f
        it.k0 = SeamHalftone.radiusForCoverage(SeamDissolveTokens.FieldCoverage)
        it.k1 = it.k0 * SeamDissolveTokens.FieldThinning
        it.front = 9f
        it.frontNear = 30f
        it.flowShift = flowShift
        it.reveal = reveal
        it.barLeft = 40f
        it.barTop = 200f
        it.barRight = 260f
        it.barBottom = 300f
        it.barRadius = 60f
        it.barLum = 80f
        it.bgLum = 98f
        it.yieldReach = 21f
        it.yieldInset = 4.5f
        it.fadeEnd = 460f
    }

    private fun dotAt(
        row: Int,
        column: Int,
        seed: Float = 2f,
        disorder: Float = 0f,
        top: SeamHalftone.Top? = null,
        tail: SeamHalftone.Tail? = null,
        lum: Float = Float.NaN,
    ): Boolean = SeamHalftone.dot(lattice, 300f, 600f, column, row, seed, disorder, top, tail, lum, out)

    private fun siteY(row: Int) = (row + .5f) * lattice.rowHeight

    // ── Rollback switch: D is computed in one place (dissolve-final §1.7, §4.10) ──

    @Test
    fun should_beRegularAtRest_when_settleAtRestIsOn() {
        assertEquals(0f, seamDisorder(0f, settleAtRest = true), 0f)
    }

    @Test
    fun should_reachOneMinusOneOverE_when_speedIsTheDisorderSpeed() {
        assertEquals(1f - exp(-1f), seamDisorder(200f, settleAtRest = true), 1e-4f)
        assertEquals(.632f, seamDisorder(200f, settleAtRest = true), 1e-3f)
    }

    @Test
    fun should_growMonotonicallyWithSpeed_when_settleAtRestIsOn() {
        var previous = -1f
        for (speed in listOf(0f, 1f, 10f, 50f, 100f, 200f, 400f, 800f, 1600f, 5000f)) {
            val disorder = seamDisorder(speed, settleAtRest = true)
            assertTrue("D($speed) = $disorder", disorder > previous)
            assertTrue(disorder in 0f..1f)
            previous = disorder
        }
        assertEquals(.22f, seamDisorder(50f, settleAtRest = true), .01f)
        assertEquals(.98f, seamDisorder(800f, settleAtRest = true), .01f)
    }

    @Test
    fun should_alwaysBeOne_when_settleAtRestIsOff() {
        for (speed in listOf(0f, 1f, 50f, 200f, 800f, 5000f)) {
            assertEquals(1f, seamDisorder(speed, settleAtRest = false), 0f)
        }
    }

    // ── Stretch, reveal, text ──

    @Test
    fun should_notStretch_when_slowerThanTheOnsetSpeed() {
        assertEquals(0f, seamStretch(0f), 0f)
        assertEquals(0f, seamStretch(150f), 0f)
        assertEquals(1f, seamStretch(1450f), 0f)
        assertEquals(1f, seamStretch(4000f), 0f)
        assertTrue(seamStretch(800f) in .4f..1f)
    }

    @Test
    fun should_haveNoBand_when_contentIsAtRest() {
        assertEquals(0f, seamReveal(scrolledPx = 0f, revealDistancePx = 100f), 0f)
        assertEquals(.5f, seamReveal(scrolledPx = 50f, revealDistancePx = 100f), 1e-6f)
        assertEquals(1f, seamReveal(scrolledPx = Float.POSITIVE_INFINITY, revealDistancePx = 100f), 0f)
    }

    @Test
    fun should_fadeTextOverTheBand_when_crossingTheSeam() {
        assertEquals(0f, seamTextAlpha(0f), 0f)
        assertEquals(0f, seamTextAlpha(.05f), 0f)
        assertEquals(1f, seamTextAlpha(1f), 1e-6f)
        var previous = 0f
        for (step in 6..100) {
            val alpha = seamTextAlpha(step / 100f)
            assertTrue(alpha >= previous)
            previous = alpha
        }
    }

    // ── Curve C ──

    @Test
    fun should_dropEveryDot_when_itSitsAtOrAboveTheSeam() {
        val seam = 150f
        for (disorder in listOf(0f, 1f)) {
            for (seed in listOf(0f, 3.7f, 9.9f)) {
                for (row in -1..8) {
                    if (siteY(row) > seam) continue
                    for (column in -1..lattice.columns) {
                        dotAt(row, column, seed, disorder, top = top(seam))
                        assertEquals(0f, out[2], 0f)
                    }
                }
            }
        }
    }

    @Test
    fun should_leaveDotsWhole_when_theyAreBelowBandAndFront() {
        val row = 20
        val seam = siteY(row) - 36f - 9f
        for (seed in listOf(0f, 3.7f, 9.9f)) {
            for (column in -1..lattice.columns) {
                assertFalse(dotAt(row, column, seed, disorder = 1f, top = top(seam)))
                assertEquals(lattice.fullRadius, out[2], 0f)
            }
        }
    }

    @Test
    fun should_printRegularDots_when_disorderIsZero() {
        // At rest every dot sits on its lattice site and its size depends on
        // its distance to the seam only — not on the item's bend or its jitter.
        val row = 6
        val seam = siteY(row) - 36f * .5f
        var radius = Float.NaN
        for (seed in listOf(0f, 3.7f, 9.9f)) {
            for (column in 0 until lattice.columns) {
                dotAt(row, column, seed, disorder = 0f, top = top(seam, flowShift = .3f))
                val stagger = if (row and 1 == 0) 0f else .5f
                assertEquals((column + .5f + stagger) * lattice.cellWidth, out[0], 1e-3f)
                assertEquals(siteY(row), out[1], 1e-3f)
                if (radius.isNaN()) radius = out[2] else assertEquals(radius, out[2], 1e-4f)
            }
        }
        assertTrue(radius > 0f && radius < lattice.fullRadius)
    }

    @Test
    fun should_swirlAndRagTheFront_when_scrolling() {
        val row = 6
        val seam = siteY(row) - 36f * .5f
        val radii = HashSet<Float>()
        var moved = false
        for (column in 0 until lattice.columns) {
            dotAt(row, column, seed = 2f, disorder = 1f, top = top(seam))
            radii += out[2]
            if (out[1] != siteY(row)) moved = true
        }
        assertTrue(moved)
        assertTrue(radii.size > 1)
    }

    @Test
    fun should_moveDotsButKeepTheirSize_when_flowCoasts() {
        // A dot mid-band: the afterglow swirls it without re-sizing it, so the
        // print never opens or closes while the flow settles.
        val row = 6
        val seam = siteY(row) - 36f * .55f
        dotAt(row, 7, disorder = 1f, top = top(seam))
        val still = out[2].also { assertTrue(it > 0f) }
        val stillCentre = out[0] to out[1]
        dotAt(row, 7, disorder = 1f, top = top(seam, flowShift = .4f))
        assertEquals(still, out[2], 0f)
        assertTrue(stillCentre != (out[0] to out[1]))
    }

    @Test
    fun should_leaveNoPinholes_when_dotsAreWhole() {
        // A staggered lattice is fully covered once each dot reaches the
        // cell's covering radius (cell width / √3) — both the whole print the
        // field grows back to and curve C's end of band.
        assertTrue(lattice.fullRadius >= lattice.cellWidth / sqrt(3f))
        assertTrue(SeamDissolveTokens.DotRadius * lattice.cellWidth >= lattice.cellWidth / sqrt(3f))
    }

    // ── Bottom field ──

    private fun rowNear(y: Float): Int = ((y / lattice.rowHeight) - .5f).toInt()

    @Test
    fun should_leaveContentWhole_when_aboveTheFieldsStart() {
        val field = tail()
        val row = rowNear(field.y0 - 20f)
        for (column in -1..lattice.columns) {
            assertFalse(dotAt(row, column, disorder = 1f, tail = field))
            assertEquals(lattice.fullRadius, out[2], 0f)
        }
    }

    @Test
    fun should_printAStillLattice_when_underTheBar() {
        val field = tail()
        val row = rowNear(field.yb + 30f)
        assertTrue(siteY(row) > field.yb)
        for (column in 0 until lattice.columns) {
            assertTrue(dotAt(row, column, disorder = 1f, tail = field))
            val stagger = if (row and 1 == 0) 0f else .5f
            assertEquals((column + .5f + stagger) * lattice.cellWidth, out[0], 1e-3f)
            assertEquals(siteY(row), out[1], 1e-3f)
            assertTrue(out[2] < field.k0 * lattice.cellWidth + 1e-3f)
            assertTrue(out[2] >= field.k1 * lattice.cellWidth - 1e-3f)
        }
    }

    @Test
    fun should_regularizeTheApproach_when_disorderIsZero() {
        val field = tail()
        val row = rowNear(field.y0 + field.len * .5f)
        var radius = Float.NaN
        for (column in 0 until lattice.columns) {
            dotAt(row, column, seed = 4.2f, disorder = 0f, tail = field)
            assertEquals(siteY(row), out[1], 1e-3f)
            if (radius.isNaN()) radius = out[2] else assertEquals(radius, out[2], 1e-4f)
        }
        // Between the whole-print start (0.62 cells) and the field (k0).
        assertTrue(radius < .62f * lattice.cellWidth && radius > field.k0 * lattice.cellWidth)
    }

    @Test
    fun should_swirlAndRagTheApproach_when_scrolling() {
        val field = tail()
        val row = rowNear(field.y0 + field.len * .5f)
        val radii = HashSet<Float>()
        var moved = false
        for (column in 0 until lattice.columns) {
            dotAt(row, column, seed = 4.2f, disorder = 1f, tail = field)
            radii += out[2]
            if (out[1] != siteY(row)) moved = true
        }
        assertTrue(moved)
        assertTrue(radii.size > 1)
    }

    @Test
    fun should_moveDotsButKeepTheirSize_when_theFieldsFlowCoasts() {
        val row = rowNear(tail().y0 + tail().len * .5f)
        dotAt(row, 7, disorder = 1f, tail = tail())
        val still = out[2].also { assertTrue(it > 0f) }
        val stillCentre = out[0] to out[1]
        dotAt(row, 7, disorder = 1f, tail = tail(flowShift = .4f))
        assertEquals(still, out[2], 0f)
        assertTrue(stillCentre != (out[0] to out[1]))
    }

    @Test
    fun should_endAsSolidContent_when_theListReachesItsEnd() {
        // rb = 0: no approach above the hidden line — the last item sits whole above the bar.
        val field = tail(length = 0f)
        for (y in listOf(field.barTop - 30f, field.barTop - 1f, field.yb - 2f)) {
            assertFalse(dotAt(rowNear(y), 3, disorder = 1f, tail = field))
        }
    }

    @Test
    fun should_growBackToWholePrint_when_theFieldFadesOut() {
        val field = tail(reveal = 0f)
        val row = rowNear(field.yb + 40f)
        dotAt(row, 4, disorder = 1f, tail = field)
        assertEquals(lattice.fullRadius, out[2], 1e-4f)
    }

    @Test
    fun should_yieldOnlyNextToTheBar_when_lightnessIsCloseToTheBars() {
        val field = tail()
        // The first row of dots on the bar, horizontally inside it.
        val row = (0..60).first { siteY(it) >= field.barTop }
        val column = 8
        val similar = run {
            dotAt(row, column, disorder = 0f, tail = field, lum = field.barLum)
            out[2]
        }
        val contrasting = run {
            dotAt(row, column, disorder = 0f, tail = field, lum = field.barLum - 40f)
            out[2]
        }
        val unknown = run {
            dotAt(row, column, disorder = 0f, tail = field)
            out[2]
        }
        assertEquals(0f, similar, 1e-4f)
        assertEquals(unknown, contrasting, 1e-4f)
        assertTrue(contrasting > 0f)
        // Far from the bar a similar dot keeps its size.
        val far = rowNear(field.end - 4f)
        dotAt(far, 0, disorder = 0f, tail = field, lum = field.barLum)
        val farSimilar = out[2]
        dotAt(far, 0, disorder = 0f, tail = field)
        assertEquals(out[2], farSimilar, 1e-4f)
    }

    @Test
    fun should_yieldWholeContent_when_itSitsAgainstTheBar() {
        // Content that has not started to break up still gives way beside the bar.
        val field = tail(length = 0f)
        val row = rowNear(field.barTop - 2f)
        dotAt(row, 8, disorder = 0f, tail = field, lum = field.barLum)
        assertTrue(out[2] < lattice.fullRadius)
    }

    @Test
    fun should_easeTowardThePage_when_deeperInTheField() {
        val field = tail()
        assertEquals(0f, field.fadeAt(field.y0), 0f)
        assertEquals(SeamDissolveTokens.FieldFadeAtBar, field.fadeAt(field.barTop), 1e-5f)
        assertEquals(SeamDissolveTokens.FieldFadeAtEdge, field.fadeAt(field.fadeEnd), 1e-5f)
        assertTrue(field.fadeAt(field.barTop + 50f) > field.fadeAt(field.barTop))
    }

    @Test
    fun should_measureBarDistance_when_insideOrOutside() {
        val field = tail()
        assertTrue(field.barDistance(150f, 250f) < 0f)
        assertEquals(10f, field.barDistance(150f, 190f), 1e-3f)
        assertEquals(10f, field.barDistance(270f, 250f), 1e-3f)
    }

    @Test
    fun should_matchFieldCoverage_when_derivingK0() {
        assertEquals(.332f, SeamHalftone.radiusForCoverage(.40f), .001f)
    }

    @Test
    fun should_measureToneLikeHct_when_computingLightness() {
        assertEquals(100f, SeamHalftone.lightness(Color.White), .01f)
        assertEquals(0f, SeamHalftone.lightness(Color.Black), .01f)
        assertEquals(53.39f, SeamHalftone.lightness(.5f, .5f, .5f), .05f)
    }
}
