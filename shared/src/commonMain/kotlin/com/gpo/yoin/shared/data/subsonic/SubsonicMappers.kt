package com.gpo.yoin.shared.data.subsonic

import com.gpo.yoin.shared.data.lyrics.LrcParser
import com.gpo.yoin.shared.data.model.AlbumInfo
import com.gpo.yoin.shared.data.model.ArtistIndex
import com.gpo.yoin.shared.data.model.ArtistInfo
import com.gpo.yoin.shared.data.model.LyricLine
import com.gpo.yoin.shared.data.model.Lyrics
import com.gpo.yoin.shared.data.model.MediaRef
import com.gpo.yoin.shared.data.model.PlaylistDetail
import com.gpo.yoin.shared.data.model.PlaylistSummary
import com.gpo.yoin.shared.data.model.SearchResult
import com.gpo.yoin.shared.data.model.SongInfo
import com.gpo.yoin.shared.data.model.Starred

/*
 * Wire → neutral. Same semantics as the Android app's
 * data/source/subsonic/SubsonicMappers.kt: `starred` is "non-empty
 * timestamp", album and playlist art fall back to their own id, ratings stay
 * raw, dates stay the server's ISO strings, playlist canWrite / ownedByMe use
 * the same ownership rule.
 */

private fun ref(rawId: String) = MediaRef(MediaRef.PROVIDER_SUBSONIC, rawId)

internal fun SongDto.toSongInfo(): SongInfo = SongInfo(
    ref = ref(id),
    id = id,
    title = title,
    artist = artist,
    artistId = artistId,
    album = album,
    albumId = albumId,
    track = track,
    disc = discNumber,
    durationSec = duration,
    coverArtId = coverArt,
    year = year,
    genre = genre,
    starred = !starred.isNullOrEmpty(),
    starredAt = starred?.takeIf { it.isNotEmpty() },
    userRating = userRating,
    suffix = suffix,
    contentType = contentType,
    bitRate = bitRate,
    size = size,
    path = path,
    playCount = playCount,
    played = played?.takeIf { it.isNotEmpty() },
)

internal fun AlbumDto.toAlbumInfo(): AlbumInfo = AlbumInfo(
    ref = ref(id),
    id = id,
    name = name,
    artist = artist,
    artistId = artistId,
    year = year,
    songCount = songCount,
    durationSec = duration,
    coverArtId = coverArt ?: id,
    starred = !starred.isNullOrEmpty(),
    starredAt = starred?.takeIf { it.isNotEmpty() },
    userRating = userRating,
    playCount = playCount,
    created = created,
    genre = genre,
    releaseType = subsonicReleaseType(releaseTypes),
    songs = song.map { it.toSongInfo() },
    played = played?.takeIf { it.isNotEmpty() },
)

/** OpenSubsonic `releaseTypes` → lower-case type; the first recognised value wins (Android: `subsonicReleaseType`). */
internal fun subsonicReleaseType(releaseTypes: List<String>): String? =
    releaseTypes.firstNotNullOfOrNull { type ->
        when (val t = type.trim().lowercase()) {
            "album", "ep", "single", "compilation" -> t
            else -> null
        }
    }

internal fun ArtistDto.toArtistInfo(): ArtistInfo = ArtistInfo(
    ref = ref(id),
    id = id,
    name = name,
    albumCount = albumCount,
    coverArtId = coverArt,
    starred = !starred.isNullOrEmpty(),
    starredAt = starred?.takeIf { it.isNotEmpty() },
    albums = emptyList(),
)

internal fun ArtistDetailDto.toArtistInfo(): ArtistInfo = ArtistInfo(
    ref = ref(id),
    id = id,
    name = name,
    albumCount = albumCount,
    coverArtId = coverArt,
    starred = !starred.isNullOrEmpty(),
    starredAt = starred?.takeIf { it.isNotEmpty() },
    albums = album.map { it.toAlbumInfo() },
)

internal fun ArtistIndexDto.toArtistIndex(): ArtistIndex = ArtistIndex(
    name = name,
    artists = artist.map { it.toArtistInfo() },
)

internal fun PlaylistDto.toPlaylistSummary(currentUsername: String?): PlaylistSummary = PlaylistSummary(
    ref = ref(id),
    id = id,
    name = name,
    owner = owner,
    isPublic = isPublic,
    comment = comment,
    coverArtId = coverArt ?: id,
    songCount = songCount,
    durationSec = duration,
    created = created,
    changed = changed,
    // getPlaylists returns public playlists of other users too. An explicit
    // OpenSubsonic readonly flag wins; legacy servers may omit owner/readonly.
    canWrite = isReadOnly != true && (owner.isNullOrBlank() || owner == currentUsername),
    // Ownership, not access; servers may echo the login name in another case.
    ownedByMe = owner?.takeUnless(String::isBlank)?.let { playlistOwner ->
        currentUsername?.let { playlistOwner.equals(it, ignoreCase = true) }
    },
)

internal fun PlaylistDto.toPlaylistDetail(currentUsername: String?): PlaylistDetail = PlaylistDetail(
    summary = toPlaylistSummary(currentUsername),
    entries = entry.map { it.toSongInfo() },
)

internal fun SearchResultDto.toSearchResult(): SearchResult = SearchResult(
    artists = artist.map { it.toArtistInfo() },
    albums = album.map { it.toAlbumInfo() },
    songs = song.map { it.toSongInfo() },
)

internal fun StarredDto.toStarred(): Starred = Starred(
    artists = artist.map { it.toArtistInfo() },
    albums = album.map { it.toAlbumInfo() },
    songs = song.map { it.toSongInfo() },
)

/**
 * OpenSubsonic structured lyrics → [Lyrics]. Android's rule: the first entry;
 * synced when it says so and at least one line has a start, lines sorted by
 * time (servers may send them out of order). Additionally applies the
 * entry's `offset` (positive = sooner), clamped at 0.
 */
internal fun LyricsListDto.toLyrics(): Lyrics? {
    val structured = structuredLyrics.firstOrNull() ?: return null
    if (structured.line.isEmpty()) return null
    return if (structured.synced && structured.line.any { it.start != null }) {
        val offset = structured.offset ?: 0L
        Lyrics(
            synced = true,
            language = structured.lang,
            lines = structured.line
                .map { LyricLine(timeMs = ((it.start ?: 0L) - offset).coerceAtLeast(0L), text = it.value) }
                .sortedBy { it.timeMs },
        )
    } else {
        Lyrics(
            synced = false,
            language = structured.lang,
            lines = structured.line.map { LyricLine(timeMs = 0L, text = it.value) },
        )
    }
}

/** Classic `getLyrics`: the value may itself be LRC, so run it through [LrcParser]. */
internal fun PlainLyricsDto.toLyrics(): Lyrics? {
    val raw = value?.takeIf { it.isNotBlank() } ?: return null
    return LrcParser.parse(raw)
}
