package com.gpo.yoin.player.sing.dsp

import com.gpo.yoin.player.sing.dsp.LyraSingSpec.CHUNK_FLOATS
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.CHUNK_FRAMES
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.CORE_RATE
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.HOP
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.MODEL_BINS
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.N_FFT
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.ONE_SIDED_BINS
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.RMS_EPSILON
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Lyra-Sing-Lite's pipeline from a stereo mix X to its vocal V and instrumental I = X − V, as Prism's
 * `infer_and_eval.py` runs it:
 *
 * 1. X (source rate) → 22.05 kHz ([PolyphaseResampler]).
 * 2. STFT ([Stft]); bins 1..512 of 256-frame chunks ([ChunkPlan]) → `[1, 4, 512, 256]`
 *    [L_re, L_im, R_re, R_im], divided by the chunk's RMS, through the model, multiplied back.
 * 3. Chunks cross-faded (weighted OLA), DC restored as 0, iSTFT → V at 22.05 kHz.
 * 4. V → source rate; I = X − V from the same decoded samples.
 *
 * Every buffer is indexed from 0 with absolute gets and puts; on the device they are memory-mapped
 * work files, so a long song costs no heap. A frame's spectrum is added up only until its last chunk
 * is in, then inverted at once, so chunks can run in any order — the one under the play position
 * first ([ChunkPlan.order]).
 */
class VocalSeparator(private val step: Int = LyraSingSpec.DEFAULT_STEP_FRAMES) {

    fun interface Progress {
        /** [done] of [total] chunks are through the model. Throw to cancel (e.g. CancellationException). */
        fun onChunk(done: Int, total: Int)
    }

    /**
     * Steps 2–3: [left]/[right] hold [length] samples at 22.05 kHz; writes V into [vocalLeft]/[vocalRight]
     * (zeroed here). [priorityFrame] is the STFT frame of the play position. Returns the chunk count.
     */
    fun separateCore(
        left: FloatBuffer,
        right: FloatBuffer,
        length: Int,
        model: SeparationModel,
        vocalLeft: FloatBuffer,
        vocalRight: FloatBuffer,
        priorityFrame: Int = 0,
        progress: Progress = Progress { _, _ -> },
    ): Int {
        for (i in 0 until length) {
            vocalLeft.put(i, 0f)
            vocalRight.put(i, 0f)
        }
        // Reflect padding needs more than half a window; a clip that short has nothing to sing.
        if (length <= N_FFT / 2) return 0

        val runner = ChunkRunner()
        val stft = runner.stft
        val totalFrames = stft.frameCount(length)
        val plan = ChunkPlan(totalFrames, step)
        val remaining = plan.coverage()
        val pending = HashMap<Int, FrameSum>()

        val order = plan.order(priorityFrame)
        for ((done, chunk) in order.withIndex()) {
            val start = plan.starts[chunk]
            val valid = plan.validFrames(chunk)
            val scale = runner.run(left, right, length, start, valid, model)
            for (t in 0 until valid) {
                val frame = start + t
                val sum = pending.getOrPut(frame) { FrameSum() }
                runner.accumulate(sum, t, scale, ChunkPlan.CROSSFADE[t])
                if (--remaining[frame] == 0) {
                    pending.remove(frame)
                    runner.invert(sum)
                    stft.overlapAdd(frame, runner.outL, vocalLeft, length)
                    stft.overlapAdd(frame, runner.outR, vocalRight, length)
                }
            }
            progress.onChunk(done + 1, plan.size)
        }
        check(pending.isEmpty()) { "frames left without all their chunks: ${pending.keys}" }
        stft.normalize(vocalLeft, length, totalFrames)
        stft.normalize(vocalRight, length, totalFrames)
        return plan.size
    }

    companion object {
        /** The STFT frame (at 22.05 kHz, hop 256) nearest a play position. */
        fun frameAt(positionMs: Long): Int = ((positionMs.coerceAtLeast(0L) * CORE_RATE / 1000L + HOP / 2) / HOP).toInt()
    }
}

/** A frame's spectrum summed over the chunks that cover it (weighted), until its last chunk is in. */
internal class FrameSum {
    val values = FloatArray(4 * MODEL_BINS)
    var weight = 0f

    fun invert(
        stft: Stft,
        lRe: DoubleArray,
        lIm: DoubleArray,
        rRe: DoubleArray,
        rIm: DoubleArray,
        outL: DoubleArray,
        outR: DoubleArray,
    ) {
        val w = maxOf(weight, 1e-8f)
        lRe[0] = 0.0
        lIm[0] = 0.0
        rRe[0] = 0.0
        rIm[0] = 0.0
        for (b in 0 until MODEL_BINS) {
            lRe[b + 1] = (values[b] / w).toDouble()
            lIm[b + 1] = (values[MODEL_BINS + b] / w).toDouble()
            rRe[b + 1] = (values[2 * MODEL_BINS + b] / w).toDouble()
            rIm[b + 1] = (values[3 * MODEL_BINS + b] / w).toDouble()
        }
        stft.inversePair(lRe, lIm, rRe, rIm, outL, outR)
    }
}

/**
 * One model chunk, as the whole-song [VocalSeparator] and the live [LiveVocalSeparator] both run it:
 * the chunk's frames' spectra (bins 1..512) into `[1, 4, 512, 256]`, divided by the chunk's RMS,
 * through the model; [accumulate] adds the output, scaled back, into a frame's [FrameSum]; [invert]
 * turns a complete frame into its windowed time signal in [outL]/[outR].
 */
internal class ChunkRunner(val stft: Stft = Stft()) {
    private val input = FloatArray(CHUNK_FLOATS)
    private val output = FloatArray(CHUNK_FLOATS)
    private val lRe = DoubleArray(ONE_SIDED_BINS)
    private val lIm = DoubleArray(ONE_SIDED_BINS)
    private val rRe = DoubleArray(ONE_SIDED_BINS)
    private val rIm = DoubleArray(ONE_SIDED_BINS)
    val outL = DoubleArray(N_FFT)
    val outR = DoubleArray(N_FFT)
    private val plane = MODEL_BINS * CHUNK_FRAMES

    /** Frames [start, start + valid) of a [length]-sample core signal through [model]; returns the scale. */
    fun run(left: FloatBuffer, right: FloatBuffer, length: Int, start: Int, valid: Int, model: SeparationModel): Float {
        input.fill(0f)
        for (t in 0 until valid) {
            stft.forwardPair(left, right, length, start + t, lRe, lIm, rRe, rIm)
            for (b in 0 until MODEL_BINS) {
                val at = b * CHUNK_FRAMES + t
                input[at] = lRe[b + 1].toFloat()
                input[plane + at] = lIm[b + 1].toFloat()
                input[2 * plane + at] = rRe[b + 1].toFloat()
                input[3 * plane + at] = rIm[b + 1].toFloat()
            }
        }
        val rms = rms(input)
        val inverse = (1.0 / rms).toFloat()
        for (i in input.indices) input[i] *= inverse
        model.run(input, output)
        return rms.toFloat()
    }

    /** Adds chunk frame [t] of the last [run], times [scale] and the cross-fade [weight], into [sum]. */
    fun accumulate(sum: FrameSum, t: Int, scale: Float, weight: Float) {
        sum.weight += weight
        for (c in 0 until 4) {
            val base = c * plane
            val dest = c * MODEL_BINS
            for (b in 0 until MODEL_BINS) {
                sum.values[dest + b] += output[base + b * CHUNK_FRAMES + t] * scale * weight
            }
        }
    }

    fun invert(sum: FrameSum) = sum.invert(stft, lRe, lIm, rRe, rIm, outL, outR)

    /** The chunk's RMS over all four planes, zero padding included: sqrt(mean(x²) + 1e-6). */
    private fun rms(x: FloatArray): Double {
        var sum = 0.0
        for (v in x) sum += v.toDouble() * v
        return sqrt(sum / x.size + RMS_EPSILON)
    }
}

/**
 * The whole song: [mix] is planar stereo at [sampleRate] ([frames] per channel, left then right as two
 * buffers); writes V and I (each planar stereo, [frames] long) through [work], which hands out scratch
 * buffers (heap in tests, mapped files on the device).
 */
class FullBandSeparator(
    private val separator: VocalSeparator = VocalSeparator(),
    private val work: (floats: Int) -> FloatBuffer = { FloatBuffer.allocate(it) },
) {
    class Result(val vocalLeft: FloatBuffer, val vocalRight: FloatBuffer, val chunks: Int)

    fun separate(
        mixLeft: FloatBuffer,
        mixRight: FloatBuffer,
        frames: Int,
        sampleRate: Int,
        model: SeparationModel,
        priorityPositionMs: Long = 0,
        progress: VocalSeparator.Progress = VocalSeparator.Progress { _, _ -> },
        afterModel: () -> Unit = {},
    ): Result {
        val down = PolyphaseResampler(sampleRate, CORE_RATE)
        val coreLength = down.outputLength(frames)
        val coreL = work(coreLength)
        val coreR = work(coreLength)
        down.process(mixLeft, frames, coreL)
        down.process(mixRight, frames, coreR)

        val vocalCoreL = work(coreLength)
        val vocalCoreR = work(coreLength)
        val chunks = separator.separateCore(
            coreL, coreR, coreLength, model, vocalCoreL, vocalCoreR,
            VocalSeparator.frameAt(priorityPositionMs), progress,
        )
        // Race to idle: the model is done with this song; let the caller drop the session now.
        afterModel()

        val up = PolyphaseResampler(CORE_RATE, sampleRate)
        val upLength = up.outputLength(coreLength)
        val vocalL = work(frames)
        val vocalR = work(frames)
        // V past the end of the resampled vocal (rounding) is silence, so I = X there.
        val n = minOf(frames, upLength)
        up.process(vocalCoreL, coreLength, vocalL, 0, n)
        up.process(vocalCoreR, coreLength, vocalR, 0, n)
        for (i in n until frames) {
            vocalL.put(i, 0f)
            vocalR.put(i, 0f)
        }
        return Result(vocalL, vocalR, chunks)
    }
}
