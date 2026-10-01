package com.gpo.yoin.data.repository

import com.gpo.yoin.data.local.ArtistSongPlayAggregate

/**
 * One profile's own listening for an artist, from local play history:
 * [playCount] plays in total, the latest at [lastPlayedAt], and the
 * most-played songs first in [topSongs].
 */
data class ArtistListening(
    val playCount: Int,
    val lastPlayedAt: Long?,
    val topSongs: List<ArtistSongPlayAggregate>,
)
