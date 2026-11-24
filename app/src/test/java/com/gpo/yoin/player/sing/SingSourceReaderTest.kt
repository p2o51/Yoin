package com.gpo.yoin.player.sing

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SingSourceReaderTest {
    private val bytes = ByteArray(1_000_000) { (it * 31 + 7).toByte() }

    /** A data source over [bytes] that honours positions, counting opens and bytes served. */
    private inner class Fake(private val knownLength: Boolean = true) : DataSource {
        private var at = 0L
        var opened = false

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            at = dataSpec.position
            opened = true
            return if (knownLength) bytes.size - at else C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at >= bytes.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, 10_000, (bytes.size - at).toInt()) // short reads, like a socket
            System.arraycopy(bytes, at.toInt(), buffer, offset, n)
            at += n
            return n
        }

        override fun getUri(): Uri? = null

        override fun close() {
            opened = false
        }
    }

    private fun reader(knownLength: Boolean = true) = SingSourceReader({ Fake(knownLength) }, Uri.parse("https://example.invalid/song.flac"))

    private fun read(reader: SingSourceReader, at: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val n = reader.readAt(at + done, out, done, size - done)
            if (n < 0) break
            done += n
        }
        return out.copyOf(done)
    }

    @Test
    fun should_readAnywhere_when_seeking() {
        val r = reader()
        assertEquals(bytes.size.toLong(), r.size())
        assertArrayEquals(bytes.copyOfRange(700_000, 700_100), read(r, 700_000, 100))
        assertArrayEquals(bytes.copyOfRange(10, 70_010), read(r, 10, 70_000))
        assertArrayEquals(bytes.copyOfRange(999_990, 1_000_000), read(r, 999_990, 50)) // short at the end
        assertEquals(-1, r.readAt(1_000_000, ByteArray(4), 0, 4))
    }

    @Test
    fun should_keepOneConnection_when_readingOn() {
        val r = reader()
        var at = 0L
        while (at < bytes.size) {
            val chunk = read(r, at, 4_096)
            assertArrayEquals(bytes.copyOfRange(at.toInt(), (at + chunk.size).toInt()), chunk)
            at += 4_096
        }
        assertEquals(1, r.opens)
        assertEquals(bytes.size.toLong(), r.bytesRead)
    }

    @Test
    fun should_notReconnect_when_steppingBackOrSlightlyForward() {
        val r = reader()
        read(r, 0, 200_000)
        val opens = r.opens
        read(r, 100_000, 1_000) // back into a kept block
        read(r, 200_000L + 2 * SingSourceReader.BLOCK, 1_000) // a short step forward reads through
        assertEquals(opens, r.opens)
        read(r, 900_000, 10) // far: a new connection
        assertEquals(opens + 1, r.opens)
    }

    @Test
    fun should_learnLength_when_streamDoesNotSay() {
        val r = reader(knownLength = false)
        assertArrayEquals(bytes.copyOfRange(0, 1_000), read(r, 0, 1_000))
        assertEquals(C.LENGTH_UNSET.toLong(), r.length)
        read(r, 990_000, 20_000)
        assertEquals(bytes.size.toLong(), r.length)
    }
}
