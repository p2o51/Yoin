package com.gpo.yoin.data.sync.identity

import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Payload field names of [SyncKinds.ACCOUNT] records. Persisted in Drive: never rename. */
object AccountDescriptorFields {
    const val PROVIDER = "provider"
    const val DISPLAY_NAME = "displayName"
    const val CREATED_AT = "createdAt"

    /** "<username> @ <host[:port]>" for Subsonic; absent otherwise. */
    const val HINT = "hint"

    /** Apple Music storefront (two-letter country), when known. */
    const val STOREFRONT = "storefront"

    /** Name of the device that wrote this version; not part of the projection. */
    const val DEVICE_NAME = "deviceName"
}

/** A music account as some device described it in Drive (an [SyncKinds.ACCOUNT] record). */
data class CloudAccountDescriptor(
    val syncProfileId: String,
    val provider: String,
    val displayName: String,
    val hint: String?,
    val storefront: String?,
    val deviceName: String?,
) {
    companion object {
        fun fromPayload(syncProfileId: String, payload: JsonObject): CloudAccountDescriptor? {
            val provider = payload.stringOrNull(AccountDescriptorFields.PROVIDER)?.takeIf(String::isNotEmpty)
                ?: return null
            return CloudAccountDescriptor(
                syncProfileId = syncProfileId,
                provider = provider,
                displayName = payload.stringOrNull(AccountDescriptorFields.DISPLAY_NAME).orEmpty(),
                hint = payload.stringOrNull(AccountDescriptorFields.HINT),
                storefront = payload.stringOrNull(AccountDescriptorFields.STOREFRONT),
                deviceName = payload.stringOrNull(AccountDescriptorFields.DEVICE_NAME),
            )
        }

        /** Null for other kinds, tombstones and payloads that aren't a descriptor. */
        fun fromRecord(record: SyncRecord): CloudAccountDescriptor? {
            if (record.kind != SyncKinds.ACCOUNT || record.deleted || record.key.isEmpty()) return null
            val payload = record.payload ?: return null
            val json = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
            return fromPayload(record.key, json)
        }

        fun fromRecords(records: Iterable<SyncRecord>): List<CloudAccountDescriptor> =
            records.mapNotNull(::fromRecord).distinctBy(CloudAccountDescriptor::syncProfileId)

        private fun JsonObject.stringOrNull(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it != JsonNull && it.isString }?.content
    }
}
