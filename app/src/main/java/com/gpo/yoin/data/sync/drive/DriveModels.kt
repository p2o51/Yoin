package com.gpo.yoin.data.sync.drive

import com.gpo.yoin.data.sync.RemoteFile
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Drive v3 REST shapes, reduced to the fields cloud sync asks for.
 * Drive encodes int64 fields (version, size) as quoted strings; kotlinx
 * serialization decodes a quoted number into Long, so they are typed Long here.
 */

@Serializable
internal data class DriveFileList(
    @SerialName("nextPageToken") val nextPageToken: String? = null,
    @SerialName("files") val files: List<DriveFile> = emptyList(),
)

@Serializable
internal data class DriveFile(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("version") val version: Long = 0L,
    /** RFC 3339, e.g. "2026-10-04T08:15:30.123Z". */
    @SerialName("modifiedTime") val modifiedTime: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("appProperties") val appProperties: Map<String, String> = emptyMap(),
) {
    fun toRemoteFile(): RemoteFile = RemoteFile(
        id = id,
        name = name,
        version = version,
        modifiedTime = modifiedTime?.let(::parseRfc3339Millis),
        size = size,
        appProperties = appProperties,
    )
}

/** Metadata part of a multipart create. Never sent on update (parents/id are not writable there). */
@Serializable
internal data class DriveCreateMetadata(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("parents") val parents: List<String>,
    @SerialName("mimeType") val mimeType: String,
    @SerialName("appProperties") val appProperties: Map<String, String>,
)

@Serializable
internal data class DriveGeneratedIds(
    @SerialName("ids") val ids: List<String> = emptyList(),
)

@Serializable
internal data class DriveAbout(
    @SerialName("user") val user: DriveUser? = null,
)

@Serializable
internal data class DriveUser(
    @SerialName("permissionId") val permissionId: String? = null,
    @SerialName("emailAddress") val emailAddress: String? = null,
    @SerialName("displayName") val displayName: String? = null,
)

@Serializable
internal data class DriveErrorResponse(
    @SerialName("error") val error: DriveError? = null,
)

@Serializable
internal data class DriveError(
    @SerialName("code") val code: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("status") val status: String? = null,
    /** Legacy per-error list: `reason` is e.g. storageQuotaExceeded, accessNotConfigured. */
    @SerialName("errors") val errors: List<DriveErrorItem> = emptyList(),
    /** google.rpc details: an ErrorInfo entry carries `reason`, e.g. SERVICE_DISABLED. */
    @SerialName("details") val details: List<DriveErrorDetail> = emptyList(),
)

@Serializable
internal data class DriveErrorItem(
    @SerialName("domain") val domain: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("message") val message: String? = null,
)

@Serializable
internal data class DriveErrorDetail(
    @SerialName("@type") val type: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("domain") val domain: String? = null,
)

internal fun parseRfc3339Millis(text: String): Long? =
    runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
