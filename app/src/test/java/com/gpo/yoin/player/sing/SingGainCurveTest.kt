package com.gpo.yoin.player.sing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingGainCurveTest {
    @Test
    fun should_spanSilenceToFullMix_when_sliderAtEnds() {
        assertEquals(0f, SingGainCurve.gainFor(0f), 0f)
        assertEquals(1f, SingGainCurve.gainFor(1f), 1e-6f)
        assertEquals(0f, SingGainCurve.gainFor(-1f), 0f)
        assertEquals(1f, SingGainCurve.gainFor(2f), 1e-6f)
    }

    @Test
    fun should_riseMonotonically_when_sliderMoves() {
        var last = -1f
        for (i in 0..1000) {
            val g = SingGainCurve.gainFor(i / 1000f)
            assertTrue(g >= last)
            last = g
        }
    }

    @Test
    fun should_beLogarithmic_when_aboveKnee() {
        // Equal slider steps are equal dB steps: 48 dB over the travel.
        val a = SingGainCurve.gainFor(0.5f)
        val b = SingGainCurve.gainFor(0.75f)
        val c = SingGainCurve.gainFor(1f)
        assertEquals(b / a, c / b, 1e-4f)
        assertEquals(12.0, 20 * kotlin.math.log10((c / b).toDouble()), 1e-3)
    }

    @Test
    fun should_roundTrip_when_inverted() {
        for (i in 0..100) {
            val p = i / 100f
            assertEquals(p, SingGainCurve.positionFor(SingGainCurve.gainFor(p)), 1e-4f)
        }
    }

    @Test
    fun should_default12Percent_when_singStarts() {
        assertEquals(0.12f, SingGainCurve.gainFor(SingGainCurve.DEFAULT_POSITION), 1e-5f)
        assertEquals(12, VocalLevel.DEFAULT.percent)
    }

    @Test
    fun should_snapToDefault_when_near12Percent() {
        val near = SingGainCurve.positionFor(0.105f)
        assertEquals(SingGainCurve.DEFAULT_POSITION, SingGainCurve.snap(near), 0f)
        assertTrue(VocalLevel(SingGainCurve.snap(near)).atDefault)
        val far = SingGainCurve.positionFor(0.3f)
        assertEquals(far, SingGainCurve.snap(far), 0f)
    }
}
