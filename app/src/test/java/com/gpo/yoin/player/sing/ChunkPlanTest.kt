package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.SingFixtures.buffer
import com.gpo.yoin.player.sing.SingFixtures.maxAbsError
import com.gpo.yoin.player.sing.SingFixtures.toArray
import com.gpo.yoin.player.sing.dsp.ChunkPlan
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import com.gpo.yoin.player.sing.dsp.SeparationModel
import com.gpo.yoin.player.sing.dsp.Stft
import com.gpo.yoin.player.sing.dsp.VocalSeparator
import java.nio.FloatBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlanTest {
    // Expected starts: the reference's rule run in Python (gen_sing_fixtures.separate).
    @Test
    fun should_matchReferenceStarts_when_planned() {
        assertArrayEquals(intArrayOf(0), ChunkPlan(1).starts)
        assertArrayEquals(intArrayOf(0), ChunkPlan(100).starts)
        assertArrayEquals(intArrayOf(0), ChunkPlan(256).starts)
        assertArrayEquals(intArrayOf(0, 1), ChunkPlan(257).starts)
        assertArrayEquals(intArrayOf(0, 3), ChunkPlan(259).starts)
        assertArrayEquals(intArrayOf(0, 160, 320, 480, 640, 744), ChunkPlan(1000).starts)
        assertArrayEquals(intArrayOf(0, 128, 256, 384, 512, 640, 744), ChunkPlan(1000, 128).starts)
        val song = ChunkPlan(15_504)
        assertEquals(97, song.size)
        assertEquals(15_248, song.starts.last())
    }

    @Test
    fun should_matchNumpyHanning_when_crossfadeBuilt() {
        val w = ChunkPlan.CROSSFADE
        assertEquals(1e-4f, w[0], 0f)
        assertEquals(0.00015177402f, w[1], 1e-9f)
        assertEquals(0.99996203f, w[127], 1e-7f)
        assertEquals(w[127], w[128], 0f)
        assertEquals(1e-4f, w[255], 0f)
    }

    @Test
    fun should_startUnderPlayPosition_when_ordered() {
        val plan = ChunkPlan(1000)
        assertArrayEquals(intArrayOf(3, 4, 5, 2, 1, 0), plan.order(500))
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5), plan.order(0))
        assertArrayEquals(intArrayOf(5, 4, 3, 2, 1, 0), plan.order(999))
        assertEquals(plan.size, plan.order(321).toSet().size)
    }

    @Test
    fun should_coverEveryFrame_when_planned() {
        for (frames in listOf(1, 255, 256, 257, 259, 1000, 4321)) {
            val coverage = ChunkPlan(frames).coverage()
            assertTrue(coverage.all { it >= 1 })
        }
    }

    /** Hands the input back: V is then X without its DC bin — independent of chunking and order. */
    private object Identity : SeparationModel {
        override fun run(input: FloatArray, output: FloatArray) {
            input.copyInto(output)
        }
    }

    private fun separate(left: FloatArray, right: FloatArray, priorityFrame: Int, step: Int = 160): Pair<FloatArray, FloatArray> {
        val vl = FloatBuffer.allocate(left.size)
        val vr = FloatBuffer.allocate(left.size)
        VocalSeparator(step).separateCore(left.buffer(), right.buffer(), left.size, Identity, vl, vr, priorityFrame)
        return vl.toArray() to vr.toArray()
    }

    /** X with each frame's DC bin zeroed, straight through the STFT, no chunks. */
    private fun dcLess(left: FloatArray, right: FloatArray): Pair<FloatArray, FloatArray> {
        val stft = Stft()
        val n = left.size
        val frames = stft.frameCount(n)
        val bins = LyraSingSpec.ONE_SIDED_BINS
        val lRe = DoubleArray(bins)
        val lIm = DoubleArray(bins)
        val rRe = DoubleArray(bins)
        val rIm = DoubleArray(bins)
        val l = DoubleArray(LyraSingSpec.N_FFT)
        val r = DoubleArray(LyraSingSpec.N_FFT)
        val outL = FloatBuffer.allocate(n)
        val outR = FloatBuffer.allocate(n)
        for (t in 0 until frames) {
            stft.forwardPair(left.buffer(), right.buffer(), n, t, lRe, lIm, rRe, rIm)
            lRe[0] = 0.0
            lIm[0] = 0.0
            rRe[0] = 0.0
            rIm[0] = 0.0
            stft.inversePair(lRe, lIm, rRe, rIm, l, r)
            stft.overlapAdd(t, l, outL, n)
            stft.overlapAdd(t, r, outR, n)
        }
        stft.normalize(outL, n, frames)
        stft.normalize(outR, n, frames)
        return outL.toArray() to outR.toArray()
    }

    @Test
    fun should_reconstructInput_when_identityModelCrossfaded() {
        // 10 s at 22.05 kHz: 862 frames, 6 overlapping chunks, RMS normalisation undone exactly.
        val (left, right) = SingFixtures.synth(10 * 22_050, 22_050)
        val (vl, vr) = separate(left, right, priorityFrame = 0)
        val (el, er) = dcLess(left, right)
        assertTrue(maxAbsError(el, vl) < 1e-5)
        assertTrue(maxAbsError(er, vr) < 1e-5)
    }

    @Test
    fun should_giveSameResult_when_startedMidSong() {
        val (left, right) = SingFixtures.synth(10 * 22_050, 22_050)
        val (al, ar) = separate(left, right, priorityFrame = 0)
        val (bl, br) = separate(left, right, priorityFrame = 500)
        assertTrue(maxAbsError(al, bl) < 1e-6)
        assertTrue(maxAbsError(ar, br) < 1e-6)
    }

    @Test
    fun should_runPositionChunkFirst_when_priorityGiven() {
        val (left, right) = SingFixtures.synth(10 * 22_050, 22_050)
        val seen = mutableListOf<Float>()
        val recorder = object : SeparationModel {
            override fun run(input: FloatArray, output: FloatArray) {
                seen += input[0]
                input.copyInto(output)
            }
        }
        val plan = ChunkPlan(Stft().frameCount(left.size))
        val vl = FloatBuffer.allocate(left.size)
        val vr = FloatBuffer.allocate(left.size)
        val chunks = VocalSeparator().separateCore(left.buffer(), right.buffer(), left.size, recorder, vl, vr, priorityFrame = 500)
        assertEquals(plan.size, chunks)
        assertEquals(plan.size, seen.size)
    }

    @Test
    fun should_returnSilence_when_clipShorterThanHalfWindow() {
        val (left, right) = SingFixtures.synth(400, 22_050)
        val (vl, _) = separate(left, right, 0)
        assertTrue(vl.all { it == 0f })
    }

    @Test
    fun should_mapPositionToFrame_when_asked() {
        assertEquals(0, VocalSeparator.frameAt(0))
        // 1 s = 22050 samples = 86.13 frames.
        assertEquals(86, VocalSeparator.frameAt(1_000))
    }
}
