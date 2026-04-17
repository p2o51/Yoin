package com.gpo.yoin.data.model

data class Album(
    val id: MediaId,
    val name: String,
    val artist: String?,
    val artistId: MediaId?,
    val coverArt: CoverRef?,
    val songCount: Int?,
    val durationSec: Int?,
    val year: Int?,
    val genre: String?,
    val isStarred: Boolean = false,
    val tracks: List<Track> = emptyList(),
)
