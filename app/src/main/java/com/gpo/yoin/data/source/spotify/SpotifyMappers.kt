package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track

internal fun SpotifyTrackObject.toTrack(
    savedTrackIds: Set<String> = emptySet(),
    albumOverride: SpotifySimplifiedAlbumObject? = album,
): Track {
    val trackId = requireNotNull(id) { "Spotify track is missing id" }
    val primaryArtist = artists.firstOrNull()
    return Track(
        id = MediaId.spotify(trackId),
        title = name,
        artist = primaryArtist?.name,
        artistId = primaryArtist?.id?.let(MediaId::spotify),
        album = albumOverride?.name,
        albumId = albumOverride?.id?.let(MediaId::spotify),
        coverArt = albumOverride?.bestImageUrl()?.let(CoverRef::Url),
        durationSec = durationMs?.let { (it / 1_000L).toInt() },
        trackNumber = trackNumber,
        year = albumOverride?.releaseYear(),
        genre = null,
        userRating = null,
        isStarred = trackId in savedTrackIds,
    )
}

internal fun SpotifyAlbumObject.toAlbum(
    savedAlbumIds: Set<String> = emptySet(),
    savedTrackIds: Set<String> = emptySet(),
    tracksOverride: List<SpotifyTrackObject> = tracks?.items.orEmpty(),
): Album = Album(
    id = MediaId.spotify(id),
    name = name,
    artist = artists.firstOrNull()?.name,
    artistId = artists.firstOrNull()?.id?.let(MediaId::spotify),
    coverArt = bestImageUrl()?.let(CoverRef::Url),
    songCount = totalTracks,
    durationSec = tracksOverride.sumOf { track -> track.durationMs ?: 0L }
        .takeIf { it > 0L }
        ?.let { (it / 1_000L).toInt() },
    year = releaseYear(),
    genre = null,
    isStarred = id in savedAlbumIds,
    tracks = tracksOverride.map { track ->
        track.toTrack(
            savedTrackIds = savedTrackIds,
            albumOverride = toSimplifiedAlbum(),
        )
    },
)

internal fun SpotifySimplifiedAlbumObject.toAlbum(
    savedAlbumIds: Set<String> = emptySet(),
): Album = Album(
    id = MediaId.spotify(id),
    name = name,
    artist = artists.firstOrNull()?.name,
    artistId = artists.firstOrNull()?.id?.let(MediaId::spotify),
    coverArt = bestImageUrl()?.let(CoverRef::Url),
    songCount = totalTracks,
    durationSec = null,
    year = releaseYear(),
    genre = null,
    isStarred = id in savedAlbumIds,
)

internal fun SpotifyArtistObject.toArtist(
    isStarred: Boolean = false,
    albumCount: Int? = null,
): Artist = Artist(
    id = MediaId.spotify(id),
    name = name,
    albumCount = albumCount,
    coverArt = bestImageUrl()?.let(CoverRef::Url),
    isStarred = isStarred,
)

internal fun SpotifyArtistObject.toArtistDetail(
    albums: List<Album>,
    isStarred: Boolean = false,
): ArtistDetail = ArtistDetail(
    id = MediaId.spotify(id),
    name = name,
    albumCount = albums.size,
    coverArt = bestImageUrl()?.let(CoverRef::Url),
    isStarred = isStarred,
    albums = albums,
)

internal fun List<Artist>.toArtistIndices(): List<ArtistIndex> =
    filter { artist -> artist.name.isNotBlank() }
        .sortedBy { artist -> artist.name.lowercase() }
        .groupBy { artist ->
            artist.name.firstOrNull()?.uppercaseChar()?.takeIf(Char::isLetter)?.toString() ?: "#"
        }
        .toSortedMap()
        .map { (name, artists) -> ArtistIndex(name = name, artists = artists) }

/**
 * @param canWrite Pass `true` only when the current profile user owns this
 *   playlist. Source is responsible for comparing `owner.id` against
 *   `apiClient.getCurrentUserId()`; the mapper does not reach out to the
 *   network.
 */
internal fun SpotifyPlaylistObject.toPlaylist(
    tracks: List<Track> = emptyList(),
    canWrite: Boolean = false,
): Playlist = Playlist(
    id = MediaId.spotify(id),
    name = name,
    owner = owner?.displayName ?: owner?.id,
    coverArt = bestImageUrl()?.let(CoverRef::Url),
    songCount = tracks.size.takeIf { it > 0 } ?: this.tracks?.total,
    durationSec = tracks.sumOf { track -> track.durationSec ?: 0 }
        .takeIf { it > 0 },
    tracks = tracks,
    canWrite = canWrite,
    snapshotId = snapshotId,
)

internal fun List<SpotifyPlaylistItemObject>.toTracksWithPlaylistOffsets(
    savedTrackIds: Set<String> = emptySet(),
): List<Pair<Int, Track>> = mapIndexedNotNull { rawOffset, item ->
    val track = item.track ?: return@mapIndexedNotNull null
    runCatching { track.toTrack(savedTrackIds = savedTrackIds) }
        .getOrNull()
        ?.let { rawOffset to it }
}

internal fun SpotifySearchResponse.toSearchResults(
    savedTrackIds: Set<String> = emptySet(),
    savedAlbumIds: Set<String> = emptySet(),
    followedArtistIds: Set<String> = emptySet(),
): SearchResults = SearchResults(
    tracks = tracks?.items.orEmpty().map { it.toTrack(savedTrackIds = savedTrackIds) },
    albums = albums?.items.orEmpty().map { it.toAlbum(savedAlbumIds = savedAlbumIds) },
    artists = artists?.items.orEmpty().map { artist ->
        artist.toArtist(isStarred = artist.id in followedArtistIds)
    },
)

internal fun toStarred(
    tracks: List<Track>,
    albums: List<Album>,
    artists: List<Artist>,
): Starred = Starred(
    tracks = tracks.filter(Track::isStarred),
    albums = albums.filter(Album::isStarred),
    artists = artists.filter(Artist::isStarred),
)

internal fun SpotifyAlbumObject.toSimplifiedAlbum(): SpotifySimplifiedAlbumObject =
    SpotifySimplifiedAlbumObject(
        id = id,
        name = name,
        uri = uri,
        artists = artists,
        images = images,
        totalTracks = totalTracks,
        releaseDate = releaseDate,
        albumType = albumType,
    )

internal fun SpotifySimplifiedAlbumObject.releaseYear(): Int? =
    releaseDate?.take(4)?.toIntOrNull()

internal fun SpotifyAlbumObject.releaseYear(): Int? =
    releaseDate?.take(4)?.toIntOrNull()

internal fun SpotifySimplifiedAlbumObject.bestImageUrl(): String? = images.firstOrNull()?.url

internal fun SpotifyAlbumObject.bestImageUrl(): String? = images.firstOrNull()?.url

internal fun SpotifyArtistObject.bestImageUrl(): String? = images.firstOrNull()?.url

internal fun SpotifyPlaylistObject.bestImageUrl(): String? = images.firstOrNull()?.url
