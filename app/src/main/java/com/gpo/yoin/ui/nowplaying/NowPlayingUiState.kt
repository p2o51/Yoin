package com.gpo.yoin.ui.nowplaying

sealed interface NowPlayingUiState {

    data object Idle : NowPlayingUiState

    data class Playing(
        val songTitle: String,
        val artist: String,
        val albumName: String,
        val coverArtUrl: String?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val bufferedMs: Long,
    ) : NowPlayingUiState
}
