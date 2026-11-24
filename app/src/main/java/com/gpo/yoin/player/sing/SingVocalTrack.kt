package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.dsp.getAt
import com.gpo.yoin.player.sing.dsp.shortsAt
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.zip.CRC32
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
 *
 * Persistence (2026-11-24): a track no longer has to be finished to be kept. After the samples comes
 * a coverage table, 8 bytes a block — a marker and the CRC-32 of the block's vocal samples — written
 * when the block completes, samples first. A track Sing stopped working on (Sing off, another song,
 * the app killed) stays in the cache as a partial file; [open] with `partial = true` maps it back,
 * every marked block checked against its CRC (a block whose samples never reached the disk — power
 * cut — fails and is simply done again), and the engine separates only what is still missing.
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

    /** Where the coverage table starts; −1 for a version-1 file (finished tracks only, no table). */
    private val tableOffset: Int = if (data.capacity() >= tableBytesEnd(frames)) HEADER_BYTES + frames * BYTES_PER_FRAME else -1

    private val crc = CRC32()
    private var crcBytes = ByteArray(0)

    /** The writer's scratch for a block: its frames' samples, and the floats going into them. */
    private var frameShorts = ShortArray(0)
    private var floatsL = FloatArray(0)
    private var floatsR = FloatArray(0)

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

    /** Blocks an unfinished file had marked whose samples failed their CRC on [open] (done again). */
    var droppedOnOpen: Int = 0
        private set

    /** Readiness blocks in the track. */
    val blockCount: Int get() = blocks

    /** Whether block [block] (frames from block × [BLOCK_FRAMES]) has its vocal in. */
    fun isBlockReady(block: Int): Boolean = ready.get(block) == 1

    /** The frames whose vocal is in, as merged half-open ranges, in order. */
    fun readyRanges(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var b = 0
        while (b < blocks) {
            if (ready.get(b) != 1) {
                b++
                continue
            }
            val start = b
            while (b < blocks && ready.get(b) == 1) b++
            out += start * BLOCK_FRAMES to minOf(frames, b * BLOCK_FRAMES)
        }
        return out
    }

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
    fun writeReference(left: FloatBuffer, right: FloatBuffer) = writeReference(0, frames, left, right)

    /**
     * Writes the mix reference for frames [from, to), read from [left]/[right] at the frame's own index
     * (the decoded song, filled in as it decodes). The vocal samples in those frames are left alone.
     */
    fun writeReference(from: Int, to: Int, left: FloatBuffer, right: FloatBuffer) {
        var at = from.coerceAtLeast(0)
        val end0 = to.coerceAtMost(frames)
        while (at < end0) {
            val end = minOf(end0, (at / BLOCK_FRAMES + 1) * BLOCK_FRAMES)
            val count = end - at
            val shorts = readFrames(at, count)
            left.getAt(at, floatsL, 0, count)
            right.getAt(at, floatsR, 0, count)
            for (n in 0 until count) shorts[3 * n + 2] = to16(0.5f * (floatsL[n] + floatsR[n]))
            writeFrames(at, count)
            at = end
        }
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
            val count = end - at
            // The block's frames are read and written back whole (the mix reference in them unchanged):
            // two bulk copies instead of a mapped-buffer access per sample.
            val shorts = readFrames(at, count)
            left.getAt(at - sourceOffset, floatsL, 0, count)
            right.getAt(at - sourceOffset, floatsR, 0, count)
            for (n in 0 until count) {
                shorts[3 * n] = to16(floatsL[n])
                shorts[3 * n + 1] = to16(floatsR[n])
            }
            writeFrames(at, count)
            written[block] += end - at
            if (written[block] >= blockLength(block) && ready.get(block) == 0) markReady(block)
            at = end
        }
    }

    /**
     * Copies every block whose vocal is in [other] (another track of the same song: the same frames
     * from 0, perhaps a different length) and is whole in both, mix reference included, and marks it
     * ready here. For a track remade at the song's true length once decoding found it.
     */
    fun copyReadyFrom(other: SingVocalTrack) {
        require(other.sampleRate == sampleRate) { "tracks at different rates" }
        val common = minOf(blocks, other.blocks)
        for (b in 0 until common) {
            if (ready.get(b) == 1 || other.ready.get(b) != 1) continue
            val length = blockLength(b)
            if (other.blockLength(b) != length) continue
            val shorts = other.readFrames(b * BLOCK_FRAMES, length)
            if (frameShorts.size < length * SAMPLES_PER_FRAME) readFrames(b * BLOCK_FRAMES, length)
            System.arraycopy(shorts, 0, frameShorts, 0, length * SAMPLES_PER_FRAME)
            writeFrames(b * BLOCK_FRAMES, length)
            written[b] = length
            markReady(b)
        }
    }

    /** Block [block] is whole: its CRC and marker go into the table (samples first), then the flag. */
    private fun markReady(block: Int) {
        if (tableOffset >= 0) {
            val at = tableOffset + block * TABLE_ENTRY_BYTES
            data.putInt(at + 4, blockCrc(block))
            data.putInt(at, READY_MARK)
        }
        ready.set(block, 1)
        readyBlocks++
    }

    /** CRC-32 of block [block]'s vocal samples (V_L, V_R; not the mix reference, written separately). */
    private fun blockCrc(block: Int): Int {
        val length = blockLength(block)
        val shorts = readFrames(block * BLOCK_FRAMES, length)
        if (crcBytes.size < length * 4) crcBytes = ByteArray(length * 4)
        for (n in 0 until length) {
            val l = shorts[3 * n].toInt()
            val r = shorts[3 * n + 1].toInt()
            crcBytes[4 * n] = l.toByte()
            crcBytes[4 * n + 1] = (l shr 8).toByte()
            crcBytes[4 * n + 2] = r.toByte()
            crcBytes[4 * n + 3] = (r shr 8).toByte()
        }
        crc.reset()
        crc.update(crcBytes, 0, length * 4)
        return crc.value.toInt()
    }

    /**
     * Restores the ready flags of an unfinished file from its table: a marked block counts only if its
     * samples still match the CRC written with the mark; the rest are cleared, to be done again.
     */
    private fun restoreCoverage(): Int {
        if (tableOffset < 0) return 0
        var dropped = 0
        for (b in 0 until blocks) {
            val at = tableOffset + b * TABLE_ENTRY_BYTES
            if (data.getInt(at) != READY_MARK) continue
            if (blockCrc(b) == data.getInt(at + 4)) {
                written[b] = blockLength(b)
                ready.set(b, 1)
                readyBlocks++
            } else {
                data.putInt(at, 0)
                dropped++
            }
        }
        return dropped
    }

    /** Pushes what's written to the file (a kept partial track; the system would anyway, in time). */
    fun flush() {
        (data as? MappedByteBuffer)?.force()
    }

    /** Stamps the file complete and flushes it, so the cache can keep it ([open] reads it back). */
    fun finish() {
        check(isComplete) { "vocal track not complete" }
        data.putInt(OFFSET_COMPLETE, 1)
        (data as? MappedByteBuffer)?.force()
    }

    /** Frames [from, from + count) (≤ a block) into [frameShorts], 3 samples each; sizes the scratch. */
    private fun readFrames(from: Int, count: Int): ShortArray {
        if (frameShorts.size < count * SAMPLES_PER_FRAME) {
            frameShorts = ShortArray(count * SAMPLES_PER_FRAME)
            floatsL = FloatArray(count)
            floatsR = FloatArray(count)
        }
        data.shortsAt(offset(from), ByteOrder.LITTLE_ENDIAN).get(frameShorts, 0, count * SAMPLES_PER_FRAME)
        return frameShorts
    }

    private fun writeFrames(from: Int, count: Int) {
        data.shortsAt(offset(from), ByteOrder.LITTLE_ENDIAN).put(frameShorts, 0, count * SAMPLES_PER_FRAME)
    }

    private fun blockLength(block: Int): Int = minOf(BLOCK_FRAMES, frames - block * BLOCK_FRAMES)

    private fun offset(frame: Int): Int = HEADER_BYTES + frame * BYTES_PER_FRAME

    companion object {
        const val BLOCK_FRAMES = 4096
        const val BYTES_PER_FRAME = 6
        private const val SAMPLES_PER_FRAME = BYTES_PER_FRAME / 2
        const val HEADER_BYTES = 32

        /** The cache's file suffix for finished tracks. */
        const val SUFFIX = ".vtrack"

        private const val MAGIC = 0x54565359 // "YSVT", little-endian

        /** 2: a coverage table after the samples (1: finished tracks only, none). */
        private const val VERSION = 2
        private const val VERSION_1 = 1
        private const val OFFSET_COMPLETE = 20
        private const val FROM_16 = 1f / 32767f
        private const val TABLE_ENTRY_BYTES = 8
        private const val READY_MARK = 0x31594452 // "RDY1"

        /** The longest song a track can hold (one mapping: 2 GB, samples and table). */
        const val MAX_FRAMES = ((Int.MAX_VALUE.toLong() - HEADER_BYTES - TABLE_ENTRY_BYTES) * BLOCK_FRAMES /
            (BYTES_PER_FRAME * BLOCK_FRAMES + TABLE_ENTRY_BYTES)).toInt()

        private fun blocksFor(frames: Int): Int = (frames + BLOCK_FRAMES - 1) / BLOCK_FRAMES

        /** The file's length with its table. */
        private fun tableBytesEnd(frames: Int): Long =
            HEADER_BYTES.toLong() + frames.toLong() * BYTES_PER_FRAME + blocksFor(frames).toLong() * TABLE_ENTRY_BYTES

        /** A new, empty track backed by [file] (created or truncated), mapped read-write. */
        fun create(file: File, sampleRate: Int, frames: Int): SingVocalTrack {
            require(frames in 0..MAX_FRAMES) { "song too long for a vocal track: $frames frames" }
            file.parentFile?.mkdirs()
            val bytes = tableBytesEnd(frames)
            val buffer = RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.setLength(bytes)
                raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes)
            }
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            writeHeader(buffer, sampleRate, frames)
            return SingVocalTrack(sampleRate, frames, buffer, complete = false, file = file)
        }

        /**
         * A track from the cache, or null when [file] isn't one (wrong version, cut short): a finished
         * one, or with [partial] also an unfinished one (mapped read-write, ready where its table and
         * CRCs say so, to be carried on).
         */
        fun open(file: File, partial: Boolean = false): SingVocalTrack? {
            if (!file.isFile || file.length() < HEADER_BYTES) return null
            return try {
                val buffer = RandomAccessFile(file, if (partial) "rw" else "r").use { raf ->
                    raf.channel.map(if (partial) FileChannel.MapMode.READ_WRITE else FileChannel.MapMode.READ_ONLY, 0, raf.length())
                }
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                if (buffer.getInt(0) != MAGIC) return null
                val version = buffer.getInt(4)
                val rate = buffer.getInt(8)
                val frames = buffer.getInt(12)
                if (buffer.getInt(16) != BYTES_PER_FRAME || rate <= 0 || frames < 0 || frames > MAX_FRAMES) return null
                val complete = buffer.getInt(OFFSET_COMPLETE) == 1
                val expected = when (version) {
                    VERSION -> tableBytesEnd(frames)
                    VERSION_1 -> HEADER_BYTES.toLong() + frames.toLong() * BYTES_PER_FRAME
                    else -> return null
                }
                if (file.length() != expected) return null
                if (!complete && (!partial || version != VERSION)) return null
                SingVocalTrack(rate, frames, buffer, complete = complete, file = file).also {
                    if (!complete) it.droppedOnOpen = it.restoreCoverage()
                }
            } catch (e: IOException) {
                null
            }
        }

        /** A heap-backed track, for tests. */
        fun inMemory(sampleRate: Int, frames: Int): SingVocalTrack {
            val buffer = ByteBuffer.allocate(tableBytesEnd(frames).toInt()).order(ByteOrder.LITTLE_ENDIAN)
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
