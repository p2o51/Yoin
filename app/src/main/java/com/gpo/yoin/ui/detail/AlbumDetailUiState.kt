package com.gpo.yoin.ui.detail

sealed interface AlbumDetailUiState {
    data object Loading : AlbumDetailUiState

    data class Content(
        val albumId: String,
        val albumName: String,
        val artistName: String,
        val artistId: String?,
        val coverArtId: String?,
        val coverArtUrl: String?,
        val year: Int?,
        val songCount: Int?,
        val totalDuration: Int?,
        val songs: List<AlbumSong>,
    ) : AlbumDetailUiState

    data class Error(val message: String) : AlbumDetailUiState
}

data class AlbumSong(
    val id: String,
    val title: String,
    val artist: String,
    val trackNumber: Int?,
    val duration: Int?,
    val isStarred: Boolean,
)
