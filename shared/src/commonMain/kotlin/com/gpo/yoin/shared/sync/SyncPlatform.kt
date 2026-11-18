package com.gpo.yoin.shared.sync

/** gzip (RFC 1952) of [bytes]. JVM: java.util.zip; iOS: the system zlib. */
internal expect fun gzipBytes(bytes: ByteArray): ByteArray

/** Inflates a gzip member; throws [SyncGzipException] on corrupt input. */
internal expect fun gunzipBytes(bytes: ByteArray): ByteArray

class SyncGzipException(message: String) : Exception(message)

/** Wall clock in epoch milliseconds. */
expect fun syncNowMillis(): Long
