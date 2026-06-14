package com.gpo.yoin.data.local

import androidx.room.Entity

@Entity(
    tableName = "spotify_library_album_cache",
    primaryKeys = ["profileId", "albumId"],
)
data class SpotifyLibraryAlbumCache(
    val profileId: String,
    val albumId: String,
    val name: String,
    val artist: String?,
    val artistId: String?,
    val coverArtKey: String?,
    val songCount: Int?,
    val year: Int?,
    val isSaved: Boolean = true,
    val addedAt: String?,
    val cachedAt: Long,
)
