package com.gpo.yoin.shared.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal actual fun gzipBytes(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(bytes.size / 4 + 64)
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

internal actual fun gunzipBytes(bytes: ByteArray): ByteArray = try {
    GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
} catch (e: IOException) {
    throw SyncGzipException(e.message ?: "Corrupt gzip")
}

actual fun syncNowMillis(): Long = System.currentTimeMillis()

internal actual fun syncMainContext(): kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
