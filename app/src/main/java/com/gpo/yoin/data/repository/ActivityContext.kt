package com.gpo.yoin.data.repository

sealed interface ActivityContext {
    data object None : ActivityContext

    data class Album(
        val albumId: String,
        val albumName: String,
        val artistName: String? = null,
        val artistId: String? = null,
        val coverArtId: String? = null,
    ) : ActivityContext

    data class Artist(
        val artistId: String,
        val artistName: String,
        val coverArtId: String? = null,
    ) : ActivityContext
}
