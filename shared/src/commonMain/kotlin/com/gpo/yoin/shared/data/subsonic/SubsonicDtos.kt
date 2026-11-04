package com.gpo.yoin.shared.data.subsonic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire shapes of the Subsonic / OpenSubsonic JSON API. Ported from the Android
 * app's data/remote/SubsonicModels.kt (same field names and defaults), plus a
 * few fields Android doesn't read yet (discNumber, playCount, userRating on
 * albums, playlist coverArt, structured-lyrics offset, plain getLyrics).
 * Internal: Swift only sees the neutral models in data.model.
 */

@Serializable
internal data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicBody,
)

@Serializable
internal data class SubsonicBody(
    val status: String,
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean? = null,
    val error: SubsonicErrorDto? = null,
    val album: AlbumDto? = null,
    val artist: ArtistDetailDto? = null,
    val artists: ArtistsDto? = null,
    val albumList2: AlbumListDto? = null,
    val searchResult3: SearchResultDto? = null,
    val starred2: StarredDto? = null,
    val randomSongs: SongListDto? = null,
    val song: SongDto? = null,
    val playlists: PlaylistsDto? = null,
    val playlist: PlaylistDto? = null,
    val lyricsList: LyricsListDto? = null,
    val lyrics: PlainLyricsDto? = null,
    val openSubsonicExtensions: List<OpenSubsonicExtensionDto>? = null,
)

@Serializable
internal data class OpenSubsonicExtensionDto(
    val name: String,
    val versions: List<Int>? = null,
)

@Serializable
internal data class SubsonicErrorDto(
    val code: Int,
    val message: String? = null,
)

@Serializable
internal data class ArtistDto(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val starred: String? = null,
)

@Serializable
internal data class ArtistDetailDto(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val starred: String? = null,
    val album: List<AlbumDto> = emptyList(),
)

@Serializable
internal data class ArtistIndexDto(
    val name: String,
    val artist: List<ArtistDto> = emptyList(),
)

@Serializable
internal data class ArtistsDto(
    val index: List<ArtistIndexDto> = emptyList(),
)

@Serializable
internal data class AlbumDto(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val playCount: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val starred: String? = null,
    val userRating: Int? = null,
    val created: String? = null,
    /** OpenSubsonic (Navidrome): when the user last played it, ISO-8601. */
    val played: String? = null,
    val song: List<SongDto> = emptyList(),
    val releaseTypes: List<String> = emptyList(),
)

@Serializable
internal data class AlbumListDto(
    val album: List<AlbumDto> = emptyList(),
)

@Serializable
internal data class SongDto(
    val id: String,
    val parent: String? = null,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val path: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val starred: String? = null,
    val userRating: Int? = null,
    val playCount: Int? = null,
    /** OpenSubsonic (Navidrome): when the user last played it, ISO-8601. */
    val played: String? = null,
)

@Serializable
internal data class SearchResultDto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<SongDto> = emptyList(),
)

@Serializable
internal data class StarredDto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<SongDto> = emptyList(),
)

@Serializable
internal data class SongListDto(
    val song: List<SongDto> = emptyList(),
)

@Serializable
internal data class PlaylistsDto(
    val playlist: List<PlaylistDto> = emptyList(),
)

@Serializable
internal data class PlaylistDto(
    val id: String,
    val name: String,
    val owner: String? = null,
    @SerialName("public") val isPublic: Boolean? = null,
    @SerialName("readonly") val isReadOnly: Boolean? = null,
    val comment: String? = null,
    val coverArt: String? = null,
    val created: String? = null,
    val changed: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val entry: List<SongDto> = emptyList(),
)

@Serializable
internal data class LyricsListDto(
    val structuredLyrics: List<StructuredLyricsDto> = emptyList(),
)

@Serializable
internal data class StructuredLyricsDto(
    val lang: String? = null,
    val synced: Boolean = false,
    /** OpenSubsonic: milliseconds; positive shows lines sooner. */
    val offset: Long? = null,
    val line: List<SyncedLineDto> = emptyList(),
)

@Serializable
internal data class SyncedLineDto(
    val start: Long? = null,
    val value: String = "",
)

/** Classic `getLyrics` (artist + title): one plain text blob. */
@Serializable
internal data class PlainLyricsDto(
    val artist: String? = null,
    val title: String? = null,
    val value: String? = null,
)
