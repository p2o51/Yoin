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

    /** `ceil(inputLength · up / down)`, as scipy sizes the output. */
    fun outputLength(inputLength: Int): Int {
        val n = inputLength.toLong() * up
        return (n / down + if (n % down != 0L) 1 else 0).toInt()
    }

    /** Resamples [inputLength] samples of [input] into [output] (indices from 0). */
    fun process(input: FloatBuffer, inputLength: Int, output: FloatBuffer) {
        process(input, inputLength, output, 0, outputLength(inputLength))
    }

    /** Output samples [fromOutput, toOutput) only, so a long signal can be done in blocks. */
    fun process(input: FloatBuffer, inputLength: Int, output: FloatBuffer, fromOutput: Int, toOutput: Int) {
        if (isIdentity) {
            for (m in fromOutput until toOutput) output.put(m, input.get(m))
            return
        }
        val last = taps.size - 1
        for (m in fromOutput until toOutput) {
            val c = m.toLong() * down + halfLength
            // j with 0 ≤ c − up·j ≤ last and 0 ≤ j < inputLength.
            val jLow = maxOf(0L, ceilDiv(c - last, up.toLong())).toInt()
            val jHigh = minOf((inputLength - 1).toLong(), c / up).toInt()
            var acc = 0.0
            var j = jLow
            var k = (c - up.toLong() * jLow).toInt()
            while (j <= jHigh) {
                acc += taps[k] * input.get(j)
                j++
                k -= up
            }
            output.put(m, acc.toFloat())
        }
    }

    companion object {
        private const val KAISER_BETA = 5.0

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
