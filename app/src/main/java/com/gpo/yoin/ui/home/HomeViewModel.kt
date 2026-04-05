package com.gpo.yoin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.repository.YoinRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: YoinRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = HomeUiState.Loading
        viewModelScope.launch {
            try {
                coroutineScope {
                    val activitiesDeferred = async {
                        repository.getRecentHistory(limit = 20).first()
                    }
                    val randomDeferred = async {
                        repository.getAlbumList("random", size = 10)
                    }
                    val newestDeferred = async {
                        repository.getAlbumList("newest", size = 10)
                    }
                    val frequentDeferred = async {
                        repository.getAlbumList("frequent", size = 20)
                    }

                    val activities = activitiesDeferred.await()
                    val random = randomDeferred.await()
                    val newest = newestDeferred.await()
                    val frequent = frequentDeferred.await()

                    _uiState.value = HomeUiState.Content(
                        activities = activities,
                        mixAlbums = (random + newest).distinctBy { it.id },
                        memories = frequent,
                    )
                }
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(
                    e.message ?: "Failed to load home content",
                )
            }
        }
    }

    fun buildCoverArtUrl(coverArtId: String): String =
        repository.buildCoverArtUrl(coverArtId, size = 300)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(container.repository) as T
    }
}
