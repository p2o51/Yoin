@file:OptIn(ExperimentalForeignApi::class)

package com.gpo.yoin.shared.sync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.zlib.MAX_WBITS
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

private const val CHUNK = 64 * 1024

/** windowBits 15 + 16 = gzip wrapper (zlib.h). */
private const val GZIP_WINDOW_BITS = MAX_WBITS + 16

internal actual fun gzipBytes(bytes: ByteArray): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    // Level 6 (Z_DEFAULT_COMPRESSION), memLevel 8, as java.util.zip.Deflater's defaults.
    check(deflateInit2(stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, GZIP_WINDOW_BITS, 8, Z_DEFAULT_STRATEGY) == Z_OK) {
        "deflateInit2 failed"
    }
    val out = ArrayList<ByteArray>()
    val buffer = ByteArray(CHUNK)
    try {
        val input = if (bytes.isEmpty()) ByteArray(1) else bytes
        input.usePinned { pinnedIn ->
            stream.next_in = pinnedIn.addressOf(0).reinterpret<UByteVar>()
            stream.avail_in = bytes.size.toUInt()
            buffer.usePinned { pinnedOut ->
                do {
                    stream.next_out = pinnedOut.addressOf(0).reinterpret<UByteVar>()
                    stream.avail_out = CHUNK.toUInt()
                    val status = deflate(stream.ptr, Z_FINISH)
                    check(status == Z_OK || status == Z_STREAM_END || status == Z_BUF_ERROR) { "deflate failed ($status)" }
                    val produced = CHUNK - stream.avail_out.toInt()
                    if (produced > 0) out += buffer.copyOf(produced)
                } while (status != Z_STREAM_END)
            }
        }
    } finally {
        deflateEnd(stream.ptr)
    }
    concat(out)
}

internal actual fun gunzipBytes(bytes: ByteArray): ByteArray = memScoped {
    if (bytes.isEmpty()) throw SyncGzipException("Empty gzip input")
    val stream = alloc<z_stream>()
    if (inflateInit2(stream.ptr, GZIP_WINDOW_BITS) != Z_OK) throw SyncGzipException("inflateInit2 failed")
    val out = ArrayList<ByteArray>()
    val buffer = ByteArray(CHUNK)
    try {
        bytes.usePinned { pinnedIn ->
            stream.next_in = pinnedIn.addressOf(0).reinterpret<UByteVar>()
            stream.avail_in = bytes.size.toUInt()
            buffer.usePinned { pinnedOut ->
                while (true) {
                    stream.next_out = pinnedOut.addressOf(0).reinterpret<UByteVar>()
                    stream.avail_out = CHUNK.toUInt()
                    val status = inflate(stream.ptr, Z_NO_FLUSH)
                    val produced = CHUNK - stream.avail_out.toInt()
                    if (produced > 0) out += buffer.copyOf(produced)
                    if (status == Z_STREAM_END) break
                    if (status != Z_OK) throw SyncGzipException("Corrupt gzip ($status)")
                    if (stream.avail_in == 0u && produced == 0) throw SyncGzipException("Truncated gzip")
                }
            }
        }
    } finally {
        inflateEnd(stream.ptr)
    }
    concat(out)
}

private fun concat(parts: List<ByteArray>): ByteArray {
    val result = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(result, offset)
        offset += part.size
    }
    return result
}

actual fun syncNowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

internal actual fun syncMainContext(): kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Main
