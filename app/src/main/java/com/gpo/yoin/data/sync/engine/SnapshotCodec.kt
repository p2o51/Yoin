package com.gpo.yoin.data.sync.engine

import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.ResetMarker
import com.gpo.yoin.data.sync.SyncEnvelope
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncRecordEntity
import com.gpo.yoin.data.sync.WireRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private val syncJsonInstance = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** The one Json configuration every sync file and payload goes through. */
object SyncJson : StringFormat by syncJsonInstance {
    val json: Json get() = syncJsonInstance
}

/** A Drive file that is not a readable Yoin sync file. Callers skip the file; seen versions must not advance. */
sealed class SyncFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Written by a newer Yoin ([schema] > [SyncFormat.SCHEMA]): skip the file and surface "update Yoin"
 * for [deviceName].
 */
class SyncSchemaTooNewException(
    val schema: Int,
    val deviceId: String?,
    val deviceName: String?,
) : SyncFormatException(
    "Sync file schema $schema is newer than supported ${SyncFormat.SCHEMA} " +
        "(from ${deviceName ?: deviceId ?: "unknown device"})",
)

class MalformedSyncFileException(message: String, cause: Throwable? = null) : SyncFormatException(message, cause)

/** Envelope <-> bytes. State/artifact files are gzip JSON; reset markers are plain JSON. */
object SnapshotCodec {
    fun encode(envelope: SyncEnvelope): ByteArray =
        gzip(SyncJson.encodeToString(SyncEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8))

    /**
     * Accepts gzip (sniffed by magic bytes) or plain JSON. A single record that does not decode is dropped
     * instead of failing the whole file.
     *
     * @throws SyncSchemaTooNewException when the file was written with a newer schema.
     * @throws MalformedSyncFileException when it is not a Yoin sync file at all.
     */
    fun decode(bytes: ByteArray): SyncEnvelope {
        val root = parseRoot(bytes)
        val format = root.stringOrNull("format")
        if (format != SyncFormat.FORMAT) throw MalformedSyncFileException("Not a Yoin sync file (format=$format)")
        val schema = (root["schema"] as? JsonPrimitive)?.intOrNull
            ?: throw MalformedSyncFileException("Sync file without schema")
        if (schema > SyncFormat.SCHEMA) {
            throw SyncSchemaTooNewException(schema, root.stringOrNull("deviceId"), root.stringOrNull("deviceName"))
        }
        if (schema < 1) throw MalformedSyncFileException("Invalid sync schema $schema")
        val records = (root["records"] as? JsonArray)
            ?: throw MalformedSyncFileException("Sync file without records")
        val header = JsonObject(root + ("records" to JsonArray(emptyList())))
        val envelope = try {
            SyncJson.json.decodeFromJsonElement(SyncEnvelope.serializer(), header)
        } catch (e: SerializationException) {
            throw MalformedSyncFileException("Unreadable sync file header", e)
        } catch (e: IllegalArgumentException) {
            throw MalformedSyncFileException("Unreadable sync file header", e)
        }
        return envelope.copy(records = records.mapNotNull(::decodeRecordOrNull))
    }

    fun encodeReset(marker: ResetMarker): ByteArray =
        SyncJson.encodeToString(ResetMarker.serializer(), marker).toByteArray(Charsets.UTF_8)

    /** @throws MalformedSyncFileException when the bytes are not a reset marker. */
    fun decodeReset(bytes: ByteArray): ResetMarker {
        val root = parseRoot(bytes)
        return try {
            SyncJson.json.decodeFromJsonElement(ResetMarker.serializer(), root)
        } catch (e: SerializationException) {
            throw MalformedSyncFileException("Unreadable reset marker", e)
        } catch (e: IllegalArgumentException) {
            throw MalformedSyncFileException("Unreadable reset marker", e)
        }
    }

    fun isGzip(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    private fun decodeRecordOrNull(element: JsonElement): WireRecord? = try {
        SyncJson.json.decodeFromJsonElement(WireRecord.serializer(), element)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun parseRoot(bytes: ByteArray): JsonObject {
        val text = try {
            (if (isGzip(bytes)) gunzip(bytes) else bytes).toString(Charsets.UTF_8)
        } catch (e: IOException) {
            throw MalformedSyncFileException("Corrupt gzip sync file", e)
        }
        val element = try {
            SyncJson.json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw MalformedSyncFileException("Sync file is not JSON", e)
        }
        return element as? JsonObject ?: throw MalformedSyncFileException("Sync file is not a JSON object")
    }

    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}

// ---- Payload text <-> JsonObject

/** Exact compact text of [payload] as stored in sync_records (key order preserved, never canonicalised). */
fun payloadText(payload: JsonObject): String = SyncJson.encodeToString(JsonObject.serializer(), payload)

/** Parses a stored payload; null when it is not a JSON object. */
fun parsePayload(text: String?): JsonObject? {
    if (text == null) return null
    return try {
        SyncJson.json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    }
}

// ---- WireRecord <-> SyncRecordEntity

/**
 * A live record without a payload stays live with a null payload: callers must reject it, never treat it
 * as a delete.
 */
fun WireRecord.toEntity(): SyncRecordEntity {
    val livePayload = if (deleted) null else payload
    val prevKnown = prevTs != null && prevDevice != null
    return SyncRecordEntity(
        kind = kind,
        scope = scope,
        key = key,
        kindVersion = kindVersion,
        ts = ts,
        device = device,
        prevTs = if (prevKnown) prevTs else null,
        prevDevice = if (prevKnown) prevDevice else null,
        deleted = deleted,
        payload = livePayload?.let(::payloadText),
        payloadHash = livePayload?.let(CanonicalJson::hash),
    )
}

fun SyncRecordEntity.toWire(): WireRecord = WireRecord(
    kind = kind,
    kindVersion = kindVersion,
    scope = scope,
    key = key,
    ts = ts,
    device = device,
    prevTs = prevTs,
    prevDevice = prevDevice,
    deleted = deleted,
    payload = if (deleted) null else parsePayload(payload),
)
