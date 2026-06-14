package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.local.SpotifyLibraryAlbumCache
import com.gpo.yoin.data.local.SpotifyLibraryArtistCache
import com.gpo.yoin.data.local.SpotifyLibraryPlaylistCache
import com.gpo.yoin.data.local.SpotifyLibraryTrackCache
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track

internal fun Track.toSpotifyLibraryTrackCache(
    profileId: String,
    cachedAt: Long,
    addedAt: String? = null,
    pendingFavoriteAction: Boolean = false,
    lastSyncError: String? = null,
): SpotifyLibraryTrackCache = SpotifyLibraryTrackCache(
    profileId = profileId,
    trackId = id.rawId,
    title = title,
    artist = artist,
    artistId = artistId?.rawId,
    album = album,
    albumId = albumId?.rawId,
    coverArtKey = CoverRef.toStorageKey(coverArt),
    durationSec = durationSec,
    addedAt = addedAt ?: this.addedAt,
    isSaved = isStarred,
    cachedAt = cachedAt,
    pendingFavoriteAction = pendingFavoriteAction,
    lastSyncError = lastSyncError,
)

internal fun SpotifyLibraryTrackCache.toTrack(): Track = Track(
    id = MediaId.spotify(trackId),
    title = title,
    artist = artist,
    artistId = artistId?.let(MediaId::spotify),
    album = album,
    albumId = albumId?.let(MediaId::spotify),
    coverArt = CoverRef.fromStorageKey(coverArtKey),
    durationSec = durationSec,
    trackNumber = null,
    year = null,
    genre = null,
    userRating = null,
    isStarred = isSaved,
    addedAt = addedAt,
)

internal fun Album.toSpotifyLibraryAlbumCache(
    profileId: String,
    cachedAt: Long,
    addedAt: String? = null,
): SpotifyLibraryAlbumCache = SpotifyLibraryAlbumCache(
    profileId = profileId,
    albumId = id.rawId,
    name = name,
    artist = artist,
    artistId = artistId?.rawId,
    coverArtKey = CoverRef.toStorageKey(coverArt),
    songCount = songCount,
    year = year,
    isSaved = isStarred,
    addedAt = addedAt ?: this.addedAt,
    cachedAt = cachedAt,
)

internal fun SpotifyLibraryAlbumCache.toAlbum(): Album = Album(
    id = MediaId.spotify(albumId),
    name = name,
    artist = artist,
    artistId = artistId?.let(MediaId::spotify),
    coverArt = CoverRef.fromStorageKey(coverArtKey),
    songCount = songCount,
    durationSec = null,
    year = year,
    genre = null,
    tracks = emptyList(),
    isStarred = isSaved,
    addedAt = addedAt,
)

internal fun Artist.toSpotifyLibraryArtistCache(
    profileId: String,
    cachedAt: Long,
): SpotifyLibraryArtistCache = SpotifyLibraryArtistCache(
    profileId = profileId,
    artistId = id.rawId,
    name = name,
    albumCount = albumCount,
    coverArtKey = CoverRef.toStorageKey(coverArt),
    isFollowed = isStarred,
    cachedAt = cachedAt,
)

internal fun SpotifyLibraryArtistCache.toArtist(): Artist = Artist(
    id = MediaId.spotify(artistId),
    name = name,
    albumCount = albumCount,
    coverArt = CoverRef.fromStorageKey(coverArtKey),
    isStarred = isFollowed,
)

internal fun Playlist.toSpotifyLibraryPlaylistCache(
    profileId: String,
    cachedAt: Long,
): SpotifyLibraryPlaylistCache = SpotifyLibraryPlaylistCache(
    profileId = profileId,
    playlistId = id.rawId,
    name = name,
    owner = owner,
    coverArtKey = CoverRef.toStorageKey(coverArt),
    songCount = songCount,
    durationSec = durationSec,
    canWrite = canWrite,
    snapshotId = snapshotId,
    cachedAt = cachedAt,
)

internal fun SpotifyLibraryPlaylistCache.toPlaylist(): Playlist = Playlist(
    id = MediaId.spotify(playlistId),
    name = name,
    owner = owner,
    coverArt = CoverRef.fromStorageKey(coverArtKey),
    songCount = songCount,
    durationSec = durationSec,
    tracks = emptyList(),
    canWrite = canWrite,
    snapshotId = snapshotId,
)

internal fun buildStarredFromCache(
    tracks: List<SpotifyLibraryTrackCache>,
    albums: List<SpotifyLibraryAlbumCache>,
    artists: List<SpotifyLibraryArtistCache>,
): Starred = Starred(
    tracks = tracks.filter { it.isSaved }.map { it.toTrack() },
    albums = albums.filter { it.isSaved }.map { it.toAlbum() },
    artists = artists.filter { it.isFollowed }.map { it.toArtist() },
)
