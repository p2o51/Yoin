package com.gpo.yoin.data.remote

import java.util.Locale
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
    /** `getSong`: one song. */
    val song: Song? = null,
    val artist: ArtistDetail? = null,
    val artists: ArtistsResponse? = null,
    val albumList2: AlbumList2Response? = null,
    val searchResult3: SearchResult? = null,
    val starred2: StarredResponse? = null,
    val randomSongs: RandomSongsResponse? = null,
    val playlists: PlaylistsResponse? = null,
    val playlist: Playlist? = null,
    val lyricsList: LyricsList? = null,
    val shares: SharesResponse? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean? = null,
    val openSubsonicExtensions: List<OpenSubsonicExtension> = emptyList(),
)

/**
 * What the server calls itself, as its account page shows it: OpenSubsonic `type` with each word
 * capitalised, then `serverVersion` as sent ("Navidrome 0.64.2"). Null from a plain Subsonic
 * server, which sends neither.
 */
val SubsonicResponseBody.serverName: String?
    get() {
        val name = type.orEmpty().split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
        return listOf(name, serverVersion.orEmpty().trim()).filter { it.isNotEmpty() }
            .joinToString(" ").takeIf { it.isNotEmpty() }
    }

/** `createShare` / `getShares`: the server's share links. */
@Serializable
data class SharesResponse(
    val share: List<Share> = emptyList(),
)

/** One share link: [url] opens it in a browser, no account needed. */
@Serializable
data class Share(
    val id: String,
    val url: String,
    val description: String? = null,
    val expires: String? = null,
)

/** One entry of OpenSubsonic `getOpenSubsonicExtensions`: an extension and the versions the server speaks. */
@Serializable
data class OpenSubsonicExtension(
    val name: String,
    val versions: List<Int> = emptyList(),
)

/** OpenSubsonic `ArtistID3` as a song's `artists` / `albumArtists` entry. */
@Serializable
data class ArtistRef(
    val id: String? = null,
    val name: String,
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
    /** When the album was added to the server (AlbumID3.created, required by the API). */
    val created: String? = null,
    val song: List<Song> = emptyList(),
    /** OpenSubsonic (Navidrome ≥0.53): e.g. ["Album"], ["EP"], ["Single"], ["Compilation"]. */
    val releaseTypes: List<String> = emptyList(),
    /** OpenSubsonic: the release's version (ALBUMVERSION), e.g. "Deluxe"; absent on older servers. */
    val version: String? = null,
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
    // OpenSubsonic extensions; absent on plain Subsonic servers.
    val samplingRate: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    val discNumber: Int? = null,
    val artists: List<ArtistRef> = emptyList(),
    val displayArtist: String? = null,
    val displayAlbumArtist: String? = null,
    val playCount: Long? = null,
    val bpm: Int? = null,
    val comment: String? = null,
    /** When the file was added to the library (ISO-8601). */
    val created: String? = null,
    val displayComposer: String? = null,
    val replayGain: SubsonicReplayGain? = null,
)

/** OpenSubsonic `ReplayGain`: gains in dB, peaks linear; every field optional. */
@Serializable
data class SubsonicReplayGain(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
    val baseGain: Float? = null,
    val fallbackGain: Float? = null,
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
    @SerialName("readonly") val isReadOnly: Boolean? = null,
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
