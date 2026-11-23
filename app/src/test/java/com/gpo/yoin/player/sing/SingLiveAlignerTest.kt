package com.gpo.yoin.player.sing

import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingLiveAlignerTest {
    private val rate = 44_100
    private val frames = rate * 4
    private val mix = SingFixtures.aperiodicSynth(frames, rate)
    private val track = SingVocalTrack.inMemory(rate, frames).apply {
        writeReference(FloatBuffer.wrap(mix.first), FloatBuffer.wrap(mix.second))
    }

    private fun capture(from: Int, count: Int) = FloatArray(count) { 0.5f * (mix.first[from + it] + mix.second[from + it]) }

    @Test
    fun should_findExactOffset_when_claimedPositionIsOff() {
        val truth = rate + 1234
        val count = 6615
        for (error in listOf(0, 1, -1, 529, -1105, 2112, -15_000, 20_000)) {
            val result = SingLiveAligner.align(capture(truth, count), count, (truth + error).toLong(), track)
            assertTrue("error $error: $result", result is SingLiveAligner.Result.Found)
            result as SingLiveAligner.Result.Found
            assertEquals("error $error", -error.toLong(), result.offset)
            assertTrue(result.correlation > 0.99)
        }
    }

    @Test
    fun should_findOffset_when_captureNearSongStart() {
        val result = SingLiveAligner.align(capture(100, 4000), 4000, 700, track)
        assertEquals(SingLiveAligner.Result.Found(-600, (result as SingLiveAligner.Result.Found).correlation), result)
    }

    @Test
    fun should_reportQuiet_when_captureSilent() {
        assertEquals(SingLiveAligner.Result.Quiet, SingLiveAligner.align(FloatArray(5000), 5000, rate.toLong(), track))
    }

    @Test
    fun should_reportNoMatch_when_anotherSong_orOutOfReach() {
        var state = 99L
        val other = FloatArray(6000) {
            state = (state * 1103515245L + 12345L) % 2147483648L
            (state / 2147483648.0 - 0.5).toFloat() * 0.3f
        }
        assertEquals(SingLiveAligner.Result.NoMatch, SingLiveAligner.align(other, 6000, rate.toLong(), track))
        // The true place is 2 s away: beyond the ±0.5 s search.
        assertEquals(SingLiveAligner.Result.NoMatch, SingLiveAligner.align(capture(rate, 6000), 6000, 3L * rate, track))
    }
}
