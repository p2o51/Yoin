package com.gpo.yoin.player.sing.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT in double precision, for one power-of-two size.
 * Forward is e^(−2πi·kn/N) unscaled; inverse is e^(+2πi·kn/N) scaled by 1/N (numpy/torch "backward").
 */
class Fft(val size: Int) {
    private val bitReversed = IntArray(size)
    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two: $size" }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) bitReversed[i] = Integer.reverse(i) ushr (32 - bits)
        for (i in 0 until size / 2) {
            cosTable[i] = cos(2.0 * PI * i / size)
            sinTable[i] = sin(2.0 * PI * i / size)
        }
    }

    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, inverse = false)

    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, inverse = true)
        val scale = 1.0 / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until size) {
            val j = bitReversed[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var half = 1
        while (half < size) {
            val tableStep = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sign * sinTable[k]
                    val l = j + half
                    val xr = re[l] * wr - im[l] * wi
                    val xi = re[l] * wi + im[l] * wr
                    re[l] = re[j] - xr
                    im[l] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += tableStep
                }
                start += half * 2
            }
            half *= 2
        }
    }
}
