package com.gpo.yoin.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubsonicResponse(
    @SerialName("subsonic-response") val subsonicResponse: SubsonicResponseBody,
)

@Serializable
data class SubsonicResponseBody(
    val status: String,
    val version: String? = null,
    val error: SubsonicError? = null,
    val album: Album? = null,
    val artist: ArtistDetail? = null,
    val artists: ArtistsResponse? = null,
    val albumList2: AlbumList2Response? = null,
    val searchResult3: SearchResult? = null,
    val starred2: StarredResponse? = null,
    val randomSongs: RandomSongsResponse? = null,
    val playlists: PlaylistsResponse? = null,
    val playlist: Playlist? = null,
    val lyricsList: LyricsList? = null,
)

@Serializable
data class SubsonicError(
    val code: Int,
    val message: String? = null,
)

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val starred: String? = null,
)

@Serializable
data class ArtistDetail(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val starred: String? = null,
    val album: List<Album> = emptyList(),
)

@Serializable
data class ArtistIndex(
    val name: String,
    val artist: List<Artist> = emptyList(),
)

@Serializable
data class ArtistsResponse(
    val index: List<ArtistIndex> = emptyList(),
)

@Serializable
data class Album(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val starred: String? = null,
    val song: List<Song> = emptyList(),
)

@Serializable
data class AlbumList2Response(
    val album: List<Album> = emptyList(),
)

@Serializable
data class Song(
    val id: String,
    val parent: String? = null,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val track: Int? = null,
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
)

@Serializable
data class SearchResult(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

@Serializable
data class StarredResponse(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

@Serializable
data class RandomSongsResponse(
    val song: List<Song> = emptyList(),
)

@Serializable
data class PlaylistsResponse(
    val playlist: List<Playlist> = emptyList(),
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val owner: String? = null,
    @SerialName("public") val isPublic: Boolean? = null,
    val comment: String? = null,
    val created: String? = null,
    val changed: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val entry: List<Song> = emptyList(),
)

@Serializable
data class LyricsList(
    val structuredLyrics: List<StructuredLyrics> = emptyList(),
)

@Serializable
data class StructuredLyrics(
    val lang: String? = null,
    val synced: Boolean = false,
    val line: List<SyncedLine> = emptyList(),
)

@Serializable
data class SyncedLine(
    val start: Long? = null,
    val value: String = "",
)
