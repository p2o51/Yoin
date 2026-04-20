package com.gpo.yoin.ui.nowplaying

sealed interface NowPlayingUiState {

    data object Idle : NowPlayingUiState

    /**
     * User tapped a track on a provider whose playback backend is still
     * coming up (e.g. Spotify App Remote negotiating the connection).
     * Display the target track's title/cover as "about to play" but do NOT
     * render controls as active, do NOT advance progress. A successful
     * first PlayerState transitions to [Playing]; a failure transitions to
     * [ConnectError].
     */
    data class Launching(
        val songTitle: String,
        val artist: String,
        val albumName: String,
        val coverArtUrl: String?,
        val durationMs: Long,
        val hint: String,
    ) : NowPlayingUiState

    /**
     * Backend refused / lost the connection while trying to play the
     * target track. Surface a dedicated error state so UI can render a
     * clear message instead of faking a playing-but-stuck screen.
     */
    data class ConnectError(
        val songTitle: String,
        val artist: String,
        val coverArtUrl: String?,
        val message: String,
    ) : NowPlayingUiState

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
        /**
         * True while the lyrics fetch is in flight (cache miss → provider
         * fallback). UI renders a loading affordance instead of the
         * "No lyrics available" empty state.
         */
        val lyricsLoading: Boolean,
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
