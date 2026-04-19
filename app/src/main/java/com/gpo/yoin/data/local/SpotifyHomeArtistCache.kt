package com.gpo.yoin.data.local

import androidx.room.Entity

@Entity(
    tableName = "spotify_home_artist_cache",
    primaryKeys = ["profileId", "artistId"],
)
data class SpotifyHomeArtistCache(
    val profileId: String,
    val artistId: String,
    val name: String,
    val coverArtKey: String?,
    val sortOrder: Int,
    val cachedAt: Long = System.currentTimeMillis(),
)
