package com.gpo.yoin.player.sing

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads the float32 fixtures scripts/sing/gen_sing_fixtures.py writes (Python reference outputs) and
 * rebuilds its synthetic clip.
 */
object SingFixtures {
    fun load(name: String): FloatArray {
        val bytes = requireNotNull(javaClass.classLoader!!.getResourceAsStream("sing/$name")) { "missing fixture sing/$name" }
            .use { it.readBytes() }
        return floatsOf(bytes)
    }

    fun loadFile(file: File): FloatArray = floatsOf(file.readBytes())

    private fun floatsOf(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    /** gen_sing_fixtures.synth: planar [left, right]. */
    fun synth(frames: Int, rate: Int): Pair<FloatArray, FloatArray> {
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        var state = 12345L
        val twoPi = 2.0 * PI
        for (i in 0 until frames) {
            state = (state * 1103515245L + 12345L) % 2147483648L
            val noise = state / 2147483648.0 - 0.5
            val t = i.toDouble() / rate
            val vib = sin(twoPi * 600.0 * t + 3.0 * sin(twoPi * 5.0 * t))
            left[i] = (
                0.25 * sin(twoPi * 110.0 * t) +
                    0.15 * sin(twoPi * 440.0 * t) * (0.5 + 0.5 * sin(twoPi * 2.0 * t)) +
                    0.2 * vib + 0.04 * noise
                ).toFloat()
            right[i] = (
                0.25 * sin(twoPi * 110.0 * t + 0.7) +
                    0.1 * sin(twoPi * 1375.0 * t) +
                    0.2 * vib - 0.04 * noise
                ).toFloat()
        }
        return left to right
    }

    fun FloatArray.slice(from: Int, count: Int): FloatArray = copyOfRange(from, from + count)

    fun FloatArray.buffer(): FloatBuffer = FloatBuffer.wrap(this)

    fun FloatBuffer.toArray(count: Int = capacity()): FloatArray = FloatArray(count) { get(it) }

    fun maxAbsError(a: FloatArray, b: FloatArray, count: Int = minOf(a.size, b.size)): Double {
        var max = 0.0
        for (i in 0 until count) max = maxOf(max, abs(a[i].toDouble() - b[i]))
        return max
    }

    fun peak(a: FloatArray): Double = a.maxOf { abs(it.toDouble()) }

    /** 10·log10(signal power / error power), over the first [count] samples. */
    fun snrDb(reference: FloatArray, test: FloatArray, count: Int = minOf(reference.size, test.size)): Double {
        var s = 0.0
        var e = 0.0
        for (i in 0 until count) {
            s += reference[i].toDouble() * reference[i]
            val d = reference[i].toDouble() - test[i]
            e += d * d
        }
        return 10 * log10(s / maxOf(e, 1e-30))
    }

    fun rms(a: FloatArray): Double = sqrt(a.sumOf { it.toDouble() * it } / a.size)
}
