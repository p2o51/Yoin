package com.gpo.yoin.data.local

import androidx.room.Entity

@Entity(
    tableName = "spotify_library_playlist_cache",
    primaryKeys = ["profileId", "playlistId"],
)
data class SpotifyLibraryPlaylistCache(
    val profileId: String,
    val playlistId: String,
    val name: String,
    val owner: String?,
    val coverArtKey: String?,
    val songCount: Int?,
    val durationSec: Int?,
    val canWrite: Boolean = false,
    val snapshotId: String?,
    val cachedAt: Long,
)
