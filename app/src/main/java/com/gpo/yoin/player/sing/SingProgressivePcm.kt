package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.dsp.IntervalSet
import com.gpo.yoin.player.sing.dsp.getAt
import com.gpo.yoin.player.sing.dsp.putAt
import java.io.IOException
import java.nio.FloatBuffer

/**
 * A song's decoded PCM (planar stereo float, [sampleRate]), filled in as it decodes — not necessarily
 * from the start: [SingStreamDecoder] decodes from where the listener is first, then fills in the rest,
 * in segments, each written at its exact frame on the song's timeline (frame 0 = the first sample
 * Media3 plays). [left]/[right] are sized to [capacity] up front (memory-mapped, zero until written).
 *
 * Segments meet: where one runs into frames another already wrote, the overlap is compared sample for
 * sample — a lossless decode from a different starting point gives exactly the same samples, so any
 * difference means a segment was placed at the wrong frame ([Misplaced]), and nothing built on it can
 * be trusted.
 *
 * One writer (the decoder thread); readers ([isDecoded], [decodedRanges], [exactFrames]) from any
 * thread. The decoded ranges are published under a lock after the samples are written, so a reader
 * that sees a range decoded sees its samples.
 */
class SingProgressivePcm(
    val left: FloatBuffer,
    val right: FloatBuffer,
    val capacity: Int,
    val sampleRate: Int,
    /** The song's length as the container tells it (frames), null when it doesn't. */
    val estimatedFrames: Int?,
) {
    /** A segment met frames already decoded and they differ: it was placed at the wrong frame. */
    class Misplaced(message: String) : IOException(message)

    /** The song is longer than [capacity]. */
    class Overflow(message: String) : IOException(message)

    private val lock = Any()
    private val decoded = IntervalSet()

    /** The song's true length once a segment reached the end of the stream; −1 before. */
    @Volatile var exactFrames: Int = -1
        private set

    /** Bumped on every write, for waiters. */
    @Volatile var version: Int = 0
        private set

    /** The segment being written: its next frame, and the frame it was started for. */
    private var cursor = -1
    private var target = 0
    private var compare = FloatArray(0)

    /** Starts a segment whose first sample is frame [startFrame], decoding for the frames from [targetFrame]. */
    fun beginSegment(startFrame: Int, targetFrame: Int = startFrame) {
        require(startFrame >= 0) { "segment before the song: $startFrame" }
        cursor = startFrame
        target = maxOf(startFrame, targetFrame)
    }

    val segmentCursor: Int get() = cursor

    /**
     * Writes [count] frames at the segment's cursor: frames not decoded yet are stored, frames already
     * decoded are checked against what's there. True while the segment should go on; false once it
     * has run, past its target, into frames already decoded (it joined a segment decoded before).
     */
    fun write(l: FloatArray, r: FloatArray, count: Int): Boolean {
        check(cursor >= 0) { "write before beginSegment" }
        if (count <= 0) return true
        if (cursor + count > capacity) throw Overflow("song longer than the ${capacity}-frame buffer")
        val from = cursor
        val to = cursor + count
        val gaps = synchronized(lock) { decoded.gaps(from, to) }
        var at = from
        for ((gapFrom, gapTo) in gaps) {
            if (at < gapFrom) verify(l, r, from, at, gapFrom)
            left.putAt(gapFrom, l, gapFrom - from, gapTo - gapFrom)
            right.putAt(gapFrom, r, gapFrom - from, gapTo - gapFrom)
            at = gapTo
        }
        if (at < to) verify(l, r, from, at, to)
        if (gaps.isNotEmpty()) {
            synchronized(lock) { for ((a, b) in gaps) decoded.add(a, b) }
            version++
        }
        cursor = to
        // Joined: past the target, and the next frame is already in.
        return !(cursor > target && synchronized(lock) { decoded.covers(cursor, cursor + 1) })
    }

    /** Frames [at, to) of the segment written at [from] are already decoded: they must be the same. */
    private fun verify(l: FloatArray, r: FloatArray, from: Int, at: Int, to: Int) {
        val count = to - at
        if (compare.size < count) compare = FloatArray(count)
        left.getAt(at, compare, 0, count)
        for (i in 0 until count) {
            if (compare[i] != l[at - from + i]) throw Misplaced("decoded frame ${at + i} differs (L)")
        }
        right.getAt(at, compare, 0, count)
        for (i in 0 until count) {
            if (compare[i] != r[at - from + i]) throw Misplaced("decoded frame ${at + i} differs (R)")
        }
    }

    /**
     * The segment reached the end of the stream: the song ends [padding] frames before its cursor
     * (encoder padding, as Media3 trims it).
     */
    fun endOfStream(padding: Int = 0) {
        check(cursor >= 0) { "end of stream before beginSegment" }
        val end = (cursor - padding).coerceAtLeast(0)
        val known = exactFrames
        if (known >= 0 && known != end) throw Misplaced("song ends at $end, before at $known")
        exactFrames = end
        version++
        cursor = -1
    }

    /** Ends the segment without the end of the stream (joined, or given up for another place). */
    fun endSegment() {
        cursor = -1
    }

    fun isDecoded(from: Int, to: Int): Boolean = synchronized(lock) { decoded.covers(from, to) }

    fun decodedRanges(): List<Pair<Int, Int>> = synchronized(lock) { decoded.ranges() }

    /**
     * Where the next segment should start for a listener needing frames from [want]: the first frame
     * not decoded from there on, else the first one from the start; −1 once the whole song is in
     * (its length known).
     */
    fun nextSegmentStart(want: Int): Int {
        val end = if (exactFrames >= 0) exactFrames else capacity
        synchronized(lock) {
            val w = want.coerceIn(0, end)
            decoded.gaps(w, end).firstOrNull()?.let { return it.first }
            if (exactFrames < 0) return if (w < end) w else -1
            decoded.gaps(0, end).firstOrNull()?.let { return it.first }
            return -1
        }
    }

    /** Whether every frame of the song is decoded (its length known). */
    val isComplete: Boolean get() = exactFrames >= 0 && isDecoded(0, exactFrames)

    companion object {
        /**
         * Before the song's true length is known, a chunk may run only if all it reads ends this many
         * frames before the container's estimate: what a chunk computes depends on the song's length
         * only within ~3 s of the end (the last chunk is pinned to it, the reflect padding, the window
         * envelope), so this keeps every chunk run early exactly what the whole-song run gives, for
         * estimates off by up to 3 % + 12 s.
         */
        fun tailGuardFrames(estimatedFrames: Int, sampleRate: Int): Int =
            (estimatedFrames / 100 * 3 + 15L * sampleRate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        /** The frame time [timeUs] (a codec timestamp) falls on, at [sampleRate]. */
        fun frameAt(timeUs: Long, sampleRate: Int): Long = Math.round(timeUs.toDouble() * sampleRate / 1_000_000.0)
    }
}
