package com.gpo.yoin.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: YoinRepository,
    private val onPlaylistMutated: () -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-shot toasts for playlist mutations surfaced from Library tab. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val searchRequestFlow = MutableStateFlow(
        LibrarySearchRequest("", LibrarySearchScope.CurrentLibrary),
    )

    private var cachedArtists: List<Artist>? = null
    private var cachedAlbums: List<Album>? = null
    private var cachedSongs: List<Track>? = null
    private var cachedPlaylists: List<Playlist>? = null
    private var cachedFavorites: Starred? = null
    private var pendingSearchShortcutScope: LibrarySearchScope? = null
    private var searchFocusRequestCounter = 0L

    /** Monotonic id per selectTab load; a stale failure must not revert a newer selection. */
    private var tabLoadGeneration = 0L

    val notedSongIds: StateFlow<Set<String>> = uiState
        .flatMapLatest { state ->
            val visibleTrackIds = when (state) {
                is LibraryUiState.Content -> when {
                    state.searchQuery.isNotBlank() ->
                        state.searchResults?.tracks.orEmpty().map(Track::id)
                    state.selectedTab == LibraryTab.Songs ->
                        state.songs.map(Track::id)
                    state.selectedTab == LibraryTab.Favorites ->
                        state.favorites?.tracks.orEmpty().map(Track::id)
                    else -> emptyList()
                }
                else -> emptyList()
            }
            if (visibleTrackIds.isEmpty()) {
                flowOf(emptySet())
            } else {
                repository.observeTracksWithNotes(visibleTrackIds)
            }
        }
        .map { ids -> ids.mapTo(linkedSetOf(), MediaId::toString) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        loadInitialData()
        observeSearch()
        observeCapabilities()
        observeProviderSearchAvailability()
        observeFavoriteOverrides()
    }

    fun refresh() {
        _uiState.value = LibraryUiState.Loading
        cachedArtists = null
        cachedAlbums = null
        cachedSongs = null
        cachedPlaylists = null
        cachedFavorites = null
        pendingSearchShortcutScope = null
        loadInitialData(forceSpotifyRefresh = true)
    }

    private fun loadInitialData(forceSpotifyRefresh: Boolean = false) {
        viewModelScope.launch {
            // Cold-start race: the ProfileManager resolves the active profile and
            // builds its MusicSource asynchronously in its own init, so for a beat
            // after launch `activeSource` is null and the very first library load
            // would throw "No profile configured". Wait briefly for the source to
            // arrive before loading. Bounded so a genuinely profile-less install
            // (no source will ever come) still falls through to the error/empty
            // state instead of hanging on the loading spinner forever.
            if (repository.currentProviderId() == null) {
                withTimeoutOrNull(ACTIVE_SOURCE_WAIT_MS) {
                    repository.activeProviderId.filterNotNull().first()
                }
            }
            try {
                if (isSpotifyProvider()) {
                    repository.refreshSpotifyLibrary(force = forceSpotifyRefresh)
                }
                val artists = loadArtistsFlat()
                cachedArtists = artists
                val capabilities = repository.currentCapabilities()
                val canSearchSpotifyCatalog = isSpotifyProvider()
                val hasPendingSearchShortcut = pendingSearchShortcutScope != null
                val pendingScope = pendingSearchShortcutScope
                    ?.let(::normaliseSearchScope)
                    ?: LibrarySearchScope.CurrentLibrary
                pendingSearchShortcutScope = null
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
                    searchScope = pendingScope,
                    canSearchSpotifyCatalog = canSearchSpotifyCatalog,
                    searchFocusRequestId = if (hasPendingSearchShortcut) nextSearchFocusRequestId() else 0L,
                    availableTabs = visibleTabs(capabilities),
                    canCreatePlaylists = Capability.PLAYLISTS_WRITE in capabilities,
                )
                searchRequestFlow.value = LibrarySearchRequest("", pendingScope)
            } catch (e: Exception) {
                if (isSpotifyProvider() && repository.hasSpotifyCachedData()) {
                    val artists = cachedArtists ?: loadArtistsFlat().also { cachedArtists = it }
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
                        searchScope = LibrarySearchScope.CurrentLibrary,
                        canSearchSpotifyCatalog = true,
                        availableTabs = visibleTabs(repository.currentCapabilities()),
                        canCreatePlaylists = Capability.PLAYLISTS_WRITE in repository.currentCapabilities(),
                    )
                } else {
                    _uiState.value = LibraryUiState.Error(
                        e.message ?: "Failed to load library",
                    )
                }
            }
        }
    }

    private fun observeCapabilities() {
        viewModelScope.launch {
            repository.capabilities.collectLatest { capabilities ->
                val current = _uiState.value as? LibraryUiState.Content
                    ?: return@collectLatest
                val visible = visibleTabs(capabilities)
                val normalisedSelected = current.selectedTab.takeIf { it in visible }
                    ?: visible.firstOrNull()
                    ?: LibraryTab.Artists
                _uiState.value = current.copy(
                    availableTabs = visible,
                    selectedTab = normalisedSelected,
                    canCreatePlaylists = Capability.PLAYLISTS_WRITE in capabilities,
                )
            }
        }
    }

    /**
     * Playlists disappear from the tab row when the provider doesn't support
     * reading them. Other tabs are universal across both Subsonic and Spotify
     * today; they'll gain capability gates when we add providers that
     * genuinely lack them.
     */
    private fun visibleTabs(capabilities: Set<Capability>): List<LibraryTab> =
        LibraryTab.entries.filter { tab ->
            when (tab) {
                LibraryTab.Playlists -> Capability.PLAYLISTS_READ in capabilities
                else -> true
            }
        }

    fun selectTab(tab: LibraryTab) {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        val previousTab = current.selectedTab
        val loadGeneration = ++tabLoadGeneration
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
                                .applyFavoriteOverrides(repository.favoriteOverrides.value)
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
                        if (cachedFavorites == null) {
                            cachedFavorites = repository.getStarred()
                                .applyFavoriteOverrides(repository.favoriteOverrides.value)
                        }
                        updateContent { copy(favorites = cachedFavorites) }
                    }
                }
            } catch (e: Exception) {
                if (isSpotifyProvider() && hasSpotifyTabCache(tab)) {
                    return@launch
                }
                val content = _uiState.value as? LibraryUiState.Content
                if (content == null) {
                    _uiState.value = LibraryUiState.Error(
                        e.message ?: "Failed to load ${tab.name}",
                    )
                    return@launch
                }
                // One tab failing shouldn't blank the whole library: keep the
                // Content we have and explain via snackbar. The failed tab has
                // nothing to show (its empty payload would render a misleading
                // "No X found"), so step back to the tab the user came from —
                // unless they've already moved on, or a NEWER load of this same
                // tab is in flight (a stale failure must not undo its selection).
                if (content.selectedTab == tab && loadGeneration == tabLoadGeneration) {
                    _uiState.value = content.copy(selectedTab = previousTab)
                }
                _messages.tryEmit(
                    e.message?.takeIf { it.isNotBlank() }
                        ?: "Failed to load ${tab.name}",
                )
            }
        }
    }

    fun showLibraryHome() {
        pendingSearchShortcutScope = null
        searchRequestFlow.value = LibrarySearchRequest("", LibrarySearchScope.CurrentLibrary)
        updateContent {
            copy(
                searchScope = LibrarySearchScope.CurrentLibrary,
                searchQuery = "",
                searchResults = null,
                isSearching = false,
            )
        }
    }

    fun openSearchShortcut(scope: LibrarySearchScope) {
        val effectiveScope = normaliseSearchScope(scope)
        val current = _uiState.value as? LibraryUiState.Content
        if (current == null) {
            pendingSearchShortcutScope = effectiveScope
            return
        }
        searchRequestFlow.value = LibrarySearchRequest("", effectiveScope)
        _uiState.value = current.copy(
            searchScope = effectiveScope,
            searchQuery = "",
            searchResults = null,
            isSearching = false,
            searchFocusRequestId = nextSearchFocusRequestId(),
        )
    }

    fun selectSearchScope(scope: LibrarySearchScope) {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        val effectiveScope = normaliseSearchScope(scope)
        if (current.searchScope == effectiveScope) return

        _uiState.value = current.copy(
            searchScope = effectiveScope,
            searchResults = null,
            isSearching = current.searchQuery.isNotBlank(),
        )
        searchRequestFlow.value = LibrarySearchRequest(current.searchQuery, effectiveScope)
    }

    fun search(query: String) {
        val scope = (_uiState.value as? LibraryUiState.Content)
            ?.searchScope
            ?.let(::normaliseSearchScope)
            ?: LibrarySearchScope.CurrentLibrary
        updateContent {
            if (searchQuery == query) {
                this
            } else {
                copy(
                    searchQuery = query,
                    searchResults = searchResults.takeIf { query.isNotBlank() },
                    isSearching = if (query.isBlank()) false else isSearching,
                )
            }
        }
        searchRequestFlow.value = LibrarySearchRequest(query, scope)
    }

    fun clearSearch() {
        val scope = (_uiState.value as? LibraryUiState.Content)
            ?.searchScope
            ?.let(::normaliseSearchScope)
            ?: LibrarySearchScope.CurrentLibrary
        searchRequestFlow.value = LibrarySearchRequest("", scope)
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
            searchRequestFlow
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { request ->
                    val query = request.query
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
                        val results = searchWithScope(query, request.scope)
                            .applyFavoriteOverrides(repository.favoriteOverrides.value)
                        updateContent {
                            if (
                                searchQuery != query ||
                                searchScope != normaliseSearchScope(request.scope)
                            ) {
                                this
                            } else {
                                copy(
                                    searchResults = results,
                                    isSearching = false,
                                )
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        _messages.tryEmit(
                            e.message?.takeIf { it.isNotBlank() }
                                ?: "Search failed",
                        )
                        updateContent {
                            if (
                                searchQuery != query ||
                                searchScope != normaliseSearchScope(request.scope)
                            ) {
                                this
                            } else {
                                copy(
                                    searchResults = SearchResults(),
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
        return indices.flatMap { it.artists }
    }

    private inline fun updateContent(
        transform: LibraryUiState.Content.() -> LibraryUiState.Content,
    ) {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        _uiState.value = current.transform()
    }

    private fun observeFavoriteOverrides() {
        viewModelScope.launch {
            repository.favoriteOverrides.collectLatest { overrides ->
                applyFavoriteOverrides(overrides)
                val current = _uiState.value as? LibraryUiState.Content ?: return@collectLatest
                // Only the Favorites tab re-reads live: getStarred() returns a
                // stable ordered set, so a newly-favorited track inserts cleanly
                // and an un-favorited one drops. The Songs tab is intentionally
                // NOT re-read here — it's a random sample (getRandomSongs does
                // .shuffled().take(50)), so a live re-read would reshuffle the
                // whole visible list on every toggle. Its heart icons are
                // already updated in-place by applyFavoriteOverrides above.
                if (current.selectedTab == LibraryTab.Favorites) {
                    try {
                        cachedFavorites = repository.getStarred()
                            .applyFavoriteOverrides(overrides)
                        updateContent { copy(favorites = cachedFavorites) }
                    } catch (_: Exception) {
                        // Stale favorites cache remains visible; the override
                        // pass above already updated in-place state.
                    }
                }
            }
        }
    }

    private fun observeProviderSearchAvailability() {
        viewModelScope.launch {
            repository.activeProviderId
                .distinctUntilChanged()
                .collectLatest { providerId ->
                    val canSearchSpotifyCatalog = providerId == MediaId.PROVIDER_SPOTIFY
                    val current = _uiState.value as? LibraryUiState.Content
                        ?: return@collectLatest
                    val nextScope = if (canSearchSpotifyCatalog) {
                        current.searchScope
                    } else {
                        LibrarySearchScope.CurrentLibrary
                    }
                    val scopeChanged = nextScope != current.searchScope
                    _uiState.value = current.copy(
                        canSearchSpotifyCatalog = canSearchSpotifyCatalog,
                        searchScope = nextScope,
                        searchResults = current.searchResults.takeUnless { scopeChanged },
                        isSearching = if (scopeChanged) false else current.isSearching,
                    )
                    if (current.searchQuery.isNotBlank() && scopeChanged) {
                        searchRequestFlow.value = LibrarySearchRequest(current.searchQuery, nextScope)
                    }
                }
        }
    }

    private suspend fun searchWithScope(
        query: String,
        scope: LibrarySearchScope,
    ): SearchResults = when (normaliseSearchScope(scope)) {
        LibrarySearchScope.SpotifyGlobal -> repository.search(query)
        LibrarySearchScope.CurrentLibrary -> {
            if (isSpotifyProvider()) {
                searchSpotifySavedLibrary(query)
            } else {
                repository.search(query)
            }
        }
    }

    private suspend fun searchSpotifySavedLibrary(query: String): SearchResults {
        val needle = query.normaliseForSearch()
        val snapshot = repository.getSpotifyLocalSearchSnapshot() ?: return SearchResults()
        val favorites = (cachedFavorites ?: snapshot.starred)
            .applyFavoriteOverrides(repository.favoriteOverrides.value)
        val artists = (cachedArtists ?: snapshot.artists)
            .plus(favorites.artists)
            .distinctBy(Artist::id)
        val albums = (cachedAlbums ?: snapshot.albums)
            .plus(favorites.albums)
            .distinctBy(Album::id)
        val songs = snapshot.tracks.ifEmpty { favorites.tracks }
        val playlists = cachedPlaylists ?: snapshot.playlists

        return SearchResults(
            artists = artists
                .filter { artist -> artist.matches(needle) }
                .take(LOCAL_SEARCH_LIMIT_PER_TYPE),
            albums = albums
                .filter { album -> album.matches(needle) }
                .take(LOCAL_SEARCH_LIMIT_PER_TYPE),
            tracks = songs
                .filter { track -> track.matches(needle) }
                .take(LOCAL_SEARCH_LIMIT_PER_TYPE),
            playlists = playlists
                .filter { playlist -> playlist.matches(needle) }
                .take(LOCAL_SEARCH_LIMIT_PER_TYPE),
        )
    }

    private fun applyFavoriteOverrides(overrides: Map<MediaId, Boolean>) {
        if (overrides.isEmpty()) return

        cachedSongs = cachedSongs?.applyFavoriteOverrides(overrides)
        cachedFavorites = cachedFavorites?.applyFavoriteOverrides(overrides)

        updateContent {
            copy(
                songs = songs.applyFavoriteOverrides(overrides),
                favorites = favorites?.applyFavoriteOverrides(overrides),
                searchResults = searchResults?.applyFavoriteOverrides(overrides),
            )
        }
    }

    private fun List<Track>.applyFavoriteOverrides(
        overrides: Map<MediaId, Boolean>,
    ): List<Track> = map { track ->
        overrides[track.id]?.let { isStarred -> track.copy(isStarred = isStarred) } ?: track
    }

    private fun SearchResults.applyFavoriteOverrides(
        overrides: Map<MediaId, Boolean>,
    ): SearchResults = copy(
        tracks = tracks.applyFavoriteOverrides(overrides),
    )

    private fun Starred.applyFavoriteOverrides(
        overrides: Map<MediaId, Boolean>,
    ): Starred = copy(
        tracks = tracks
            .applyFavoriteOverrides(overrides)
            .filter(Track::isStarred),
    )

    fun buildCoverArtUrl(coverArtId: String): String =
        repository.resolveSubsonicCoverUrl(coverArtId, size = 256).orEmpty()

    fun invalidatePlaylists() {
        cachedPlaylists = null
        val current = _uiState.value as? LibraryUiState.Content ?: return
        if (current.selectedTab != LibraryTab.Playlists) return
        viewModelScope.launch {
            runCatching { repository.getPlaylists() }
                .onSuccess { playlists ->
                    cachedPlaylists = playlists
                    updateContent { copy(playlists = playlists) }
                }
        }
    }

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
                    onPlaylistMutated()
                    _messages.tryEmit("Created \"$trimmed\"")
                }
                .onFailure {
                    _messages.tryEmit(it.message ?: "Couldn't create \"$trimmed\"")
                }
        }
    }

    private fun normaliseSearchScope(scope: LibrarySearchScope): LibrarySearchScope =
        if (scope == LibrarySearchScope.SpotifyGlobal && !isSpotifyProvider()) {
            LibrarySearchScope.CurrentLibrary
        } else {
            scope
        }

    private fun isSpotifyProvider(): Boolean =
        repository.currentProviderId() == MediaId.PROVIDER_SPOTIFY

    private fun hasSpotifyTabCache(tab: LibraryTab): Boolean = when (tab) {
        LibraryTab.Artists -> !cachedArtists.isNullOrEmpty()
        LibraryTab.Albums -> !cachedAlbums.isNullOrEmpty()
        LibraryTab.Songs -> !cachedSongs.isNullOrEmpty()
        LibraryTab.Playlists -> !cachedPlaylists.isNullOrEmpty()
        LibraryTab.Favorites -> cachedFavorites != null
    }

    private fun nextSearchFocusRequestId(): Long {
        searchFocusRequestCounter += 1
        return searchFocusRequestCounter
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(
                repository = container.repository,
                onPlaylistMutated = container::notifyPlaylistMutation,
            ) as T
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L

        /** Max wait for the active [MusicSource] to resolve on a cold start
         *  before the first library load gives up (see [loadInitialData]). */
        private const val ACTIVE_SOURCE_WAIT_MS = 4_000L
        private const val LOCAL_SEARCH_LIMIT_PER_TYPE = 40
    }
}

private data class LibrarySearchRequest(
    val query: String,
    val scope: LibrarySearchScope,
)

private fun String.normaliseForSearch(): String = trim().lowercase()

private fun String?.containsSearchToken(token: String): Boolean {
    val value = this ?: return false
    return value.isNotBlank() && value.lowercase().contains(token)
}

private fun Artist.matches(token: String): Boolean =
    name.containsSearchToken(token) || id.rawId.containsSearchToken(token)

private fun Album.matches(token: String): Boolean =
    name.containsSearchToken(token) ||
        artist.containsSearchToken(token) ||
        genre.containsSearchToken(token) ||
        year?.toString().containsSearchToken(token) ||
        id.rawId.containsSearchToken(token)

private fun Track.matches(token: String): Boolean =
    title.containsSearchToken(token) ||
        artist.containsSearchToken(token) ||
        album.containsSearchToken(token) ||
        genre.containsSearchToken(token) ||
        id.rawId.containsSearchToken(token)

private fun Playlist.matches(token: String): Boolean =
    name.containsSearchToken(token) ||
        owner.containsSearchToken(token) ||
        id.rawId.containsSearchToken(token)
