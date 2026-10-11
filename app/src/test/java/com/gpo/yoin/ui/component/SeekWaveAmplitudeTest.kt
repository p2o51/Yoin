package com.gpo.yoin.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scrubber's wave amplitude is a step on play state alone (owner 10-10):
 * a full wave while playing, flat while paused. This only pins the step's two
 * values. Whether the drawn wave really ignores progress (no flat first 10% or
 * last 5%) is guarded by WaveProgressBarAmplitudeTest, which measures the ink.
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
