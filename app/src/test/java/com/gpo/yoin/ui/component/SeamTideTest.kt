package com.gpo.yoin.ui.component

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tide line's geometry, shared by Home's status bar and every top seam under chrome. */
class SeamTideTest {
    private val density = Density(2f)

    @Test
    fun should_restBelowSeam_when_tideRevealed() {
        assertEquals(2f, tideBase(restPx = 2f, hiddenPx = 8f, reveal = 1f), 0f)
        assertEquals(-8f, tideBase(restPx = 2f, hiddenPx = 8f, reveal = 0f), 0f)
        assertEquals(-3f, tideBase(restPx = 2f, hiddenPx = 8f, reveal = .5f), 1e-5f)
    }

    @Test
    fun should_followTheLinesReveal_when_textFadesUnderChrome() {
        with(density) {
            val amplitude = SeamDissolveTokens.TideAmplitude.toPx()
            val rest = SeamDissolveTokens.TideRest.toPx() + tideCrestPx(amplitude)
            // Line still above the screen: no cut in open water.
            assertEquals(0f, tideTextSeamPx(reveal = 0f), 0f)
            assertEquals(0f, tideTextSeamPx(reveal = .28f), 0f)
            // Revealed: the line's rest, as before.
            assertEquals(rest, tideTextSeamPx(reveal = 1f), 1e-5f)
            assertTrue(tideTextSeamPx(reveal = .9f) in 0f..rest)
        }
    }

    @Test
    fun should_riseByTheHarmonic_when_measuringTheCrest() {
        assertEquals(10f * (1f + SeamDissolveTokens.TideHarmonic), tideCrestPx(10f), 1e-5f)
    }

    @Test
    fun should_holdBothWavesInTheBand_when_measuringTheDepth() {
        // The chrome seam's layer must reach the back wave's lowest trough.
        val amplitude = with(density) { SeamDissolveTokens.TideAmplitude.toPx() }
        val depth = with(density) { tideDepthPx(amplitude) }
        val backDrop = with(density) { SeamDissolveTokens.TideBackDrop.toPx() }
        assertTrue(depth > backDrop + amplitude * SeamDissolveTokens.TideBackAmplitude)
    }

    @Test
    fun should_stayStillAndLow_when_motionIsReduced() {
        val flow = SeamFlow().apply {
            speedDp = 5000f
            travelPx = 300f
            lagPx = 20f
        }
        with(density) {
            assertEquals(SeamDissolveTokens.TideAmplitude.toPx(), tideAmplitudePx(flow, reduced = true), 1e-5f)
            assertEquals(0f, tidePhase(flow, reduced = true), 0f)
            assertTrue(tideAmplitudePx(flow, reduced = false) > tideAmplitudePx(flow, reduced = true))
            assertTrue(tidePhase(flow, reduced = false) > 0f)
        }
    }
}
