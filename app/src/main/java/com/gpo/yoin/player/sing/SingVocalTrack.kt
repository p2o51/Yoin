package com.gpo.yoin.player.sing

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.roundToInt

/**
 * One song's vocal stem V for live Sing, filled chunk by chunk while the song plays
 * ([OnDeviceSingStreamProvider]) and read by [SingMixerAudioProcessor] on the audio thread, which
 * subtracts it from the playing original: out = X − (1 − g)·V.
 *
 * Per frame, at the song's own sample rate and on the decoded timeline (frame 0 = the first sample
 * after the encoder delay, as Media3 plays it), three 16-bit little-endian samples: V_L, V_R and a
 * mono reference of the mix X ((L + R) / 2), which [SingLiveAligner] matches the playing audio
 * against. 16 bits put the quantisation of V near −96 dB — far below anything the subtraction leaves.
 *
 * Memory: the samples live in a memory-mapped file (the [file] in the Sing cache), not on the heap:
 * ~6 bytes a frame, ~64 MB for a 4-minute song at 44.1 kHz, paged by the system. Nothing ever unmaps
 * it explicitly (the GC does, once no binding holds the track), so a reader can never touch an
 * unmapped page — deleting or renaming the file under a live mapping is fine.
 *
 * Coverage: [BLOCK_FRAMES]-frame blocks, each ready once all its frames are written. The ready
 * flags are an [AtomicIntegerArray]: the writer stores the samples, then sets the flag (release);
 * the reader checks the flag (acquire), then reads — no locks and no allocation on the audio thread.
 * One writer at a time (the separation worker).
 */
class SingVocalTrack private constructor(
    val sampleRate: Int,
    val frames: Int,
    private val data: ByteBuffer,
    complete: Boolean,
    /** The backing file while it is being written, then its place in the cache; null in memory. */
    @Volatile var file: File?,
) {
    private val blocks = (frames + BLOCK_FRAMES - 1) / BLOCK_FRAMES
    private val ready = AtomicIntegerArray(blocks)

    /** Frames written per block (writer side only). */
    private val written = IntArray(blocks)

    @Volatile private var readyBlocks = 0

    init {
        if (complete) {
            for (b in 0 until blocks) {
                ready.set(b, 1)
                written[b] = blockLength(b)
            }
            readyBlocks = blocks
        }
    }

    val isComplete: Boolean get() = readyBlocks == blocks

    /** Share of the song whose vocal is in, 0..1. */
    val coverage: Float get() = if (blocks == 0) 1f else readyBlocks.toFloat() / blocks

    /** Whether V at [frame] is in. Frames outside the song have no vocal and count as ready. */
    fun isReady(frame: Long): Boolean {
        if (frame < 0 || frame >= frames) return true
        return ready.get((frame / BLOCK_FRAMES).toInt()) == 1
    }

    /**
     * The end of the run of ready frames starting at [from], looking no further than [limit]: [from]
     * itself when V at [from] isn't in, [limit] when everything up to it is, [Long.MAX_VALUE] when
     * the run reaches the end of the song. Allocation-free; for the audio thread.
     */
    fun readyEnd(from: Long, limit: Long): Long {
        // Before the song there is no vocal: those frames count as ready.
        var at = maxOf(from, 0L)
        while (at < limit) {
            if (at >= frames) return Long.MAX_VALUE
            val block = (at / BLOCK_FRAMES).toInt()
            if (ready.get(block) != 1) return maxOf(at, from)
            at = (block + 1).toLong() * BLOCK_FRAMES
        }
        return if (at >= frames) Long.MAX_VALUE else limit
    }

    /** Whether every frame in [from, to) has its vocal in (clamped to the song). */
    fun isCovered(from: Long, to: Long): Boolean {
        val start = from.coerceIn(0L, frames.toLong())
        val end = to.coerceIn(0L, frames.toLong())
        if (start >= end) return true
        for (b in (start / BLOCK_FRAMES).toInt()..((end - 1) / BLOCK_FRAMES).toInt()) {
            if (ready.get(b) != 1) return false
        }
        return true
    }

    fun vocalLeft(frame: Int): Float = data.getShort(offset(frame)) * FROM_16

    fun vocalRight(frame: Int): Float = data.getShort(offset(frame) + 2) * FROM_16

    /** (V_L + V_R) / 2, for a mono stream. */
    fun vocalMono(frame: Int): Float {
        val at = offset(frame)
        return (data.getShort(at) + data.getShort(at + 2)) * (FROM_16 * 0.5f)
    }

    /** The mono reference of the mix at [frame]. */
    fun reference(frame: Int): Float = data.getShort(offset(frame) + 4) * FROM_16

    /**
     * Writes the mix reference for the whole song (before any vocal, and before the track is handed
     * to a reader).
     */
    fun writeReference(left: FloatBuffer, right: FloatBuffer) {
        for (i in 0 until frames) data.putShort(offset(i) + 4, to16(0.5f * (left.get(i) + right.get(i))))
    }

    /**
     * Writes the final vocal for frames [from, to), read from [left]/[right] at index (frame −
     * [sourceOffset]), and marks every block it completes as ready. Each frame is written once.
     */
    fun writeVocal(from: Int, to: Int, left: FloatBuffer, right: FloatBuffer, sourceOffset: Int = 0) {
        require(from in 0..to && to <= frames) { "vocal range [$from, $to) outside 0..$frames" }
        var at = from
        while (at < to) {
            val block = at / BLOCK_FRAMES
            val end = minOf(to, (block + 1) * BLOCK_FRAMES)
            for (i in at until end) {
                val o = offset(i)
                data.putShort(o, to16(left.get(i - sourceOffset)))
                data.putShort(o + 2, to16(right.get(i - sourceOffset)))
            }
            written[block] += end - at
            if (written[block] >= blockLength(block) && ready.get(block) == 0) {
                ready.set(block, 1)
                readyBlocks++
            }
            at = end
        }
    }

    /** Stamps the file complete and flushes it, so the cache can keep it ([open] reads it back). */
    fun finish() {
        check(isComplete) { "vocal track not complete" }
        data.putInt(OFFSET_COMPLETE, 1)
        (data as? MappedByteBuffer)?.force()
    }

    private fun blockLength(block: Int): Int = minOf(BLOCK_FRAMES, frames - block * BLOCK_FRAMES)

    private fun offset(frame: Int): Int = HEADER_BYTES + frame * BYTES_PER_FRAME

    companion object {
        const val BLOCK_FRAMES = 4096
        const val BYTES_PER_FRAME = 6
        const val HEADER_BYTES = 32

        /** The cache's file suffix for finished tracks. */
        const val SUFFIX = ".vtrack"

        private const val MAGIC = 0x54565359 // "YSVT", little-endian
        private const val VERSION = 1
        private const val OFFSET_COMPLETE = 20
        private const val FROM_16 = 1f / 32767f

        /** The longest song a track can hold (one mapping: 2 GB). */
        const val MAX_FRAMES = (Int.MAX_VALUE - HEADER_BYTES) / BYTES_PER_FRAME

        /** A new, empty track backed by [file] (created or truncated), mapped read-write. */
        fun create(file: File, sampleRate: Int, frames: Int): SingVocalTrack {
            require(frames in 0..MAX_FRAMES) { "song too long for a vocal track: $frames frames" }
            file.parentFile?.mkdirs()
            val bytes = HEADER_BYTES.toLong() + frames.toLong() * BYTES_PER_FRAME
            val buffer = RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.setLength(bytes)
                raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes)
            }
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            writeHeader(buffer, sampleRate, frames)
            return SingVocalTrack(sampleRate, frames, buffer, complete = false, file = file)
        }

        /** A finished track from the cache, or null when [file] isn't one (wrong version, cut short). */
        fun open(file: File): SingVocalTrack? {
            if (!file.isFile || file.length() < HEADER_BYTES) return null
            return try {
                val buffer = RandomAccessFile(file, "r").use { raf ->
                    raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                }
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                if (buffer.getInt(0) != MAGIC || buffer.getInt(4) != VERSION) return null
                val rate = buffer.getInt(8)
                val frames = buffer.getInt(12)
                if (buffer.getInt(16) != BYTES_PER_FRAME || buffer.getInt(OFFSET_COMPLETE) != 1) return null
                if (rate <= 0 || frames < 0 || file.length() != HEADER_BYTES.toLong() + frames.toLong() * BYTES_PER_FRAME) return null
                SingVocalTrack(rate, frames, buffer, complete = true, file = file)
            } catch (e: IOException) {
                null
            }
        }

        /** A heap-backed track, for tests. */
        fun inMemory(sampleRate: Int, frames: Int): SingVocalTrack {
            val buffer = ByteBuffer.allocate(HEADER_BYTES + frames * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
            writeHeader(buffer, sampleRate, frames)
            return SingVocalTrack(sampleRate, frames, buffer, complete = false, file = null)
        }

        private fun writeHeader(buffer: ByteBuffer, sampleRate: Int, frames: Int) {
            buffer.putInt(0, MAGIC)
            buffer.putInt(4, VERSION)
            buffer.putInt(8, sampleRate)
            buffer.putInt(12, frames)
            buffer.putInt(16, BYTES_PER_FRAME)
            buffer.putInt(OFFSET_COMPLETE, 0)
        }

        private fun to16(v: Float): Short =
            if (v.isFinite()) (v * 32767f).roundToInt().coerceIn(-32767, 32767).toShort() else 0
    }
}
