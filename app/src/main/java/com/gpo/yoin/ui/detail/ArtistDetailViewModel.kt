package com.gpo.yoin.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.repository.YoinRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ArtistDetailViewModel(
    private val artistId: String,
    private val repository: YoinRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ArtistDetailUiState>(ArtistDetailUiState.Loading)
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    init {
        loadArtist()
    }

    private fun loadArtist() {
        viewModelScope.launch {
            try {
                val artist = repository.getArtist(artistId)
                if (artist == null) {
                    _uiState.value = ArtistDetailUiState.Error("Artist not found")
                    return@launch
                }
                _uiState.value = ArtistDetailUiState.Content(
                    artistName = artist.name,
                    albumCount = artist.albumCount,
                    albums = artist.album.map { album ->
                        ArtistAlbum(
                            id = album.id,
                            name = album.name,
                            coverArtUrl = album.coverArt?.let {
                                repository.buildCoverArtUrl(it)
                            },
                            year = album.year,
                            songCount = album.songCount,
                        )
                    },
                )
            } catch (e: Exception) {
                _uiState.value = ArtistDetailUiState.Error(
                    e.message ?: "Failed to load artist",
                )
            }
        }
    }

    class Factory(
        private val artistId: String,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ArtistDetailViewModel(artistId, container.repository) as T
    }
}
