package com.gpo.yoin.player.sing.dsp

import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `scipy.signal.resample_poly(x, up, down)` with its defaults — a linear-phase (zero-delay) polyphase
 * FIR, `firwin(2·10·max(up, down) + 1, 1/max(up, down), window=('kaiser', 5.0))` scaled by `up`, zero
 * padding at both ends — computed in double as scipy does. This is the resampler Prism's
 * `infer_and_eval.py` and the training data used, so the model sees the same 22.05 kHz signal here.
 *
 * Output sample m is Σ h[c − up·j]·x[j] with c = m·down + halfLength, which is what scipy's upfirdn
 * plus its pre-pad / trim works out to.
 */
class PolyphaseResampler(fromRate: Int, toRate: Int) {
    val up: Int
    val down: Int
    private val halfLength: Int
    private val taps: DoubleArray

    init {
        require(fromRate > 0 && toRate > 0)
        val g = gcd(fromRate, toRate)
        up = toRate / g
        down = fromRate / g
        val maxRate = maxOf(up, down)
        halfLength = 10 * maxRate
        taps = if (up == 1 && down == 1) DoubleArray(0) else lowPass(2 * halfLength + 1, 1.0 / maxRate, up)
    }

    val isIdentity: Boolean get() = up == 1 && down == 1

    /**
     * How far the filter reaches, in upsampled units: output m reads input j for
     * ceil((m·down − reach)/up) ≤ j ≤ floor((m·down + reach)/up). 0 for the identity.
     */
    val reach: Int get() = if (isIdentity) 0 else halfLength

    /** `ceil(inputLength · up / down)`, as scipy sizes the output. */
    fun outputLength(inputLength: Int): Int {
        val n = inputLength.toLong() * up
        return (n / down + if (n % down != 0L) 1 else 0).toInt()
    }

    /**
     * The taps of each phase p (h[p], h[p + up], h[p + 2·up], …), reversed: output m (phase c mod up)
     * reads them forwards while j runs forwards, the order the sum was always taken in.
     */
    private val phases: Array<DoubleArray> = Array(if (isIdentity) 0 else up) { p ->
        val count = (taps.size - 1 - p) / up + 1
        DoubleArray(count) { i -> taps[p + up * (count - 1 - i)] }
    }

    /** The input samples a block of outputs reads, and the block's results (reused: one caller at a time). */
    private var window = FloatArray(0)
    private val block = FloatArray(BLOCK)

    /** Resamples [inputLength] samples of [input] into [output] (indices from 0). */
    fun process(input: FloatBuffer, inputLength: Int, output: FloatBuffer) {
        process(input, inputLength, output, 0, outputLength(inputLength))
    }

    /**
     * Output samples [fromOutput, toOutput) only, so a long signal can be done in blocks; sample m goes
     * to [output] index m − [outputOffset]. Reads and writes the buffers in bulk, a block at a time.
     * Not thread-safe (one scratch window per resampler).
     */
    fun process(
        input: FloatBuffer,
        inputLength: Int,
        output: FloatBuffer,
        fromOutput: Int,
        toOutput: Int,
        outputOffset: Int = 0,
    ) {
        var m = fromOutput
        while (m < toOutput) {
            val end = minOf(toOutput, m + BLOCK)
            process(input, inputLength, block, m, end, outputOffset = m)
            output.putAt(m - outputOffset, block, 0, end - m)
            m = end
        }
    }

    /** [process] into an array: sample m goes to [output] index m − [outputOffset]. */
    fun process(
        input: FloatBuffer,
        inputLength: Int,
        output: FloatArray,
        fromOutput: Int,
        toOutput: Int,
        outputOffset: Int = 0,
    ) {
        if (fromOutput >= toOutput) return
        if (isIdentity) {
            input.getAt(fromOutput, output, fromOutput - outputOffset, toOutput - fromOutput)
            return
        }
        var m = fromOutput
        while (m < toOutput) {
            val end = minOf(toOutput, m + BLOCK)
            // Every input this block reads, at once: j from jLow(m) to jHigh(end − 1).
            val first = jLow(centre(m))
            val count = jHigh(centre(end - 1), inputLength) - first + 1
            if (window.size < count) window = FloatArray(count)
            input.getAt(first, window, 0, count)
            for (n in m until end) output[n - outputOffset] = sample(n, inputLength, first)
            m = end
        }
    }

    /** Output [m], its inputs read from [window] (input index j at j − [first]). */
    private fun sample(m: Int, inputLength: Int, first: Int): Float {
        val c = centre(m)
        // j with 0 ≤ c − up·j ≤ last and 0 ≤ j < inputLength.
        val jLow = jLow(c)
        val jHigh = jHigh(c, inputLength)
        if (jLow > jHigh) return 0f
        val phase = phases[(c % up).toInt()]
        // Tap c − up·j is phase entry (c / up − j) from its start, so reversed: size − 1 − (c / up − jLow).
        var k = phase.size - 1 - (c / up - jLow).toInt()
        val x = window
        var acc = 0.0
        for (i in jLow - first..jHigh - first) {
            acc += phase[k] * x[i]
            k++
        }
        return acc.toFloat()
    }

    private fun centre(m: Int): Long = m.toLong() * down + halfLength

    private fun jLow(c: Long): Int = maxOf(0L, ceilDiv(c - (taps.size - 1), up.toLong())).toInt()

    private fun jHigh(c: Long, inputLength: Int): Int = minOf((inputLength - 1).toLong(), c / up).toInt()

    companion object {
        private const val KAISER_BETA = 5.0

        /** Outputs computed per bulk read of the input. */
        private const val BLOCK = 4096

        /** `firwin(numTaps, cutoff, window=('kaiser', 5.0))` (cutoff relative to Nyquist), times [gain]. */
        internal fun lowPass(numTaps: Int, cutoff: Double, gain: Int): DoubleArray {
            val alpha = 0.5 * (numTaps - 1)
            val i0Beta = besselI0(KAISER_BETA)
            val h = DoubleArray(numTaps) { n ->
                val m = n - alpha
                val ratio = (n - alpha) / alpha
                val window = besselI0(KAISER_BETA * sqrt(maxOf(0.0, 1.0 - ratio * ratio))) / i0Beta
                cutoff * sinc(cutoff * m) * window
            }
            val sum = h.sum()
            for (n in h.indices) h[n] = h[n] / sum * gain
            return h
        }

        private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        /** Modified Bessel function of the first kind, order 0 (power series; converges fast for β = 5). */
        internal fun besselI0(x: Double): Double {
            val q = x * x / 4.0
            var term = 1.0
            var sum = 1.0
            var k = 1
            while (term > sum * 1e-17) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }

        private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
