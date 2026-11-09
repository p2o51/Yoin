package com.gpo.yoin.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.integration.neodb.NeoDbAlbumVoices
import com.gpo.yoin.data.integration.neodb.NeoDbReferenceScore
import com.gpo.yoin.data.integration.neodb.NeoDbVoice
import com.gpo.yoin.data.integration.neodb.NeoDbVoicesService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The "别人怎么说" page's states. */
sealed interface NeoDbVoicesUiState {
    data object Loading : NeoDbVoicesUiState

    /** NeoDB has no item like this album: the page offers a search there. */
    data class NotFound(val searchUrl: String) : NeoDbVoicesUiState

    data object Failed : NeoDbVoicesUiState

    data class Content(
        val album: NeoDbAlbumVoices,
        val voices: List<NeoDbVoice>,
        /** Every comment and review on the item, not only the loaded ones. */
        val count: Int,
        val references: List<NeoDbReferenceScore>,
        val loadingMore: Boolean = false,
        val endReached: Boolean = false,
        /** The next page failed: the list ends in a retry. */
        val moreFailed: Boolean = false,
    ) : NeoDbVoicesUiState
}

class NeoDbVoicesViewModel(
    private val service: NeoDbVoicesService,
    val albumName: String,
    val artistName: String?,
) : ViewModel() {
    private val _uiState = MutableStateFlow<NeoDbVoicesUiState>(NeoDbVoicesUiState.Loading)
    val uiState: StateFlow<NeoDbVoicesUiState> = _uiState.asStateFlow()

    private var nextPage = 1
    private var pageJob: Job? = null

    init {
        load()
    }

    fun load() {
        pageJob?.cancel()
        _uiState.value = NeoDbVoicesUiState.Loading
        nextPage = 1
        viewModelScope.launch {
            val album = service.findAlbum(albumName, artistName).getOrElse {
                _uiState.value = NeoDbVoicesUiState.Failed
                return@launch
            }
            if (album == null) {
                _uiState.value = NeoDbVoicesUiState.NotFound(service.searchUrl(albumName, artistName))
                return@launch
            }
            val first = service.voices(album.itemUuid, 1).getOrElse {
                _uiState.value = NeoDbVoicesUiState.Failed
                return@launch
            }
            nextPage = 2
            _uiState.value = NeoDbVoicesUiState.Content(
                album = album,
                voices = first.voices,
                count = first.count,
                references = emptyList(),
                endReached = first.page >= first.pages,
            )
            // The other catalogues come in after the words: they never hold the page back.
            val references = service.referenceScores(album)
            _uiState.update { state ->
                if (state is NeoDbVoicesUiState.Content) state.copy(references = references) else state
            }
        }
    }

    /** The next 20, when the list nears its end. */
    fun loadMore() {
        val state = _uiState.value as? NeoDbVoicesUiState.Content ?: return
        if (state.loadingMore || state.endReached || pageJob?.isActive == true) return
        _uiState.value = state.copy(loadingMore = true, moreFailed = false)
        val page = nextPage
        pageJob = viewModelScope.launch {
            val result = service.voices(state.album.itemUuid, page)
            _uiState.update { current ->
                if (current !is NeoDbVoicesUiState.Content) return@update current
                result.fold(
                    onSuccess = { more ->
                        nextPage = page + 1
                        val known = current.voices.mapTo(HashSet()) { it.id }
                        current.copy(
                            voices = current.voices + more.voices.filter { it.id !in known },
                            loadingMore = false,
                            endReached = more.voices.isEmpty() || more.page >= more.pages,
                        )
                    },
                    onFailure = { current.copy(loadingMore = false, moreFailed = true) },
                )
            }
        }
    }

    class Factory(
        private val container: AppContainer,
        private val albumName: String,
        private val artistName: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NeoDbVoicesViewModel(container.neoDbVoicesService, albumName, artistName) as T
    }
}
