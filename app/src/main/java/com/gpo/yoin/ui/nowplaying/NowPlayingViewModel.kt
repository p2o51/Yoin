package com.gpo.yoin.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.data.repository.YoinRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class NowPlayingViewModel(
    private val playbackManager: PlaybackManager,
    private val repository: YoinRepository,
) : ViewModel() {

    val uiState: StateFlow<NowPlayingUiState> = playbackManager.playbackState
        .map { state ->
            val song = state.currentSong
            if (song == null) {
                NowPlayingUiState.Idle
            } else {
                NowPlayingUiState.Playing(
                    songTitle = song.title.orEmpty(),
                    artist = song.artist.orEmpty(),
                    albumName = song.album.orEmpty(),
                    coverArtUrl = song.coverArt?.let { repository.buildCoverArtUrl(it) },
                    isPlaying = state.isPlaying,
                    positionMs = state.position,
                    durationMs = state.duration,
                    bufferedMs = state.bufferedPosition,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingUiState.Idle)

    fun togglePlayPause() {
        val state = playbackManager.playbackState.value
        if (state.isPlaying) playbackManager.pause() else playbackManager.resume()
    }

    fun skipNext() {
        playbackManager.skipNext()
    }

    fun skipPrevious() {
        playbackManager.skipPrevious()
    }

    /** @param fraction 0.0–1.0 mapped to current track duration. */
    fun seekTo(fraction: Float) {
        val durationMs = playbackManager.playbackState.value.duration
        if (durationMs > 0) {
            playbackManager.seekTo((fraction.coerceIn(0f, 1f) * durationMs).toLong())
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NowPlayingViewModel(
                playbackManager = container.playbackManager,
                repository = container.repository,
            ) as T
    }
}
