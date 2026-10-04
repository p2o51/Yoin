package com.gpo.yoin.data.sync

import java.security.MessageDigest
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
 */
object CanonicalJson {
    /** Canonical text: object keys sorted recursively, no whitespace. */
    fun canonical(element: JsonElement): String = buildString { write(element) }

    /** Lowercase hex SHA-256 of [canonical]. */
    fun hash(element: JsonElement): String = sha256Hex(canonical(element))

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** Small non-negative int derived from [text], stable across devices and app versions (shard routing). */
    fun stableHash(text: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return ((digest[0].toInt() and 0x7f) shl 24) or
            ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or
            (digest[3].toInt() and 0xff)
    }

    private fun StringBuilder.write(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                append('{')
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
