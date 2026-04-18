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
        /** Source-provided write permission; gates Rename/Delete/Remove UI. */
        val canWrite: Boolean = false,
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
    /**
     * Zero-based server-ordered position. Used by remove-by-index
     * (Subsonic) and remove-by-uri+positions (Spotify, duplicate-safe).
     */
    val position: Int = 0,
)
