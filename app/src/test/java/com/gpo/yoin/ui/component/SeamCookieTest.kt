package com.gpo.yoin.ui.component

import androidx.compose.ui.unit.Density
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 曲奇浪口: the Cookie wave top seam's geometry (SeamCookie.kt). */
class SeamCookieTest {
    private val density = Density(1f)

    /** A phone album cover: 118.7dp square (density 1, so px = dp). */
    private val width = 118.7f
    private val height = 118.7f

    private fun lip(
        seam: Float,
        stretch: Float = 0f,
        disorder: Float = 0f,
        lagPx: Float = 0f,
        travelPx: Float = 0f,
        centreX: Float = 100f,
        reveal: Float = 1f,
        reduced: Boolean = false,
        itemWidth: Float = width,
        itemHeight: Float = height,
    ): SeamCookie.Lip? {
        val lip = SeamCookie.Lip()
        val touched = with(SeamCookie) {
            density.configure(
                lip = lip,
                seam = seam,
                width = itemWidth,
                height = itemHeight,
                centreX = centreX,
                stretch = stretch,
                disorder = disorder,
                lagPx = lagPx,
                travelPx = travelPx,
                reveal = reveal,
                reduced = reduced,
            )
        }
        return if (touched) lip else null
    }

    private fun fronts(lip: SeamCookie.Lip): List<Float> =
        (0..1187).map { SeamCookie.front(lip, width, height, it / 10f, round = false) }

    @Test
    fun should_beFlatAtBothEnds_when_theExitStartsOrEnds() {
        assertEquals(0f, SeamCookie.envelope(0f), 0f)
        assertEquals(0f, SeamCookie.envelope(1f), 0f)
        assertEquals(1f, SeamCookie.envelope(.5f), 0f)
    }

    @Test
    fun should_morphCookieThroughSineToSoftBurst_when_theExitProgresses() {
        assertEquals(.5f, SeamCookie.character(0f), 1e-5f)
        assertEquals(.5f, SeamCookie.character(.2f), 1e-5f)
        // Halfway the lip is an almost pure sine: the geometric mean of .5 and 1.8.
        assertEquals(sqrt(.5f * 1.8f), SeamCookie.character(.5f), 1e-4f)
        assertEquals(1.8f, SeamCookie.character(.8f), 1e-4f)
        assertEquals(1.8f, SeamCookie.character(1f), 1e-4f)
    }

    @Test
    fun should_keepTipsRound_when_theWaveIsTall() {
        // Phone at rest: λ 29.7dp, A 4dp → p caps at ≈1.39 (SoftBurst would be 1.8).
        val capped = SeamCookie.tipFloor(1.8f, 118.7f / 4f, 4f, 4f)
        assertEquals(1.39f, capped, .01f)
        assertEquals(.5f, SeamCookie.tipFloor(.5f, 118.7f / 4f, 4f, 4f), 0f)
        assertEquals(1.8f, SeamCookie.tipFloor(1.8f, 118.7f / 4f, 0f, 4f), 0f)
    }

    @Test
    fun should_countLobesOfAbout30dp_when_sizingTheLip() {
        assertEquals(4, SeamCookie.lobeCount(118.7f, 30f))
        assertEquals(4, SeamCookie.lobeCount(128f, 30f))
        assertEquals(5, SeamCookie.lobeCount(152f, 30f))
        assertEquals(2, SeamCookie.lobeCount(48f, 30f))
    }

    @Test
    fun should_leaveTheItemUntouched_when_itIsWellBelowTheSeam() {
        assertTrue(lip(seam = -40f) == null)
        assertTrue(lip(seam = -2f) != null)
        // Nothing before the page has scrolled.
        assertTrue(lip(seam = 30f, reveal = 0f) == null)
    }

    @Test
    fun should_cutAHeightfieldBelowTheSeam_when_midExit() {
        for (stretch in listOf(0f, 1f)) {
            val lip = lip(seam = 50f, stretch = stretch)!!
            for (front in fronts(lip)) {
                // Never above the seam (no lifted crowns), never past its reach.
                assertTrue(front > 0f)
                assertTrue(front < lip.reach)
            }
        }
    }

    @Test
    fun should_restFourDpWaves_when_atRestMidExit() {
        val lip = lip(seam = 50f)!!
        assertEquals(4f, lip.amplitude, 1e-4f)
        assertEquals(.45f * 12f, lip.rest, 1e-4f)
        // A fling widens the band and the waves (here capped at a fifth of a lobe).
        val flung = lip(seam = 50f, stretch = 1f)!!
        assertEquals(.45f * 22f, flung.rest, 1e-4f)
        assertEquals(minOf(6f, .2f * width / 4f), flung.amplitude, 1e-4f)
        assertTrue(flung.amplitude > lip.amplitude)
    }

    @Test
    fun should_matchAcrossARow_when_atRest() {
        // At rest the lip depends on q alone: two covers of a row are the same.
        val a = lip(seam = 50f, centreX = 60f)!!
        val b = lip(seam = 50f, centreX = 190f)!!
        assertEquals(a.phase, b.phase, 0f)
        assertEquals(a.character, b.character, 0f)
        // While flung, one coherent ripple runs along the row.
        val c = lip(seam = 50f, disorder = 1f, centreX = 60f)!!
        val d = lip(seam = 50f, disorder = 1f, centreX = 190f)!!
        assertTrue(c.phase != d.phase)
    }

    @Test
    fun should_rollAQuarterTurnPerLobe_when_theItemExits() {
        val start = lip(seam = -5f)!!
        val end = lip(seam = height - 1f)!!
        val q0 = SeamCookie.exitProgress(-5f, height, 12f)
        val q1 = SeamCookie.exitProgress(height - 1f, height, 12f)
        assertEquals(.25f * 4 * (q1 - q0), end.phase - start.phase, 1e-4f)
    }

    @Test
    fun should_holdStillAndUnrolled_when_motionIsReduced() {
        val lip = lip(seam = 50f, stretch = 1f, disorder = 1f, lagPx = 6f, travelPx = 300f, reduced = true)!!
        assertEquals(0f, lip.phase, 0f)
        assertEquals(.45f * 12f, lip.rest, 1e-4f)
        // The morph and the envelope stay: they are positional.
        assertEquals(4f, lip.amplitude, 1e-4f)
    }

    @Test
    fun should_flattenTheLip_when_theItemsBottomReachesIt() {
        val lip = lip(seam = height - 5f)!!
        for (front in fronts(lip)) assertEquals(lip.rest, front, 1e-4f)
    }

    @Test
    fun should_dropShoulders_when_theItemIsRound() {
        val lip = lip(seam = 30f)!!
        assertTrue(lip.shoulder > 0f)
        val square = SeamCookie.front(lip, width, height, .5f, round = false)
        val round = SeamCookie.front(lip, width, height, .5f, round = true)
        assertTrue(square > round)
    }

    @Test
    fun should_leaveNoIslands_when_aCircleExitsUnderThePathFallback() {
        // Pre-33 cannot tell a circle from a square: shoulders stay on, but a
        // square box guards with the ellipse, so a flung avatar's last sliver
        // stays one piece (with the square's bottom it splits off slivers).
        for (size in listOf(48f, 110f, 160f, 216f)) {
            val guardRound = SeamCookie.roundable(size, size)
            assertTrue(guardRound)
            var step = 0
            while (true) {
                val seam = -12f + step * .2f
                if (seam > size) break
                step++
                val lip = lip(seam = seam, stretch = 1f, itemWidth = size, itemHeight = size) ?: continue
                var pieces = 0
                var inside = false
                for (column in 0..(size * 10).toInt()) {
                    val x = column / 10f
                    val u = (x - size / 2f) / (size / 2f)
                    val bottom = size / 2f * (1f + sqrt(maxOf(0f, 1f - u * u)))
                    val front = SeamCookie.front(lip, size, size, x, round = false, guardRound = guardRound)
                    val visible = seam + front <= bottom
                    if (visible && !inside) pieces++
                    inside = visible
                }
                assertTrue("$size dp at seam $seam: $pieces pieces", pieces <= 1)
            }
        }
    }

    @Test
    fun should_veilTheTipsTowardThePage_when_nearTheSeam() {
        val lip = lip(seam = 50f)!!
        assertEquals(.5f, SeamCookie.veil(lip, 0f), 1e-5f)
        assertEquals(0f, SeamCookie.veil(lip, .55f * 12f), 0f)
        assertEquals(0f, SeamCookie.alpha(front = 3f, depth = -1f, edge = .6f), 0f)
        assertEquals(1f, SeamCookie.alpha(front = 3f, depth = 4f, edge = .6f), 0f)
        assertTrue(SeamCookie.alpha(front = 3f, depth = 3f, edge = .6f) in .4f..0.6f)
    }
}
