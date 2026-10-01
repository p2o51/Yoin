package com.gpo.yoin.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SeamDissolveTest {
    private val lattice = SeamHalftone.lattice(width = 300f, pitch = 15f)
    private val out = FloatArray(3)

    private fun radiusAt(row: Int, column: Int, seam: Float, band: Float, seed: Float, flowShift: Float = 0f): Float {
        SeamHalftone.dot(lattice, 300f, 300f, column, row, seam, band, seed, flowShift, out)
        return out[2]
    }

    @Test
    fun should_haveNoBand_when_contentIsAtRest() {
        assertEquals(0f, seamBandPx(scrolledPx = 0f, revealDistancePx = 100f, bandPx = 90f), 0f)
    }

    @Test
    fun should_growBandWithScroll_when_withinRevealDistance() {
        assertEquals(45f, seamBandPx(scrolledPx = 50f, revealDistancePx = 100f, bandPx = 90f), 1e-4f)
    }

    @Test
    fun should_useFullBand_when_scrolledPastFirstItem() {
        val band = seamBandPx(scrolledPx = Float.POSITIVE_INFINITY, revealDistancePx = 100f, bandPx = 90f)
        assertEquals(90f, band, 0f)
    }

    @Test
    fun should_dropEveryDot_when_itSitsAtOrAboveTheSeam() {
        val seam = 150f
        for (seed in listOf(0f, 3.7f, 9.9f)) {
            for (row in -1..8) {
                val centreY = (row + .5f) * lattice.rowHeight
                if (centreY > seam) continue
                for (column in -1..lattice.columns) {
                    assertEquals(0f, radiusAt(row, column, seam, band = 90f, seed = seed), 0f)
                }
            }
        }
    }

    @Test
    fun should_printWholeDots_when_theyReachTheSolidLine() {
        val band = 90f
        val row = 20
        val seam = (row + .5f) * lattice.rowHeight - band * SeamHalftone.SolidFrom
        for (seed in listOf(0f, 3.7f, 9.9f)) {
            for (column in -1..lattice.columns) {
                assertEquals(lattice.fullRadius, radiusAt(row, column, seam, band, seed), 1e-3f)
            }
        }
    }

    @Test
    fun should_moveDotsButKeepTheirSize_when_flowCoasts() {
        // A dot mid-band: the afterglow swirls it without re-sizing it, so the
        // print never opens or closes while the flow settles.
        val band = 90f
        val row = 6
        val seam = (row + .5f) * lattice.rowHeight - band * .55f
        val column = 7
        val still = radiusAt(row, column, seam, band, seed = 2f).also { assertTrue(it > 0f) }
        val stillCentre = out[0] to out[1]
        val coasting = radiusAt(row, column, seam, band, seed = 2f, flowShift = .4f)
        assertEquals(still, coasting, 0f)
        assertTrue(stillCentre != (out[0] to out[1]))
    }

    @Test
    fun should_leaveNoPinholes_when_dotsAreWhole() {
        // A staggered lattice is fully covered once each dot reaches the
        // cell's covering radius (cell width / √3).
        assertTrue(lattice.fullRadius >= lattice.cellWidth / sqrt(3f))
    }
}
