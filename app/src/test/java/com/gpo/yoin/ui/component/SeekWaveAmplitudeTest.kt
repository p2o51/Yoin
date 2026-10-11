package com.gpo.yoin.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scrubber's wave amplitude is a step on play state alone (owner 10-10):
 * a full wave while playing, flat while paused, never shaped by progress.
 */
class SeekWaveAmplitudeTest {

    @Test
    fun should_keepFullAmplitude_when_playing() {
        assertEquals(SeekWaveAmplitudeScale, seekWaveAmplitude(isPlaying = true), 0f)
    }

    @Test
    fun should_flatten_when_paused() {
        assertEquals(0f, seekWaveAmplitude(isPlaying = false), 0f)
    }
}
