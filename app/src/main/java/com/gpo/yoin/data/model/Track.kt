package com.gpo.yoin.data.model

data class Track(
    val id: MediaId,
    val title: String?,
    val artist: String?,
    val artistId: MediaId?,
    val album: String?,
    val albumId: MediaId?,
    val coverArt: CoverRef?,
    val durationSec: Int?,
    val trackNumber: Int?,
    val year: Int?,
    val genre: String?,
    val userRating: Int?,
    val isStarred: Boolean = false,
    val extras: Map<String, String> = emptyMap(),
)
