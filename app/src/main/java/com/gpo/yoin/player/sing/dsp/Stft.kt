package com.gpo.yoin.player.sing.dsp

import com.gpo.yoin.player.sing.dsp.LyraSingSpec.HOP
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.N_FFT
import com.gpo.yoin.player.sing.dsp.LyraSingSpec.ONE_SIDED_BINS
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos

/**
 * `torch.stft` / `torch.istft` with Lyra-Sing-Lite's settings: n_fft 1024, hop 256, a periodic Hann
 * window of 1024, center=True with reflect padding (torch's default pad_mode), normalized=False,
 * one-sided. Frames are computed one at a time, two channels per complex FFT (z = l + i·r), so a
 * whole song's spectrogram never has to sit in memory.
 *
 * Signals are indexed from 0 and read and written in bulk (a frame or a window at a time, into reused
 * arrays), so the same code runs on heap arrays (tests) and on memory-mapped work files (a song on the
 * device) without a buffer access per sample. Not thread-safe: one scratch set per instance.
 */
class Stft {
    /** `torch.hann_window(1024, periodic=True)`. */
    val window = DoubleArray(N_FFT) { n -> 0.5 - 0.5 * cos(2.0 * PI * n / N_FFT) }

    private val fft = Fft(N_FFT)
    private val re = DoubleArray(N_FFT)
    private val im = DoubleArray(N_FFT)

    /** Frames torch makes of [length] samples with center=True. */
    fun frameCount(length: Int): Int = length / HOP + 1

    private val frameLeft = FloatArray(N_FFT)
    private val frameRight = FloatArray(N_FFT)
    private val olaScratch = FloatArray(N_FFT)
    private val normalizeScratch = FloatArray(NORMALIZE_BLOCK)

    /**
     * Frame [frame] of two signals of [length] samples (> 512, as reflect padding needs).
     * Writes bins 0..512 of each into the four arrays.
     */
    fun forwardPair(
        left: FloatBuffer,
        right: FloatBuffer,
        length: Int,
        frame: Int,
        leftRe: DoubleArray,
        leftIm: DoubleArray,
        rightRe: DoubleArray,
        rightIm: DoubleArray,
    ) {
        val origin = frame * HOP - N_FFT / 2
        padded(left, length, origin, frameLeft, N_FFT)
        padded(right, length, origin, frameRight, N_FFT)
        forwardPair(frameLeft, frameRight, 0, leftRe, leftIm, rightRe, rightIm)
    }

    /**
     * The frame whose [N_FFT] samples start at index [at] of [left]/[right], already reflect padded
     * (see [padded]). Writes bins 0..512 of each into the four arrays.
     */
    fun forwardPair(
        left: FloatArray,
        right: FloatArray,
        at: Int,
        leftRe: DoubleArray,
        leftIm: DoubleArray,
        rightRe: DoubleArray,
        rightIm: DoubleArray,
    ) {
        for (n in 0 until N_FFT) {
            val w = window[n]
            re[n] = left[at + n] * w
            im[n] = right[at + n] * w
        }
        fft.forward(re, im)
        for (k in 0 until ONE_SIDED_BINS) {
            val mirror = if (k == 0) 0 else N_FFT - k
            val a = re[k]
            val b = im[k]
            val c = re[mirror]
            val d = im[mirror]
            leftRe[k] = 0.5 * (a + c)
            leftIm[k] = 0.5 * (b - d)
            rightRe[k] = 0.5 * (b + d)
            rightIm[k] = 0.5 * (c - a)
        }
    }

    /**
     * Samples [from, from + [count]) of a [length]-sample signal (> 512) as torch's center=True
     * reflect padding sees it — indices before 0 and from [length] on mirrored in — into [into] from 0.
     * The part inside the signal is one bulk read.
     */
    fun padded(signal: FloatBuffer, length: Int, from: Int, into: FloatArray, count: Int) {
        require(length > N_FFT / 2) { "reflect padding needs more than ${N_FFT / 2} samples, got $length" }
        val inFrom = maxOf(0, from)
        val inTo = minOf(length, from + count)
        signal.getAt(inFrom, into, inFrom - from, inTo - inFrom)
        for (i in from until minOf(0, from + count)) into[i - from] = signal.get(reflect(i, length))
        for (i in maxOf(length, from) until from + count) into[i - from] = signal.get(reflect(i, length))
    }

    /**
     * The inverse of one frame of two channels, windowed (irfft · window), as `torch.istft` overlaps
     * it. The imaginary parts of DC and Nyquist are ignored, as an inverse real FFT does.
     */
    fun inversePair(
        leftRe: DoubleArray,
        leftIm: DoubleArray,
        rightRe: DoubleArray,
        rightIm: DoubleArray,
        outLeft: DoubleArray,
        outRight: DoubleArray,
    ) {
        val nyquist = N_FFT / 2
        for (k in 0..nyquist) {
            val lIm = if (k == 0 || k == nyquist) 0.0 else leftIm[k]
            val rIm = if (k == 0 || k == nyquist) 0.0 else rightIm[k]
            re[k] = leftRe[k] - rIm
            im[k] = lIm + rightRe[k]
            if (k in 1 until nyquist) {
                re[N_FFT - k] = leftRe[k] + rIm
                im[N_FFT - k] = -lIm + rightRe[k]
            }
        }
        fft.inverse(re, im)
        for (n in 0 until N_FFT) {
            outLeft[n] = re[n] * window[n]
            outRight[n] = im[n] * window[n]
        }
    }

    /** Adds a windowed frame from [inversePair] into [out] (unnormalised OLA, see [normalize]). */
    fun overlapAdd(frame: Int, windowed: DoubleArray, out: FloatBuffer, length: Int) {
        val origin = frame * HOP - N_FFT / 2
        val from = maxOf(0, -origin)
        val to = minOf(N_FFT, length - origin)
        if (from >= to) return
        val sum = olaScratch
        out.getAt(origin + from, sum, from, to - from)
        for (n in from until to) sum[n] = (sum[n] + windowed[n]).toFloat()
        out.putAt(origin + from, sum, from, to - from)
    }

    /** Divides an OLA sum of [frames] frames by the window² envelope, as `torch.istft(length=)` does. */
    fun normalize(out: FloatBuffer, length: Int, frames: Int) {
        val block = normalizeScratch
        var at = 0
        while (at < length) {
            val count = minOf(NORMALIZE_BLOCK, length - at)
            out.getAt(at, block, 0, count)
            for (n in 0 until count) block[n] = (block[n] / envelope(at + n, frames)).toFloat()
            out.putAt(at, block, 0, count)
            at += count
        }
    }

    /** Σ window² over the frames that cover output sample [index]. */
    fun envelope(index: Int, frames: Int): Double {
        val p = index + N_FFT / 2
        val last = minOf(frames - 1, p / HOP)
        var t = maxOf(0, (p - N_FFT + HOP) / HOP)
        var sum = 0.0
        while (t <= last) {
            val n = p - t * HOP
            if (n in 0 until N_FFT) sum += window[n] * window[n]
            t++
        }
        return sum
    }

    private fun reflect(index: Int, length: Int): Int = when {
        index < 0 -> -index
        index >= length -> 2 * (length - 1) - index
        else -> index
    }

    private companion object {
        const val NORMALIZE_BLOCK = 4096
    }
}
