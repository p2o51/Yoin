package com.gpo.yoin.player.sing.dsp

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/*
 * Bulk copies between buffers and arrays at an absolute index, leaving the buffer's own position alone.
 * On ART every single get/put of a direct or memory-mapped buffer goes through a JNI-style trampoline
 * (Memory.peekInt): per-sample loops over the work files cost more than the maths. A bulk get/put is
 * one memcpy. Positions are set through [Buffer] (FloatBuffer.position(int) returning FloatBuffer only
 * exists from API 34).
 */

/** Copies [count] floats from index [index] of this buffer into [dst] from [offset]. */
internal fun FloatBuffer.getAt(index: Int, dst: FloatArray, offset: Int, count: Int) {
    if (count <= 0) return
    val view = duplicate()
    val positioned: Buffer = view
    positioned.position(index)
    view.get(dst, offset, count)
}

/** Copies [count] floats of [src] from [offset] into this buffer from index [index]. */
internal fun FloatBuffer.putAt(index: Int, src: FloatArray, offset: Int, count: Int) {
    if (count <= 0) return
    val view = duplicate()
    val positioned: Buffer = view
    positioned.position(index)
    view.put(src, offset, count)
}

/** Zeroes this buffer over [from, to), in bulk. */
internal fun FloatBuffer.zeroAt(from: Int, to: Int) {
    val zeros = FloatArray(minOf(ZERO_BLOCK, maxOf(0, to - from)))
    var at = from
    while (at < to) {
        val count = minOf(zeros.size, to - at)
        putAt(at, zeros, 0, count)
        at += count
    }
}

private const val ZERO_BLOCK = 8192

/** A view of this buffer's 16-bit values from byte [byteIndex] on, in [order]. */
internal fun ByteBuffer.shortsAt(byteIndex: Int, order: ByteOrder): ShortBuffer {
    val view = duplicate()
    val positioned: Buffer = view
    positioned.position(byteIndex)
    return view.order(order).asShortBuffer()
}
