package com.gpo.yoin.data.local

import androidx.room.Entity

@Entity(
    tableName = "spotify_library_artist_cache",
    primaryKeys = ["profileId", "artistId"],
)
data class SpotifyLibraryArtistCache(
    val profileId: String,
    val artistId: String,
    val name: String,
    val albumCount: Int?,
    val coverArtKey: String?,
    val isFollowed: Boolean = false,
    val cachedAt: Long,
)
