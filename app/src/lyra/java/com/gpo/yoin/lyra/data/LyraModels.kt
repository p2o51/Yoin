package com.gpo.yoin.lyra.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class LyraCapabilitiesResponse(
    @SerialName("lyra_version") val lyraVersion: String = "",
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    val capabilities: Map<String, LyraCapabilityEntry> = emptyMap(),
) {
    fun isEnabled(name: String): Boolean = capabilities[name]?.enabled == true
}

@Serializable
data class LyraCapabilityEntry(
    val enabled: Boolean = false,
    val version: Int = 1,
    val count: Int? = null,
    @SerialName("supports_pull") val supportsPull: Boolean? = null,
    val sse: Boolean? = null,
)

@Serializable
data class LyraSourcesResponse(
    val sources: List<LyraSourceInfo> = emptyList(),
)

@Serializable
data class LyraSourceInfo(
    @SerialName("source_id") val sourceId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("default_target") val defaultTarget: String = "library",
    val qualities: List<String> = emptyList(),
    @SerialName("default_quality") val defaultQuality: String = "",
)

@Serializable
data class LyraSourceSearchResponse(
    val type: String = "song",
    val results: List<LyraSourceItem> = emptyList(),
)

@Serializable
data class LyraSourceItem(
    val type: String = "song",
    val mid: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    @SerialName("album_mid") val albumMid: String = "",
    @SerialName("duration_s") val durationSec: Double = 0.0,
    val year: String = "",
    val date: String = "",
    @SerialName("track_count") val trackCount: Int = 0,
    val disc: Int = 0,
    val track: Int = 0,
    val qualities: List<String> = emptyList(),
    @SerialName("cover_url") val coverUrl: String = "",
)

@Serializable
data class LyraSourceAlbumInfo(
    val mid: String = "",
    val title: String = "",
    val artist: String = "",
    val date: String = "",
    val year: String = "",
    val label: String = "",
    val genre: String = "",
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("disc_count") val discCount: Int = 0,
    @SerialName("cover_url") val coverUrl: String = "",
)

@Serializable
data class LyraSourceAlbumResponse(
    val album: LyraSourceAlbumInfo = LyraSourceAlbumInfo(),
    val tracks: List<LyraSourceItem> = emptyList(),
)

@Serializable
data class LyraDownloadRequest(
    val mid: String = "",
    @SerialName("album_mid") val albumMid: String = "",
    val quality: String = "",
    val layout: String = "",
    val title: String = "",
    val artist: String = "",
    @SerialName("album_title") val albumTitle: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
)

@Serializable
data class LyraDownloadResponse(
    @SerialName("job_id") val jobId: Long = 0L,
    val status: String = "",
    val target: String = "library",
)

@Serializable
data class LyraJobsResponse(
    val jobs: List<LyraJob> = emptyList(),
)

@Serializable
data class LyraJob(
    val id: Long = 0L,
    val kind: String = "",
    @SerialName("song_id") val songId: String = "",
    val status: String = "",
    val payload: JsonElement? = null,
    val result: JsonElement? = null,
    val error: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    val isActive: Boolean
        get() = status == "queued" || status == "pending" || status == "running"

    val isDone: Boolean
        get() = status == "done" || status == "succeeded" || status == "completed"

    val isFailed: Boolean
        get() = status == "failed" || status == "error"

    fun displayTitle(): String {
        val obj = payload as? JsonObject ?: return kind.ifBlank { "#$id" }
        val title = obj["title"]?.toString()?.trim('"').orEmpty()
        val albumTitle = obj["album_title"]?.toString()?.trim('"').orEmpty()
        val artist = obj["artist"]?.toString()?.trim('"').orEmpty()
        val primary = title.ifBlank { albumTitle }.ifBlank { kind.ifBlank { "#$id" } }
        return if (artist.isNotBlank() && title.isNotBlank()) "$primary — $artist" else primary
    }
}

@Serializable
data class LyraEventTicketResponse(
    val ticket: String,
    val scope: String = "yoin",
    @SerialName("expires_in") val expiresIn: Int = 60,
)

@Serializable
data class LyraEvent(
    val id: Long = 0L,
    val topic: String = "",
    val kind: String = "",
    @SerialName("entity_id") val entityId: String = "",
    val payload: JsonElement? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class LyraInboxListResponse(
    val items: List<LyraInboxItem> = emptyList(),
)

@Serializable
data class LyraInboxItem(
    val id: String = "",
    @SerialName("origin_kind") val originKind: String = "",
    @SerialName("origin_ref") val originRef: String = "",
    @SerialName("file_path") val filePath: String = "",
    val size: Long = 0L,
    @SerialName("duration_sec") val durationSec: Double? = null,
    val state: String = "",
    val error: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class LyraListenLaterListResponse(
    val items: List<LyraListenLaterItem> = emptyList(),
)

@Serializable
data class LyraListenLaterItem(
    val id: String = "",
    val kind: String = "",
    val ref: String = "",
    val title: String = "",
    val artist: String = "",
    val note: String = "",
    val state: String = "open",
)

@Serializable
data class LyraSharesListResponse(
    val shares: List<LyraShareView> = emptyList(),
)

@Serializable
data class LyraShareView(
    val token: String = "",
    val title: String = "",
    val description: String = "",
    val url: String = "",
    val state: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
data class LyraIncomingPullsResponse(
    val pulls: List<LyraIncomingPull> = emptyList(),
)

@Serializable
data class LyraIncomingPull(
    val id: String = "",
    @SerialName("manifest_url") val manifestUrl: String = "",
    val target: String = "library",
    val status: String = "",
    val error: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

// ── /api/v1/sync/* wire DTOs (matching Yoin SyncTransport 1:1) ────────────

@Serializable
data class LyraSyncAboutResponse(
    val permissionId: String,
    val email: String? = null,
    val displayName: String? = null,
)

@Serializable
data class LyraSyncFilesResponse(
    val files: List<LyraSyncFileDto> = emptyList(),
)

@Serializable
data class LyraSyncFileDto(
    val id: String,
    val name: String,
    val version: Long = 1L,
    val modifiedTime: Long? = null,
    val size: Long? = null,
    val appProperties: Map<String, String> = emptyMap(),
)

@Serializable
data class LyraSyncGeneratedIdsResponse(
    val ids: List<String> = emptyList(),
)
