package com.gpo.yoin.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.artist
import com.gpo.yoin.data.repository.YoinRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class LibraryViewModel(
    private val repository: YoinRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-shot toasts for playlist mutations surfaced from Library tab. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val searchQueryFlow = MutableStateFlow("")

    private var cachedArtists: List<Artist>? = null
    private var cachedAlbums: List<Album>? = null
    private var cachedSongs: List<Track>? = null
    private var cachedPlaylists: List<Playlist>? = null
    private var cachedFavorites: Starred? = null

    init {
        loadInitialData()
        observeSearch()
    }

    fun refresh() {
        _uiState.value = LibraryUiState.Loading
        cachedArtists = null
        cachedAlbums = null
        cachedSongs = null
        cachedPlaylists = null
        cachedFavorites = null
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                val artists = loadArtistsFlat()
                cachedArtists = artists
                _uiState.value = LibraryUiState.Content(
                    selectedTab = LibraryTab.Artists,
                    artists = artists,
                    albums = emptyList(),
                    songs = emptyList(),
                    playlists = emptyList(),
                    favorites = null,
                    searchQuery = "",
                    searchResults = null,
                    isSearching = false,
                )
            } catch (e: Exception) {
                _uiState.value = LibraryUiState.Error(
                    e.message ?: "Failed to load library",
                )
            }
        }
    }

    fun selectTab(tab: LibraryTab) {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        _uiState.value = current.copy(selectedTab = tab)
        viewModelScope.launch {
            try {
                when (tab) {
                    LibraryTab.Artists -> {
                        if (cachedArtists == null) {
                            cachedArtists = loadArtistsFlat()
                        }
                        updateContent { copy(artists = cachedArtists.orEmpty()) }
                    }
                    LibraryTab.Albums -> {
                        if (cachedAlbums == null) {
                            cachedAlbums = repository.getAlbumList("alphabeticalByName", size = 500)
                        }
                        updateContent { copy(albums = cachedAlbums.orEmpty()) }
                    }
                    LibraryTab.Songs -> {
                        if (cachedSongs == null) {
                            cachedSongs = repository.getRandomSongs(size = 50)
                        }
                        updateContent { copy(songs = cachedSongs.orEmpty()) }
                    }
                    LibraryTab.Playlists -> {
                        if (cachedPlaylists == null) {
                            cachedPlaylists = repository.getPlaylists()
                        }
                        updateContent { copy(playlists = cachedPlaylists.orEmpty()) }
                    }
                    LibraryTab.Favorites -> {
                        cachedFavorites = repository.getStarred()
                        updateContent { copy(favorites = cachedFavorites) }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = LibraryUiState.Error(
                    e.message ?: "Failed to load ${tab.name}",
                )
            }
        }
    }

    fun search(query: String) {
        updateContent {
            if (searchQuery == query) {
                this
            } else {
                copy(searchQuery = query)
            }
        }
        searchQueryFlow.value = query
    }

    fun clearSearch() {
        searchQueryFlow.value = ""
        updateContent {
            copy(
                searchQuery = "",
                searchResults = null,
                isSearching = false,
            )
        }
    }

    private fun observeSearch() {
        viewModelScope.launch {
            searchQueryFlow
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.isBlank()) {
                        updateContent {
                            copy(
                                searchResults = null,
                                isSearching = false,
                            )
                        }
                        return@collectLatest
                    }

                    updateContent { copy(isSearching = true) }
                    try {
                        val results = repository.search(query)
                        updateContent {
                            if (searchQuery != query) {
                                this
                            } else {
                                copy(
                                    searchResults = results,
                                    isSearching = false,
                                )
                            }
                        }
                    } catch (_: Exception) {
                        updateContent {
                            if (searchQuery != query) {
                                this
                            } else {
                                copy(
                                    isSearching = false,
                                )
                            }
                        }
                    }
                }
        }
    }

    private suspend fun loadArtistsFlat(): List<Artist> {
        val indices: List<ArtistIndex> = repository.getArtists()
        return indices.flatMap { it.artist }
    }

    private inline fun updateContent(
        transform: LibraryUiState.Content.() -> LibraryUiState.Content,
    ) {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        _uiState.value = current.transform()
    }

    fun buildCoverArtUrl(coverArtId: String): String =
        repository.resolveSubsonicCoverUrl(coverArtId, size = 256).orEmpty()

    /**
     * Create an empty playlist on the active source and splice it into the
     * Playlists tab without a full reload.
     */
    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.createPlaylist(trimmed)
                .onSuccess { created ->
                    // Merge the newcomer into the cached list so the Playlists
                    // tab shows it immediately; a full refresh would wipe
                    // unrelated cached tabs.
                    val updated = (cachedPlaylists.orEmpty() + created)
                        .sortedBy { it.name.lowercase() }
                    cachedPlaylists = updated
                    updateContent { copy(playlists = updated) }
                    _messages.tryEmit("Created \"$trimmed\"")
                }
                .onFailure {
                    _messages.tryEmit(it.message ?: "Couldn't create \"$trimmed\"")
                }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(container.repository) as T
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}
