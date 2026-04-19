package com.gpo.yoin.data.local

import androidx.room.Entity

@Entity(
    tableName = "spotify_home_album_cache",
    primaryKeys = ["profileId", "albumId"],
)
data class SpotifyHomeAlbumCache(
    val profileId: String,
    val albumId: String,
    val name: String,
    val artist: String?,
    val artistId: String?,
    val coverArtKey: String?,
    val songCount: Int?,
    val year: Int?,
    val sortOrder: Int,
    val cachedAt: Long = System.currentTimeMillis(),
)
