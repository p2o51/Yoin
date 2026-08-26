package com.gpo.yoin.data.local

import androidx.room.Entity
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track

/**
 * Persisted candidate pools for the home "Jump Back In" widget grid, one row
 * per item, all providers. The pools are written pre-shuffled in final order
 * ([sortOrder]) at fetch time, so grid composition from them is deterministic:
 * the shelf looks identical across app restarts until the pools age out and
 * rotate — no per-open re-roll, no network on a warm open.
 *
 * [coverArtKey] is a storage key (URL for Spotify, raw id for Subsonic), never
 * a resolved URL — Subsonic resolved URLs embed a rotating token.
 */
@Entity(
    tableName = "home_grid_pool_cache",
    primaryKeys = ["profileId", "provider", "itemType", "itemId"],
)
data class HomeGridPoolCache(
    val profileId: String,
    val provider: String,
    val itemType: String,
    val itemId: String,
    val title: String,
    val subtitle: String?,
    val coverArtKey: String?,
    // Track rows only: enough to reconstruct a playable Track.
    val albumId: String?,
    val durationSec: Int?,
    val sortOrder: Int,
    val cachedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val TYPE_ALBUM = "album"
        const val TYPE_TRACK = "track"
        const val TYPE_PLAYLIST = "playlist"
    }
}

internal fun HomeGridPoolCache.toAlbum(): Album = Album(
    id = MediaId(provider, itemId),
    name = title,
    artist = subtitle,
    artistId = null,
    coverArt = CoverRef.fromStorageKey(coverArtKey),
    songCount = null,
    durationSec = null,
    year = null,
    genre = null,
)

internal fun HomeGridPoolCache.toTrack(): Track = Track(
    id = MediaId(provider, itemId),
    title = title,
    artist = subtitle,
    artistId = null,
    album = null,
    albumId = albumId?.let { MediaId(provider, it) },
    coverArt = CoverRef.fromStorageKey(coverArtKey),
    durationSec = durationSec,
    trackNumber = null,
    year = null,
    genre = null,
    userRating = null,
)

internal fun HomeGridPoolCache.toPlaylist(): Playlist = Playlist(
    id = MediaId(provider, itemId),
    name = title,
    owner = subtitle,
    coverArt = CoverRef.fromStorageKey(coverArtKey),
    songCount = null,
    durationSec = null,
)

internal fun Album.toGridPoolRow(
    profileId: String,
    sortOrder: Int,
    cachedAt: Long,
): HomeGridPoolCache = HomeGridPoolCache(
    profileId = profileId,
    provider = id.provider,
    itemType = HomeGridPoolCache.TYPE_ALBUM,
    itemId = id.rawId,
    title = name,
    subtitle = artist,
    coverArtKey = CoverRef.toStorageKey(coverArt),
    albumId = null,
    durationSec = null,
    sortOrder = sortOrder,
    cachedAt = cachedAt,
)

internal fun Track.toGridPoolRow(
    profileId: String,
    sortOrder: Int,
    cachedAt: Long,
): HomeGridPoolCache = HomeGridPoolCache(
    profileId = profileId,
    provider = id.provider,
    itemType = HomeGridPoolCache.TYPE_TRACK,
    itemId = id.rawId,
    title = title.orEmpty(),
    subtitle = artist,
    coverArtKey = CoverRef.toStorageKey(coverArt),
    albumId = albumId?.rawId,
    durationSec = durationSec,
    sortOrder = sortOrder,
    cachedAt = cachedAt,
)

internal fun Playlist.toGridPoolRow(
    profileId: String,
    sortOrder: Int,
    cachedAt: Long,
): HomeGridPoolCache = HomeGridPoolCache(
    profileId = profileId,
    provider = id.provider,
    itemType = HomeGridPoolCache.TYPE_PLAYLIST,
    itemId = id.rawId,
    title = name,
    subtitle = owner,
    coverArtKey = CoverRef.toStorageKey(coverArt),
    albumId = null,
    durationSec = null,
    sortOrder = sortOrder,
    cachedAt = cachedAt,
)
