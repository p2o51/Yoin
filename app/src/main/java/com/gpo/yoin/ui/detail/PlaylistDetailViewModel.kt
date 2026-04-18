package com.gpo.yoin.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.comment
import com.gpo.yoin.data.model.duration
import com.gpo.yoin.data.model.entry
import com.gpo.yoin.data.model.isPublic
import com.gpo.yoin.data.repository.YoinRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(
    private val playlistId: String,
    private val repository: YoinRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlaylistDetailUiState>(PlaylistDetailUiState.Loading)
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    private var playlistSongs: List<Track> = emptyList()

    init {
        loadPlaylist()
    }

    fun getPlaylistSongs(): List<Track> = playlistSongs

    fun retry() {
        _uiState.value = PlaylistDetailUiState.Loading
        loadPlaylist()
    }

    private fun loadPlaylist() {
        viewModelScope.launch {
            try {
                val playlist = repository.getPlaylist(MediaId.parse(playlistId))
                if (playlist == null) {
                    _uiState.value = PlaylistDetailUiState.Error("Playlist not found")
                    return@launch
                }
                playlistSongs = playlist.entry
                val heroSong = playlist.entry.firstOrNull()
                val heroCoverRef = heroSong?.coverArt ?: heroSong?.albumId?.let { CoverRef.SourceRelative(it.rawId) }
                _uiState.value = PlaylistDetailUiState.Content(
                    playlistId = playlist.id.toString(),
                    playlistName = playlist.name,
                    owner = playlist.owner.orEmpty(),
                    comment = playlist.comment,
                    isPublic = playlist.isPublic,
                    songCount = playlist.songCount,
                    totalDuration = playlist.duration,
                    coverArtUrl = heroCoverRef?.let { repository.resolveCoverUrl(it) },
                    songs = playlist.entry.map { song ->
                        PlaylistSong(
                            id = song.id.toString(),
                            title = song.title.orEmpty(),
                            artist = song.artist.orEmpty(),
                            album = song.album.orEmpty(),
                            duration = song.duration,
                            coverArtUrl = (song.coverArt
                                ?: song.albumId?.let { albumId -> CoverRef.SourceRelative(albumId.rawId) })?.let {
                                repository.resolveCoverUrl(it, size = 320)
                            },
                        )
                    },
                )
            } catch (e: Exception) {
                _uiState.value = PlaylistDetailUiState.Error(
                    e.message ?: "Failed to load playlist",
                )
            }
        }
    }

    class Factory(
        private val playlistId: String,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaylistDetailViewModel(playlistId, container.repository) as T
    }
}
