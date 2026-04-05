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
        val songId: String,
        val rating: Float,
        val isStarred: Boolean,
        val lyrics: List<LyricLine>,
        val queue: List<QueueItem>,
        val currentQueueIndex: Int,
    ) : NowPlayingUiState
}

data class LyricLine(
    val startMs: Long?,
    val text: String,
)

data class QueueItem(
    val songId: String,
    val title: String,
    val artist: String,
    val coverArtUrl: String?,
)
