package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.SingFixtures.buffer
import com.gpo.yoin.player.sing.SingFixtures.maxAbsError
import com.gpo.yoin.player.sing.SingFixtures.slice
import com.gpo.yoin.player.sing.SingFixtures.toArray
import com.gpo.yoin.player.sing.dsp.PolyphaseResampler
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against scipy.signal.resample_poly on the fixture clip (gen_sing_fixtures.py). */
class PolyphaseResamplerTest {
    private val clip = SingFixtures.load("clip_44k_stereo_planar.f32")
    private val frames = clip.size / 2
    private val left = clip.slice(0, frames)
    private val right = clip.slice(frames, frames)
    private val core = SingFixtures.load("resample_44100_to_22050_planar.f32")
    private val coreLeft = core.slice(0, core.size / 2)

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        val r = PolyphaseResampler(from, to)
        val out = FloatBuffer.allocate(r.outputLength(input.size))
        r.process(input.buffer(), input.size, out)
        return out.toArray()
    }

    private fun check(name: String, actual: FloatArray, expected: FloatArray) {
        assertEquals("$name length", expected.size, actual.size)
        val error = maxAbsError(expected, actual)
        println("resampler $name: max |error| = $error")
        assertTrue("$name max error $error", error < 1e-4)
    }

    @Test
    fun should_matchScipy_when_44100To22050() {
        check("44100→22050 L", resample(left, 44_100, 22_050), coreLeft)
        check("44100→22050 R", resample(right, 44_100, 22_050), core.slice(core.size / 2, core.size / 2))
    }

    @Test
    fun should_matchScipy_when_22050To44100() {
        check("22050→44100", resample(coreLeft, 22_050, 44_100), SingFixtures.load("resample_22050_to_44100_left.f32"))
    }

    @Test
    fun should_matchScipy_when_48000To22050() {
        check("48000→22050", resample(left, 48_000, 22_050), SingFixtures.load("resample_48000_to_22050_left.f32"))
    }

    @Test
    fun should_matchScipy_when_22050To48000() {
        check("22050→48000", resample(coreLeft, 22_050, 48_000), SingFixtures.load("resample_22050_to_48000_left.f32"))
    }

    @Test
    fun should_computeBlocksIdentically_when_processedInRanges() {
        val r = PolyphaseResampler(48_000, 22_050)
        val whole = FloatBuffer.allocate(r.outputLength(left.size))
        r.process(left.buffer(), left.size, whole)
        val blocks = FloatBuffer.allocate(r.outputLength(left.size))
        var from = 0
        while (from < blocks.capacity()) {
            val to = minOf(blocks.capacity(), from + 777)
            r.process(left.buffer(), left.size, blocks, from, to)
            from = to
        }
        assertEquals(0.0, maxAbsError(whole.toArray(), blocks.toArray()), 0.0)
    }

    @Test
    fun should_copy_when_ratesMatch() {
        assertTrue(PolyphaseResampler(44_100, 44_100).isIdentity)
        assertEquals(0.0, maxAbsError(left, resample(left, 44_100, 44_100)), 0.0)
    }

    @Test
    fun should_matchNumpyI0_when_besselComputed() {
        // scipy.special.i0(5.0)
        assertEquals(27.239871823604442, PolyphaseResampler.besselI0(5.0), 1e-12)
    }
}
