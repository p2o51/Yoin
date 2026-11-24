package com.gpo.yoin.player.sing

import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Random access to the original song's bytes through the player's own data source chain (the
 * downloads, then the stream cache ExoPlayer fills as it plays, then the network with range requests)
 * — so the bytes ExoPlayer already fetched aren't fetched again, and a seek far into the file costs a
 * range request, not a download of everything before it.
 *
 * Reads go through [BLOCK]-byte blocks, the last [CACHED_BLOCKS] kept: an extractor rereads headers
 * and steps back a little now and then, and a reopened connection per small step back would be slow.
 * A read just past what was last fetched carries on the open connection. Thread-safe (one read at a
 * time).
 */
@OptIn(UnstableApi::class)
class SingSourceReader(
    private val factory: DataSource.Factory,
    private val uri: Uri,
) {
    @Volatile private var source: DataSource? = null

    @Volatile private var aborted = false

    /** Where the open connection will read next; −1 when none is open. */
    private var position = -1L

    /** The file's length, once a response told it; [C.LENGTH_UNSET] before (or for a stream). */
    @Volatile var length: Long = C.LENGTH_UNSET.toLong()
        private set

    /** Blocks by index, the most recently used last. */
    private val blocks = object : LinkedHashMap<Long, ByteArray>(CACHED_BLOCKS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > CACHED_BLOCKS
    }

    /** Connections opened (for the log and tests). */
    @Volatile var opens: Int = 0
        private set

    /** Bytes fetched through the data source. */
    @Volatile var bytesRead: Long = 0
        private set

    /** When the first bytes came back (System.nanoTime), 0 before. */
    @Volatile var firstBytesNanos: Long = 0
        private set

    /**
     * Up to [size] bytes from [at] into [buffer] from [offset]: the count, or −1 at the end of the file.
     */
    @Synchronized
    fun readAt(at: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (at < 0) throw IOException("negative position $at")
        val knownLength = length
        if (knownLength >= 0 && at >= knownLength) return -1
        var done = 0
        while (done < size) {
            val pos = at + done
            val index = pos / BLOCK
            val block = block(index) ?: break
            val inBlock = (pos - index * BLOCK).toInt()
            if (inBlock >= block.size) break
            val n = minOf(size - done, block.size - inBlock)
            System.arraycopy(block, inBlock, buffer, offset + done, n)
            done += n
            if (block.size < BLOCK) break // the file's last block
        }
        return if (done == 0) -1 else done
    }

    /** The file's size, opening a connection to learn it if no response has yet; −1 if unknown. */
    @Synchronized
    fun size(): Long {
        if (length < 0 && opens == 0) block(0)
        return length
    }

    /**
     * From another thread: closes the open connection under a read in progress, so a decode that was
     * cancelled doesn't sit in a network read; every later read fails.
     */
    fun abort() {
        aborted = true
        try {
            source?.close()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun close() {
        try {
            source?.close()
        } finally {
            source = null
            position = -1
            blocks.clear()
        }
    }

    /** Block [index] (shorter at the end of the file), null past the end. */
    private fun block(index: Long): ByteArray? {
        blocks[index]?.let { return it }
        val start = index * BLOCK
        val knownLength = length
        if (knownLength >= 0 && start >= knownLength) return null
        // A short step forward: read through the blocks between on the open connection (each kept).
        while (source != null && position in (start - SKIP_THROUGH) until start && position % BLOCK == 0L) {
            if (fetch(position / BLOCK) == null) return null
        }
        return fetch(index)
    }

    /** Fetches block [index] into the cache, reading on from the open connection when it's there. */
    private fun fetch(index: Long): ByteArray? {
        val start = index * BLOCK
        if (source == null || position != start) open(start)
        val src = checkNotNull(source)
        val out = ByteArray(BLOCK)
        var filled = 0
        while (filled < BLOCK) {
            val n = src.read(out, filled, BLOCK - filled)
            if (n == C.RESULT_END_OF_INPUT) {
                if (length < 0) length = start + filled
                break
            }
            if (firstBytesNanos == 0L && n > 0) firstBytesNanos = System.nanoTime()
            filled += n
            bytesRead += n
        }
        position = start + filled
        if (filled == 0) return null
        val block = if (filled == BLOCK) out else out.copyOf(filled)
        blocks[index] = block
        return block
    }

    private fun open(at: Long) {
        if (aborted) throw IOException("source reader aborted")
        try {
            source?.close()
        } catch (_: IOException) {
        }
        source = null
        position = -1
        val src = factory.createDataSource()
        val resolved = src.open(DataSpec.Builder().setUri(uri).setPosition(at).build())
        opens++
        if (length < 0 && resolved != C.LENGTH_UNSET.toLong()) length = at + resolved
        source = src
        position = at
    }

    /**
     * The last read that failed. MediaExtractor takes a failed read for the end of the file, so the
     * decoder checks this before believing an end of stream.
     */
    @Volatile var failure: Exception? = null
        private set

    /** This reader as a [MediaDataSource], for MediaExtractor. */
    fun asMediaDataSource(): MediaDataSource = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = try {
            this@SingSourceReader.readAt(position, buffer, offset, size)
        } catch (e: Exception) {
            failure = e
            throw if (e is IOException) e else IOException(e)
        }

        override fun getSize(): Long = try {
            size()
        } catch (e: Exception) {
            failure = e
            -1
        }

        override fun close() = this@SingSourceReader.close()
    }

    companion object {
        const val BLOCK = 64 * 1024
        const val CACHED_BLOCKS = 32

        /** A read this far past the open connection's position reads through rather than reconnecting. */
        private const val SKIP_THROUGH = 4L * BLOCK
    }
}
