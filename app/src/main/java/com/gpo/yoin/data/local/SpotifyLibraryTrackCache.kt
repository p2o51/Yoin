package com.gpo.yoin.data.local

import androidx.room.Entity

@Entity(
    tableName = "spotify_library_track_cache",
    primaryKeys = ["profileId", "trackId"],
)
data class SpotifyLibraryTrackCache(
    val profileId: String,
    val trackId: String,
    val title: String?,
    val artist: String?,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val coverArtKey: String?,
    val durationSec: Int?,
    val addedAt: String?,
    val isSaved: Boolean = true,
    val cachedAt: Long,
    val pendingFavoriteAction: Boolean = false,
    val lastSyncError: String? = null,
)
