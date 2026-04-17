package com.gpo.yoin.data.model

data class Playlist(
    val id: MediaId,
    val name: String,
    val owner: String?,
    val coverArt: CoverRef?,
    val songCount: Int?,
    val durationSec: Int?,
    val tracks: List<Track> = emptyList(),
)
