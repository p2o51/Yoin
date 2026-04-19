package com.gpo.yoin.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AlbumDetailViewModel(
    private val albumId: String,
    private val repository: YoinRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AlbumDetailUiState>(AlbumDetailUiState.Loading)
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    private var albumSongs: List<Track> = emptyList()

    init {
        loadAlbum()
    }

    fun getAlbumSongs(): List<Track> = albumSongs

    fun retry() {
        _uiState.value = AlbumDetailUiState.Loading
        loadAlbum()
    }

    private fun loadAlbum() {
        viewModelScope.launch {
            try {
                val parsedAlbumId = MediaId.parse(albumId)
                val album = repository.getAlbum(parsedAlbumId)
                if (album == null) {
                    _uiState.value = AlbumDetailUiState.Error("Album not found")
                    return@launch
                }
                albumSongs = album.tracks
                repository.recordAlbumVisit(album)
                _uiState.value = AlbumDetailUiState.Content(
                    albumId = album.id.toString(),
                    albumName = album.name,
                    artistName = album.artist.orEmpty(),
                    artistId = album.artistId?.toString(),
                    // Storage-key shape (URL for Spotify, raw id for Subsonic)
                    // so downstream `ActivityContext.Album` → `ActivityEvent`
                    // persistence round-trips via CoverRef.{to,from}StorageKey.
                    coverArtId = CoverRef.toStorageKey(album.coverArt),
                    coverArtUrl = album.coverArt?.let { repository.resolveCoverUrl(it) },
                    year = album.year,
                    songCount = album.songCount,
                    totalDuration = album.durationSec,
                    songs = album.tracks.map { song ->
                        AlbumSong(
                            id = song.id.toString(),
                            title = song.title.orEmpty(),
                            artist = song.artist.orEmpty(),
                            trackNumber = song.trackNumber,
                            duration = song.durationSec,
                            isStarred = song.isStarred,
                        )
                    },
                )
            } catch (e: Exception) {
                _uiState.value = AlbumDetailUiState.Error(
                    e.message ?: "Failed to load album",
                )
            }
        }
    }

    fun toggleStar(songId: String) {
        val current = _uiState.value as? AlbumDetailUiState.Content ?: return
        val song = current.songs.find { it.id == songId } ?: return
        viewModelScope.launch {
            try {
                repository.setFavorite(MediaId.parse(songId), favorite = !song.isStarred)
                _uiState.value = current.copy(
                    songs = current.songs.map {
                        if (it.id == songId) it.copy(isStarred = !it.isStarred) else it
                    },
                )
            } catch (_: Exception) {
                // Silently ignore star/unstar failures
            }
        }
    }

    class Factory(
        private val albumId: String,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AlbumDetailViewModel(albumId, container.repository) as T
    }
}
