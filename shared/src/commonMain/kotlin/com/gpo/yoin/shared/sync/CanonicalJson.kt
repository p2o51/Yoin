package com.gpo.yoin.shared.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Order-independent JSON identity for sync: the same logical value always
 * hashes the same, whatever key order a device (or an older app version)
 * happened to write. Hashes decide "did this change" everywhere in sync, so
 * they must never depend on insertion order or whitespace.
 *
 * Port of Android's `data/sync/CanonicalJson.kt` (same output, byte for byte).
 */
object CanonicalJson {
    /** Canonical text: object keys sorted recursively, no whitespace. */
    fun canonical(element: JsonElement): String = buildString { write(element) }

    /** Lowercase hex SHA-256 of [canonical]. */
    fun hash(element: JsonElement): String = sha256Hex(canonical(element))

    fun sha256Hex(text: String): String {
        val digest = Sha256.digest(text.encodeToByteArray())
        val hex = "0123456789abcdef"
        return buildString(64) {
            for (byte in digest) {
                val v = byte.toInt() and 0xff
                append(hex[v ushr 4])
                append(hex[v and 0x0f])
            }
        }
    }

    /** Small non-negative int derived from [text], stable across devices and app versions (shard routing). */
    fun stableHash(text: String): Int {
        val digest = Sha256.digest(text.encodeToByteArray())
        return ((digest[0].toInt() and 0x7f) shl 24) or
            ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or
            (digest[3].toInt() and 0xff)
    }

    private fun StringBuilder.write(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                append('{')
                // String.compareTo is UTF-16 code-unit order on every platform, as java.lang.String's.
                element.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    append(JsonPrimitive(key).toString())
                    append(':')
                    write(element.getValue(key))
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    write(item)
                }
                append(']')
            }
            JsonNull -> append("null")
            is JsonPrimitive -> append(element.toString())
        }
    }
}
