package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.SingFixtures.buffer
import com.gpo.yoin.player.sing.SingFixtures.maxAbsError
import com.gpo.yoin.player.sing.SingFixtures.slice
import com.gpo.yoin.player.sing.SingFixtures.toArray
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import com.gpo.yoin.player.sing.dsp.Stft
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against torch.stft / torch.istft (float64, center=True, reflect) on the fixture clip at 22.05 kHz. */
class StftTest {
    private val core = SingFixtures.load("resample_44100_to_22050_planar.f32")
    private val length = core.size / 2
    private val left = core.slice(0, length)
    private val stft = Stft()
    private val bins = LyraSingSpec.ONE_SIDED_BINS

    /** torch's spectrum: [frame][bin][re, im]. */
    private val torchSpec = SingFixtures.load("stft_left_re_im.f32")
    private val frames = torchSpec.size / (bins * 2)

    @Test
    fun should_countFramesAsTorch_when_centered() {
        assertEquals(frames, stft.frameCount(length))
        assertEquals(259, stft.frameCount(3 * 22_050))
    }

    @Test
    fun should_matchTorchStft_when_reflectPadded() {
        val zeros = FloatBuffer.allocate(length)
        val lRe = DoubleArray(bins)
        val lIm = DoubleArray(bins)
        val rRe = DoubleArray(bins)
        val rIm = DoubleArray(bins)
        var maxError = 0.0
        var maxMagnitude = 0.0
        for (t in 0 until frames) {
            stft.forwardPair(left.buffer(), zeros, length, t, lRe, lIm, rRe, rIm)
            for (k in 0 until bins) {
                val at = (t * bins + k) * 2
                maxError = maxOf(maxError, abs(lRe[k] - torchSpec[at]), abs(lIm[k] - torchSpec[at + 1]))
                maxMagnitude = maxOf(maxMagnitude, abs(torchSpec[at].toDouble()))
                // The zero channel packed in the same FFT stays zero.
                assertEquals(0.0, rRe[k], 1e-9)
                assertEquals(0.0, rIm[k], 1e-9)
            }
        }
        println("stft: max |error| = $maxError (largest bin ${"%.1f".format(maxMagnitude)}; fixture stored as float32)")
        assertTrue("stft max error $maxError", maxError < 1e-4)
    }

    @Test
    fun should_matchTorchIstft_when_maskedSpectrumInverted() {
        val expected = SingFixtures.load("istft_masked_left.f32")
        val out = FloatBuffer.allocate(length)
        val zeros = DoubleArray(bins)
        val lRe = DoubleArray(bins)
        val lIm = DoubleArray(bins)
        val outL = DoubleArray(LyraSingSpec.N_FFT)
        val outR = DoubleArray(LyraSingSpec.N_FFT)
        for (t in 0 until frames) {
            for (k in 0 until bins) {
                val mask = 0.5 + 0.4 * sin(0.01 * k + 0.1 * t)
                val at = (t * bins + k) * 2
                lRe[k] = if (k == 0) 0.0 else torchSpec[at] * mask
                lIm[k] = if (k == 0) 0.0 else torchSpec[at + 1] * mask
            }
            stft.inversePair(lRe, lIm, zeros, zeros, outL, outR)
            stft.overlapAdd(t, outL, out, length)
        }
        stft.normalize(out, length, frames)
        val error = maxAbsError(expected, out.toArray())
        println("istft: max |error| = $error")
        assertTrue("istft max error $error", error < 1e-4)
    }

    @Test
    fun should_reconstructSignal_when_stftThenIstft() {
        val right = core.slice(length, length)
        val outLeft = FloatBuffer.allocate(length)
        val outRight = FloatBuffer.allocate(length)
        val lRe = DoubleArray(bins)
        val lIm = DoubleArray(bins)
        val rRe = DoubleArray(bins)
        val rIm = DoubleArray(bins)
        val l = DoubleArray(LyraSingSpec.N_FFT)
        val r = DoubleArray(LyraSingSpec.N_FFT)
        for (t in 0 until frames) {
            stft.forwardPair(left.buffer(), right.buffer(), length, t, lRe, lIm, rRe, rIm)
            stft.inversePair(lRe, lIm, rRe, rIm, l, r)
            stft.overlapAdd(t, l, outLeft, length)
            stft.overlapAdd(t, r, outRight, length)
        }
        stft.normalize(outLeft, length, frames)
        stft.normalize(outRight, length, frames)
        assertTrue(maxAbsError(left, outLeft.toArray()) < 1e-6)
        assertTrue(maxAbsError(right, outRight.toArray()) < 1e-6)
    }

    @Test
    fun should_beOnePointFive_when_envelopeInterior() {
        // Hann² at hop N/4 sums to 1.5.
        assertEquals(1.5, stft.envelope(5_000, frames), 1e-12)
    }
}
