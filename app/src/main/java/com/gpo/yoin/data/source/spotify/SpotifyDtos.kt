package com.gpo.yoin.data.source.spotify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpotifyImageObject(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class SpotifyExternalUrls(
    val spotify: String? = null,
)

@Serializable
data class SpotifyOwnerObject(
    val id: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class SpotifySimplifiedArtistObject(
    val id: String,
    val name: String,
    val uri: String? = null,
    @SerialName("external_urls") val externalUrls: SpotifyExternalUrls? = null,
)

@Serializable
data class SpotifyArtistObject(
    val id: String,
    val name: String,
    val uri: String? = null,
    val images: List<SpotifyImageObject> = emptyList(),
    @SerialName("external_urls") val externalUrls: SpotifyExternalUrls? = null,
)

@Serializable
data class SpotifySimplifiedAlbumObject(
    val id: String,
    val name: String,
    val uri: String? = null,
    val artists: List<SpotifySimplifiedArtistObject> = emptyList(),
    val images: List<SpotifyImageObject> = emptyList(),
    @SerialName("total_tracks") val totalTracks: Int? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("album_type") val albumType: String? = null,
)

@Serializable
data class SpotifyTrackObject(
    val id: String? = null,
    val name: String,
    val uri: String? = null,
    val artists: List<SpotifySimplifiedArtistObject> = emptyList(),
    val album: SpotifySimplifiedAlbumObject? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
)

@Serializable
data class SpotifyAlbumObject(
    val id: String,
    val name: String,
    val uri: String? = null,
    val artists: List<SpotifySimplifiedArtistObject> = emptyList(),
    val images: List<SpotifyImageObject> = emptyList(),
    @SerialName("total_tracks") val totalTracks: Int? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("album_type") val albumType: String? = null,
    val tracks: SpotifyPagingObject<SpotifyTrackObject>? = null,
)

@Serializable
data class SpotifyPlaylistTracksObject(
    val total: Int? = null,
    val items: List<SpotifyPlaylistItemObject> = emptyList(),
    val next: String? = null,
)

@Serializable
data class SpotifyPlaylistObject(
    val id: String,
    val name: String,
    val uri: String? = null,
    val description: String? = null,
    val images: List<SpotifyImageObject> = emptyList(),
    val owner: SpotifyOwnerObject? = null,
    val public: Boolean? = null,
    val tracks: SpotifyPlaylistTracksObject? = null,
    /** Optimistic-concurrency token returned by `/playlists/{id}` and mutations. */
    @SerialName("snapshot_id") val snapshotId: String? = null,
)

@Serializable
data class SpotifyPlaylistItemObject(
    val track: SpotifyTrackObject? = null,
)

@Serializable
data class SpotifySavedTrackObject(
    @SerialName("added_at") val addedAt: String? = null,
    val track: SpotifyTrackObject? = null,
)

@Serializable
data class SpotifySavedAlbumObject(
    @SerialName("added_at") val addedAt: String? = null,
    val album: SpotifyAlbumObject? = null,
)

@Serializable
data class SpotifyPagingObject<T>(
    val items: List<T> = emptyList(),
    val next: String? = null,
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)

@Serializable
data class SpotifyCursorPageCursors(
    val after: String? = null,
    val before: String? = null,
)

@Serializable
data class SpotifyCursorPagingObject<T>(
    val items: List<T> = emptyList(),
    val next: String? = null,
    val total: Int? = null,
    val limit: Int? = null,
    val cursors: SpotifyCursorPageCursors? = null,
)

@Serializable
data class SpotifyFollowedArtistsResponse(
    val artists: SpotifyCursorPagingObject<SpotifyArtistObject>,
)

@Serializable
data class SpotifySearchResponse(
    val tracks: SpotifyPagingObject<SpotifyTrackObject>? = null,
    val albums: SpotifyPagingObject<SpotifySimplifiedAlbumObject>? = null,
    val artists: SpotifyPagingObject<SpotifyArtistObject>? = null,
    val playlists: SpotifyPagingObject<SpotifyPlaylistObject>? = null,
)

@Serializable
data class SpotifyDevicesResponse(
    val devices: List<SpotifyDevice> = emptyList(),
)

@Serializable
data class SpotifyDevice(
    val id: String? = null,
    val name: String,
    val type: String,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_private_session") val isPrivateSession: Boolean = false,
    @SerialName("is_restricted") val isRestricted: Boolean = false,
    @SerialName("volume_percent") val volumePercent: Int? = null,
)

// ── Playlist write request / response bodies ────────────────────────────
//
// Sent to / returned by `POST /v1/me/playlists`, `PUT /v1/playlists/{id}`,
// `POST /v1/playlists/{id}/items`, `DELETE /v1/playlists/{id}/items`.

@Serializable
data class SpotifyCreatePlaylistRequest(
    val name: String,
    // `public` has no Kotlin default — its Spotify server-side default is
    // `true`, so omitting it would silently create public playlists. Callers
    // must pass the intended value explicitly.
    val public: Boolean,
    val description: String? = null,
)

@Serializable
data class SpotifyRenamePlaylistRequest(
    val name: String,
    val description: String? = null,
)

@Serializable
data class SpotifyAddTracksRequest(
    val uris: List<String>,
)

@Serializable
data class SpotifyRemoveTrackItem(
    val uri: String,
    val positions: List<Int>? = null,
)

@Serializable
data class SpotifyRemoveTracksRequest(
    val tracks: List<SpotifyRemoveTrackItem>,
    @SerialName("snapshot_id") val snapshotId: String? = null,
)

@Serializable
data class SpotifySnapshotResponse(
    @SerialName("snapshot_id") val snapshotId: String,
)

@Serializable
data class SpotifyTransferPlaybackRequest(
    @SerialName("device_ids") val deviceIds: List<String>,
    val play: Boolean,
)
