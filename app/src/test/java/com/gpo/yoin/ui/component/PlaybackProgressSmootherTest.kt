package com.gpo.yoin.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressSmootherTest {
    private val ms = 1_000_000L
    private val smoother = PlaybackProgressSmoother { 20_000L }

    @Test
    fun should_advanceBetweenTicks_whenPlaying() {
        assertEquals(0.5f, smoother.displayed(0.5f, isPlaying = true, nowNanos = 0L), 1e-6f)
        // 100 ms into a 20 s song, before the next tick: 0.5 + 0.005.
        assertEquals(0.505f, smoother.displayed(0.5f, isPlaying = true, nowNanos = 100 * ms), 1e-5f)
    }

    @Test
    fun should_neverStepBack_whenTickLandsSlightlyBehind() {
        smoother.displayed(0.5f, true, 0L)
        val drawn = smoother.displayed(0.5f, true, 260 * ms)
        // The tick says 250 ms; 260 ms were already drawn.
        val next = smoother.displayed(0.5125f, true, 270 * ms)
        assertTrue(next >= drawn)
    }

    @Test
    fun should_stopAhead_whenTicksStall() {
        smoother.displayed(0.5f, true, 0L)
        // 600 ms of lead at most: 0.5 + 0.03.
        assertEquals(0.53f, smoother.displayed(0.5f, true, 5_000 * ms), 1e-5f)
    }

    @Test
    fun should_snapToTick_whenSeekOrTrackChange() {
        smoother.displayed(0.9f, true, 0L)
        smoother.displayed(0.9f, true, 200 * ms)
        assertEquals(0.01f, smoother.displayed(0.01f, true, 250 * ms), 1e-6f)
    }

    @Test
    fun should_drawTheTick_whenPaused() {
        smoother.displayed(0.5f, true, 0L)
        assertEquals(0.5f, smoother.displayed(0.5f, false, 500 * ms), 1e-6f)
        // Resuming starts from the paused tick, not from the old anchor.
        assertEquals(0.5f, smoother.displayed(0.5f, true, 10_000 * ms), 1e-6f)
    }
}
