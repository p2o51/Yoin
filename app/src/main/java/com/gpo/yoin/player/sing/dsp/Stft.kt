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
 * Signals are read with absolute [FloatBuffer.get] from index 0, so the same code runs on heap arrays
 * (tests) and on memory-mapped work files (a song on the device).
 */
class Stft {
    /** `torch.hann_window(1024, periodic=True)`. */
    val window = DoubleArray(N_FFT) { n -> 0.5 - 0.5 * cos(2.0 * PI * n / N_FFT) }

    private val fft = Fft(N_FFT)
    private val re = DoubleArray(N_FFT)
    private val im = DoubleArray(N_FFT)

    /** Frames torch makes of [length] samples with center=True. */
    fun frameCount(length: Int): Int = length / HOP + 1

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
        require(length > N_FFT / 2) { "reflect padding needs more than ${N_FFT / 2} samples, got $length" }
        val origin = frame * HOP - N_FFT / 2
        for (n in 0 until N_FFT) {
            val index = reflect(origin + n, length)
            val w = window[n]
            re[n] = left.get(index) * w
            im[n] = right.get(index) * w
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
        for (n in from until to) {
            val i = origin + n
            out.put(i, (out.get(i) + windowed[n]).toFloat())
        }
    }

    /** Divides an OLA sum of [frames] frames by the window² envelope, as `torch.istft(length=)` does. */
    fun normalize(out: FloatBuffer, length: Int, frames: Int) {
        for (i in 0 until length) out.put(i, (out.get(i) / envelope(i, frames)).toFloat())
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
}
