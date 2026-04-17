package com.gpo.yoin.data.source.subsonic

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.LyricLine
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.remote.Album as SubsonicAlbum
import com.gpo.yoin.data.remote.Artist as SubsonicArtist
import com.gpo.yoin.data.remote.ArtistDetail as SubsonicArtistDetail
import com.gpo.yoin.data.remote.ArtistIndex as SubsonicArtistIndex
import com.gpo.yoin.data.remote.LyricsList as SubsonicLyricsList
import com.gpo.yoin.data.remote.Playlist as SubsonicPlaylist
import com.gpo.yoin.data.remote.SearchResult as SubsonicSearchResult
import com.gpo.yoin.data.remote.Song as SubsonicSong
import com.gpo.yoin.data.remote.StarredResponse as SubsonicStarredResponse
import com.gpo.yoin.data.model.Playlist as NeutralPlaylist

internal fun SubsonicSong.toTrack(): Track {
    val extras = mutableMapOf<String, String>()
    bitRate?.let { extras[EXTRA_BIT_RATE] = it.toString() }
    size?.let { extras[EXTRA_SIZE] = it.toString() }
    contentType?.let { extras[EXTRA_CONTENT_TYPE] = it }
    suffix?.let { extras[EXTRA_SUFFIX] = it }
    path?.let { extras[EXTRA_PATH] = it }
    return Track(
        id = MediaId.subsonic(id),
        title = title,
        artist = artist,
        artistId = artistId?.let(MediaId::subsonic),
        album = album,
        albumId = albumId?.let(MediaId::subsonic),
        coverArt = coverArt?.let(CoverRef::SourceRelative),
        durationSec = duration,
        trackNumber = track,
        year = year,
        genre = genre,
        userRating = userRating,
        isStarred = !starred.isNullOrEmpty(),
        extras = extras,
    )
}

internal fun SubsonicAlbum.toAlbum(): Album = Album(
    id = MediaId.subsonic(id),
    name = name,
    artist = artist,
    artistId = artistId?.let(MediaId::subsonic),
    coverArt = (coverArt ?: id).let(CoverRef::SourceRelative),
    songCount = songCount,
    durationSec = duration,
    year = year,
    genre = genre,
    isStarred = !starred.isNullOrEmpty(),
    tracks = song.map { it.toTrack() },
)

internal fun SubsonicArtist.toArtist(): Artist = Artist(
    id = MediaId.subsonic(id),
    name = name,
    albumCount = albumCount,
    coverArt = coverArt?.let(CoverRef::SourceRelative),
    isStarred = !starred.isNullOrEmpty(),
)

internal fun SubsonicArtistDetail.toArtistDetail(): ArtistDetail = ArtistDetail(
    id = MediaId.subsonic(id),
    name = name,
    albumCount = albumCount,
    coverArt = coverArt?.let(CoverRef::SourceRelative),
    isStarred = !starred.isNullOrEmpty(),
    albums = album.map { it.toAlbum() },
)

internal fun SubsonicArtistIndex.toArtistIndex(): ArtistIndex = ArtistIndex(
    name = name,
    artists = artist.map { it.toArtist() },
)

internal fun SubsonicPlaylist.toPlaylist(): NeutralPlaylist = NeutralPlaylist(
    id = MediaId.subsonic(id),
    name = name,
    owner = owner,
    coverArt = id.let(CoverRef::SourceRelative),
    songCount = songCount,
    durationSec = duration,
    tracks = entry.map { it.toTrack() },
)

internal fun SubsonicSearchResult.toSearchResults(): SearchResults = SearchResults(
    tracks = song.map { it.toTrack() },
    albums = album.map { it.toAlbum() },
    artists = artist.map { it.toArtist() },
)

internal fun SubsonicStarredResponse.toStarred(): Starred = Starred(
    tracks = song.map { it.toTrack() },
    albums = album.map { it.toAlbum() },
    artists = artist.map { it.toArtist() },
)

internal fun SubsonicLyricsList.toLyrics(): Lyrics? {
    val structured = structuredLyrics.firstOrNull() ?: return null
    return if (structured.synced && structured.line.any { it.start != null }) {
        Lyrics.Synced(
            language = structured.lang,
            lines = structured.line.map { LyricLine(startMs = it.start ?: 0L, text = it.value) },
        )
    } else {
        Lyrics.Unsynced(
            language = structured.lang,
            text = structured.line.joinToString("\n") { it.value },
        )
    }
}

internal const val EXTRA_BIT_RATE = "subsonic.bitRate"
internal const val EXTRA_SIZE = "subsonic.size"
internal const val EXTRA_CONTENT_TYPE = "subsonic.contentType"
internal const val EXTRA_SUFFIX = "subsonic.suffix"
internal const val EXTRA_PATH = "subsonic.path"
