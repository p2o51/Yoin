package com.gpo.yoin.data.model

data class Artist(
    val id: MediaId,
    val name: String,
    val albumCount: Int?,
    val coverArt: CoverRef?,
    val isStarred: Boolean = false,
)

data class ArtistDetail(
    val id: MediaId,
    val name: String,
    val albumCount: Int?,
    val coverArt: CoverRef?,
    val isStarred: Boolean = false,
    val albums: List<Album> = emptyList(),
)

/** A letter/group bucket for library browsing (e.g. "A", "#"). */
data class ArtistIndex(
    val name: String,
    val artists: List<Artist> = emptyList(),
)
