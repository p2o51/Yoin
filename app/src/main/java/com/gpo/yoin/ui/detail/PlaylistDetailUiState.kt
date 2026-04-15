package com.gpo.yoin.ui.detail

sealed interface PlaylistDetailUiState {
    data object Loading : PlaylistDetailUiState

    data class Content(
        val playlistId: String,
        val playlistName: String,
        val owner: String,
        val comment: String?,
        val isPublic: Boolean?,
        val songCount: Int?,
        val totalDuration: Int?,
        val coverArtUrl: String?,
        val songs: List<PlaylistSong>,
    ) : PlaylistDetailUiState

    data class Error(val message: String) : PlaylistDetailUiState
}

data class PlaylistSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Int?,
    val coverArtUrl: String?,
)
