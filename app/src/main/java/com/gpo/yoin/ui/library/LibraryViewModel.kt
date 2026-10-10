package com.gpo.yoin.ui.library

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.component.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    val trackLibraryStates: StateFlow<Map<MediaId, LibraryMembership>> = repository.trackLibraryStates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _workingLibraryTrackIds = MutableStateFlow<Set<MediaId>>(emptySet())
    val workingLibraryTrackIds: StateFlow<Set<MediaId>> = _workingLibraryTrackIds.asStateFlow()
    private data class LibraryOperationKey(val profileId: String, val trackId: MediaId)
    private val libraryOperations = mutableMapOf<LibraryOperationKey, Long>()
    private var libraryOperationToken = 0L
    private var pendingLibraryCheck: Track? = null

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
    private var searchAttemptCounter = 0
    private var libraryDataGeneration = 0L
    private var initialLoadJob: Job? = null
    private var tabLoadJob: Job? = null
    private var reshuffleJob: Job? = null
    private var copyResources: Resources? = null

    /** Monotonic id per selectTab load; a stale failure must not revert a newer selection. */
    private var tabLoadGeneration = 0L

    val notedSongIds: StateFlow<Set<String>> = uiState
        .flatMapLatest { state ->
            val visibleTrackIds = when (state) {
                is LibraryUiState.Content -> when {
                    state.searchQuery.isNotBlank() ->
                        state.searchResults?.tracks.orEmpty().map(Track::id)
                    state.selectedTab == LibraryTab.Songs ->
                        state.songs.orEmpty().map(Track::id)
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
        observeSearchLibraryMembership()
        observeProfileChanges()
        observeLibraryRevision()
    }

    fun refresh() = reloadLibrary(forceSpotifyRefresh = true)

    private fun reloadLibrary(forceSpotifyRefresh: Boolean = false) {
        cancelDataLoads()
        libraryDataGeneration += 1
        _uiState.value = LibraryUiState.Loading
        cachedArtists = null
        cachedAlbums = null
        cachedSongs = null
        cachedPlaylists = null
        cachedFavorites = null
        pendingSearchShortcutScope = null
        searchAttemptCounter += 1
        searchRequestFlow.value = LibrarySearchRequest(
            "",
            LibrarySearchScope.CurrentLibrary,
            searchAttemptCounter,
        )
        loadInitialData(forceSpotifyRefresh)
    }

    private fun loadInitialData(forceSpotifyRefresh: Boolean = false) {
        initialLoadJob?.cancel()
        val generation = libraryDataGeneration
        initialLoadJob = viewModelScope.launch {
            // Cold-start race: the ProfileManager resolves the active profile and
            // builds its MusicSource asynchronously in its own init, so for a beat
            // after launch `activeSource` is null and the very first library load
            // would throw "No profile configured". Wait briefly for the source to
            // arrive before loading. Bounded so a genuinely profile-less install
            // (no source will ever come) still falls through to the error/empty
            // state instead of hanging on the loading spinner forever.
            repository.awaitActiveSource(ACTIVE_SOURCE_WAIT_MS)
            val profileId = repository.currentProfileId()
            try {
                if (isSpotifyProvider()) {
                    repository.refreshSpotifyLibrary(force = forceSpotifyRefresh)
                }
                val artists = loadArtistsFlat()
                if (!isDataLoadCurrent(generation, profileId)) return@launch
                cachedArtists = artists
                val capabilities = repository.currentCapabilities()
                val canSearchSpotifyCatalog = canSearchCatalog(MediaId.PROVIDER_SPOTIFY)
                val hasPendingSearchShortcut = pendingSearchShortcutScope != null
                val pendingScope = pendingSearchShortcutScope
                    ?.let(::normaliseSearchScope)
                    ?: LibrarySearchScope.CurrentLibrary
                pendingSearchShortcutScope = null
                _uiState.value = LibraryUiState.Content(
                    selectedTab = LibraryTab.Artists,
                    artists = artists,
                    // null = not loaded yet; each tab lazy-loads on first
                    // selection and the UI shows a loading indicator instead
                    // of a misleading "No X found" empty state.
                    albums = null,
                    songs = null,
                    playlists = null,
                    favorites = null,
                    searchQuery = "",
                    searchResults = null,
                    isSearching = false,
                    searchScope = pendingScope,
                    canSearchSpotifyCatalog = canSearchSpotifyCatalog,
                    canSearchAppleMusicCatalog = canSearchCatalog(MediaId.PROVIDER_APPLE_MUSIC),
                    searchFocusRequestId = if (hasPendingSearchShortcut) nextSearchFocusRequestId() else 0L,
                    availableTabs = visibleTabs(capabilities),
                    canCreatePlaylists = Capability.PLAYLISTS_WRITE in capabilities,
                    canReshuffleSongs = canReshuffleSongs(capabilities),
                    canAddToLibrary = Capability.LIBRARY_ADD in capabilities,
                )
                searchRequestFlow.value = LibrarySearchRequest("", pendingScope)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!isDataLoadCurrent(generation, profileId)) return@launch
                val hasSpotifyCache = isSpotifyProvider() && repository.hasSpotifyCachedData()
                if (!isDataLoadCurrent(generation, profileId)) return@launch
                if (hasSpotifyCache) {
                    val artists = cachedArtists ?: loadArtistsFlat()
                    if (!isDataLoadCurrent(generation, profileId)) return@launch
                    cachedArtists = artists
                    _uiState.value = LibraryUiState.Content(
                        selectedTab = LibraryTab.Artists,
                        artists = artists,
                        albums = null,
                        songs = null,
                        playlists = null,
                        favorites = null,
                        searchQuery = "",
                        searchResults = null,
                        isSearching = false,
                        searchScope = LibrarySearchScope.CurrentLibrary,
                        canSearchSpotifyCatalog = canSearchCatalog(MediaId.PROVIDER_SPOTIFY),
                        availableTabs = visibleTabs(repository.currentCapabilities()),
                        canCreatePlaylists = Capability.PLAYLISTS_WRITE in repository.currentCapabilities(),
                        canReshuffleSongs = canReshuffleSongs(repository.currentCapabilities()),
                        canAddToLibrary = Capability.LIBRARY_ADD in repository.currentCapabilities(),
                    )
                } else {
                    _uiState.value = LibraryUiState.Error(
                        e.uiTextOr(UiText.Res(R.string.library_error_load_library)),
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
                    canReshuffleSongs = canReshuffleSongs(capabilities),
                    canAddToLibrary = Capability.LIBRARY_ADD in capabilities,
                )
            }
        }
    }

    /**
     * Playlists disappear from the tab row when the provider doesn't support
     * reading them. Songs can be either a saved-library list or a provider's
     * random sample; favorite controls retain their own capability gate.
     */
    private fun visibleTabs(capabilities: Set<Capability>): List<LibraryTab> =
        LibraryTab.entries.filter { tab ->
            when (tab) {
                LibraryTab.Playlists -> Capability.PLAYLISTS_READ in capabilities
                LibraryTab.Favorites -> Capability.FAVORITES in capabilities
                LibraryTab.Songs -> Capability.LIBRARY_SONGS in capabilities || Capability.RANDOM_SONGS in capabilities
                else -> true
            }
        }

    fun selectTab(tab: LibraryTab) {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        if (tab !in current.availableTabs) return
        val previousTab = current.selectedTab
        tabLoadJob?.cancel()
        val loadGeneration = ++tabLoadGeneration
        val generation = libraryDataGeneration
        val profileId = repository.currentProfileId()
        fun isCurrent(): Boolean = isDataLoadCurrent(generation, profileId) &&
            loadGeneration == tabLoadGeneration
        _uiState.value = current.copy(selectedTab = tab)
        tabLoadJob = viewModelScope.launch {
            try {
                when (tab) {
                    LibraryTab.Artists -> {
                        val artists = cachedArtists ?: loadArtistsFlat()
                        if (!isCurrent()) return@launch
                        cachedArtists = artists
                        updateContent { copy(artists = cachedArtists.orEmpty()) }
                    }
                    LibraryTab.Albums -> {
                        // 最近添加 newest-first — "newest" is recently ADDED on
                        // both providers (Subsonic native; Spotify addedAt sort).
                        val albums = cachedAlbums ?: repository.getAlbumList("newest", size = 500)
                        if (!isCurrent()) return@launch
                        cachedAlbums = albums
                        updateContent { copy(albums = cachedAlbums.orEmpty()) }
                    }
                    LibraryTab.Songs -> {
                        val songs = cachedSongs ?: run {
                            val loaded = if (Capability.LIBRARY_SONGS in repository.currentCapabilities()) {
                                repository.getLibrarySongs(size = 500)
                            } else {
                                repository.getRandomSongs(size = 50)
                            }
                            loaded.applyFavoriteOverrides(repository.favoriteOverrides.value)
                        }
                        if (!isCurrent()) return@launch
                        cachedSongs = songs
                        updateContent { copy(songs = cachedSongs.orEmpty()) }
                    }
                    LibraryTab.Playlists -> {
                        val playlists = cachedPlaylists ?: repository.getPlaylists()
                        if (!isCurrent()) return@launch
                        cachedPlaylists = playlists
                        updateContent { copy(playlists = cachedPlaylists.orEmpty()) }
                    }
                    LibraryTab.Favorites -> {
                        val favorites = cachedFavorites ?: repository.getStarred()
                            .applyFavoriteOverrides(repository.favoriteOverrides.value)
                        if (!isCurrent()) return@launch
                        cachedFavorites = favorites
                        updateContent { copy(favorites = cachedFavorites) }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!isCurrent()) return@launch
                if (isSpotifyProvider() && hasSpotifyTabCache(tab)) {
                    return@launch
                }
                val content = _uiState.value as? LibraryUiState.Content
                if (content == null) {
                    _uiState.value = LibraryUiState.Error(
                        e.uiTextOr(tab.loadFailure()),
                    )
                    return@launch
                }
                // One tab failing shouldn't blank the whole library: keep the
                // Content we have and explain via snackbar. The failed tab has
                // nothing to show (its payload is still null, so it would sit
                // on its loading indicator forever), so step back to the tab
                // the user came from — unless they've already moved on, or a
                // NEWER load of this same tab is in flight (a stale failure
                // must not undo its selection).
                if (content.selectedTab == tab && loadGeneration == tabLoadGeneration) {
                    _uiState.value = content.copy(selectedTab = previousTab)
                }
                _messages.tryEmit(
                    e.snackbarOr(tab.loadFailureRes(), "Failed to load ${tab.name}"),
                )
            }
        }
    }

    /**
     * Discards the current random sample and draws a fresh one. The Songs tab
     * is a 50-song random mix ([selectTab] only loads when `cachedSongs` is
     * null), so this is the only way to reshuffle — favorite toggles
     * deliberately never re-read it (see [observeFavoriteOverrides]).
     */
    fun reshuffleSongs() {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        if (current.selectedTab != LibraryTab.Songs || !current.canReshuffleSongs) return
        val previousSongs = cachedSongs
        val generation = libraryDataGeneration
        val profileId = repository.currentProfileId()
        reshuffleJob?.cancel()
        cachedSongs = null
        updateContent { copy(songs = null) }
        reshuffleJob = viewModelScope.launch {
            try {
                val songs = repository.getRandomSongs(size = 50)
                    .applyFavoriteOverrides(repository.favoriteOverrides.value)
                if (!isDataLoadCurrent(generation, profileId)) return@launch
                cachedSongs = songs
                updateContent { copy(songs = cachedSongs.orEmpty()) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!isDataLoadCurrent(generation, profileId)) return@launch
                // Keep the sample the user already had rather than stranding
                // the tab on its loading indicator.
                cachedSongs = previousSongs
                updateContent { copy(songs = previousSongs) }
                _messages.tryEmit(
                    e.snackbarOr(R.string.library_error_reshuffle, "Couldn't reshuffle songs"),
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
                searchError = null,
                // The shortcut id is a one-shot UI trigger. Leaving it set
                // would reopen Search when Library remounts after Home.
                searchFocusRequestId = 0L,
            )
        }
    }

    fun openSearchShortcut(scope: LibrarySearchScope) {
        val current = _uiState.value as? LibraryUiState.Content
        if (current == null) {
            // Keep the requested scope while the active source resolves;
            // loadInitialData normalises it against the resulting provider.
            pendingSearchShortcutScope = scope
            return
        }
        val effectiveScope = normaliseSearchScope(scope)
        searchRequestFlow.value = LibrarySearchRequest("", effectiveScope)
        _uiState.value = current.copy(
            searchScope = effectiveScope,
            searchQuery = "",
            searchResults = null,
            isSearching = false,
            searchError = null,
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
            searchError = null,
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
                    searchError = null,
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
                searchError = null,
            )
        }
    }

    /**
     * Re-runs the current query after a failed search. The request pipeline
     * is a [MutableStateFlow], which drops value-equal writes — the bumped
     * `attempt` makes the retried request distinct so it actually re-fires.
     */
    fun retrySearch() {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        if (current.searchQuery.isBlank()) return
        updateContent { copy(isSearching = true, searchError = null) }
        searchAttemptCounter += 1
        searchRequestFlow.value = LibrarySearchRequest(
            query = current.searchQuery,
            scope = normaliseSearchScope(current.searchScope),
            attempt = searchAttemptCounter,
        )
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
                                searchError = null,
                            )
                        }
                        return@collectLatest
                    }

                    val generation = libraryDataGeneration
                    val profileId = repository.currentProfileId()
                    updateContent { copy(isSearching = true, searchError = null) }
                    try {
                        val results = searchWithScope(query, request.scope)
                            .applyFavoriteOverrides(repository.favoriteOverrides.value)
                        if (!isDataLoadCurrent(generation, profileId)) return@collectLatest
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
                                    searchError = null,
                                )
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (!isDataLoadCurrent(generation, profileId)) return@collectLatest
                        _messages.tryEmit(
                            e.snackbarOr(R.string.library_error_search_snackbar, "Search failed"),
                        )
                        // A failure is not "no results": null results plus a
                        // populated searchError drive the retryable error
                        // surface instead of a false "No results found".
                        updateContent {
                            if (
                                searchQuery != query ||
                                searchScope != normaliseSearchScope(request.scope)
                            ) {
                                this
                            } else {
                                copy(
                                    searchResults = null,
                                    isSearching = false,
                                    searchError = e.searchFailure(),
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
                    val generation = libraryDataGeneration
                    val profileId = repository.currentProfileId()
                    try {
                        val favorites = repository.getStarred()
                            .applyFavoriteOverrides(overrides)
                        if (!isDataLoadCurrent(generation, profileId)) return@collectLatest
                        cachedFavorites = favorites
                        updateContent { copy(favorites = cachedFavorites) }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        // Stale favorites cache remains visible; the override
                        // pass above already updated in-place state.
                    }
                }
            }
        }
    }

    private fun observeProviderSearchAvailability() {
        viewModelScope.launch {
            combine(repository.activeProviderId, repository.capabilities) { providerId, capabilities ->
                providerId to capabilities
            }
                .distinctUntilChanged()
                .collectLatest { (providerId, capabilities) ->
                    val canSearchSpotifyCatalog = providerId == MediaId.PROVIDER_SPOTIFY &&
                        Capability.CATALOG_SEARCH in capabilities
                    val canSearchAppleMusicCatalog = providerId == MediaId.PROVIDER_APPLE_MUSIC &&
                        Capability.CATALOG_SEARCH in capabilities
                    val current = _uiState.value as? LibraryUiState.Content
                        ?: return@collectLatest
                    val nextScope = normaliseSearchScope(current.searchScope)
                    val scopeChanged = nextScope != current.searchScope
                    _uiState.value = current.copy(
                        canSearchSpotifyCatalog = canSearchSpotifyCatalog,
                        canSearchAppleMusicCatalog = canSearchAppleMusicCatalog,
                        searchScope = nextScope,
                        searchResults = current.searchResults.takeUnless { scopeChanged },
                        isSearching = if (scopeChanged) false else current.isSearching,
                        searchError = current.searchError.takeUnless { scopeChanged },
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
        LibrarySearchScope.SpotifyGlobal, LibrarySearchScope.AppleMusicGlobal -> repository.search(query)
        LibrarySearchScope.CurrentLibrary -> {
            if (isSpotifyProvider()) {
                searchSpotifySavedLibrary(query)
            } else {
                repository.searchCurrentLibrary(query)
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

    private fun observeSearchLibraryMembership() {
        viewModelScope.launch {
            val requests = Semaphore(3)
            uiState.map { state ->
                (state as? LibraryUiState.Content)
                    ?.takeIf { it.canAddToLibrary }
                    ?.searchResults?.tracks.orEmpty()
            }.distinctUntilChanged().collectLatest { tracks ->
                coroutineScope {
                    tracks.forEach { track ->
                        launch {
                            requests.withPermit {
                                repository.refreshLibraryMembership(track)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun observeProfileChanges() {
        var previousProfileId = repository.currentProfileId()
        viewModelScope.launch {
            repository.currentProfileIdFlow.distinctUntilChanged().collectLatest { profileId ->
                if (profileId != previousProfileId) {
                    previousProfileId = profileId
                    updateWorkingLibraryTrackIds()
                    reloadLibrary()
                }
            }
        }
    }

    private fun observeLibraryRevision() {
        viewModelScope.launch {
            var previous: Pair<String?, Long>? = null
            combine(repository.currentProfileIdFlow, repository.libraryRevision) { profileId, revision ->
                profileId to revision
            }.distinctUntilChanged().collectLatest { currentRevision ->
                val (profileId, revision) = currentRevision
                val needsRefresh = previous?.let { (previousProfileId, previousRevision) ->
                    previousProfileId == profileId && revision > previousRevision
                } == true
                previous = currentRevision
                if (!needsRefresh) return@collectLatest
                if (profileId != repository.currentProfileId()) return@collectLatest

                val current = _uiState.value as? LibraryUiState.Content
                cancelDataLoads()
                libraryDataGeneration += 1
                cachedArtists = null
                cachedAlbums = null
                cachedSongs = null
                if (current == null) {
                    loadInitialData()
                } else {
                    _uiState.value = current.copy(artists = null, albums = null, songs = null)
                    selectTab(current.selectedTab)
                    if (current.searchQuery.isNotBlank() &&
                        (current.searchScope == LibrarySearchScope.CurrentLibrary || current.isSearching)
                    ) {
                        retrySearch()
                    }
                }
            }
        }
    }

    private fun cancelDataLoads() {
        initialLoadJob?.cancel()
        tabLoadJob?.cancel()
        reshuffleJob?.cancel()
        tabLoadGeneration += 1
    }

    private fun isDataLoadCurrent(generation: Long, profileId: String?): Boolean =
        generation == libraryDataGeneration && profileId == repository.currentProfileId()

    /** Snackbar "Check" on a pending Apple Music add: ask the library again. */
    fun checkPendingLibraryAddition() {
        pendingLibraryCheck?.let { addSongToLibrary(it) }
    }

    fun addSongToLibrary(track: Track) {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        if (!current.canAddToLibrary) return
        val profileId = repository.currentProfileId() ?: return
        val operationKey = LibraryOperationKey(profileId, track.id)
        if (operationKey in libraryOperations) return
        if (trackLibraryStates.value[track.id] == LibraryMembership.Added) return
        updateContent { copy(libraryActionFeedback = libraryActionFeedback - track.id) }
        val operationToken = ++libraryOperationToken
        libraryOperations[operationKey] = operationToken
        updateWorkingLibraryTrackIds()
        viewModelScope.launch {
            try {
                // A Pending result rechecks the accepted addition without
                // posting it again; only confirmed membership earns a check.
                repository.addToLibrary(track)
                    .onSuccess { membership ->
                        if (repository.currentProfileId() != profileId) return@onSuccess
                        when (membership) {
                            LibraryMembership.Added -> {
                                if (pendingLibraryCheck?.id == track.id) pendingLibraryCheck = null
                                val message = shownCopy(
                                    R.string.library_feedback_added,
                                    "Added to library",
                                )
                                showLibraryActionFeedback(track.id, message)
                                _messages.tryEmit(message)
                            }
                            LibraryMembership.Pending -> {
                                pendingLibraryCheck = track
                                val message = shownCopy(
                                    R.string.library_feedback_apple_music_pending,
                                    APPLE_MUSIC_PENDING_ENGLISH,
                                )
                                showLibraryActionFeedback(track.id, message)
                                _messages.tryEmit(message)
                            }
                            else -> Unit
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        if (repository.currentProfileId() != profileId) return@onFailure
                        val message = error.toUserMessage(
                            shownCopy(
                                R.string.library_error_add_song,
                                "Couldn't add this song to the library.",
                            ),
                        )
                        showLibraryActionFeedback(track.id, message, isError = true)
                        _messages.tryEmit(message)
                    }
            } finally {
                if (libraryOperations[operationKey] == operationToken) {
                    libraryOperations.remove(operationKey)
                    updateWorkingLibraryTrackIds()
                }
            }
        }
    }

    private fun updateWorkingLibraryTrackIds() {
        val profileId = repository.currentProfileId()
        _workingLibraryTrackIds.value = libraryOperations.keys
            .filter { it.profileId == profileId }
            .mapTo(linkedSetOf()) { it.trackId }
    }

    private fun showLibraryActionFeedback(id: MediaId, message: String, isError: Boolean = false) {
        updateContent {
            copy(libraryActionFeedback = libraryActionFeedback + (id to LibraryActionFeedback(message, isError)))
        }
    }

    private fun applyFavoriteOverrides(overrides: Map<MediaId, Boolean>) {
        if (overrides.isEmpty()) return

        cachedSongs = cachedSongs?.applyFavoriteOverrides(overrides)
        cachedFavorites = cachedFavorites?.applyFavoriteOverrides(overrides)

        updateContent {
            copy(
                songs = songs?.applyFavoriteOverrides(overrides),
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
        val generation = libraryDataGeneration
        val profileId = repository.currentProfileId()
        viewModelScope.launch {
            runCatching { repository.getPlaylists() }
                .onSuccess { playlists ->
                    if (!isDataLoadCurrent(generation, profileId)) return@onSuccess
                    cachedPlaylists = playlists
                    updateContent { copy(playlists = playlists) }
                }
                .onFailure { error -> if (error is CancellationException) throw error }
        }
    }

    /**
     * Create an empty playlist on the active source and splice it into the
     * Playlists tab without a full reload.
     */
    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val generation = libraryDataGeneration
        val profileId = repository.currentProfileId()
        viewModelScope.launch {
            repository.createPlaylist(trimmed)
                .onSuccess { created ->
                    if (!isDataLoadCurrent(generation, profileId)) return@onSuccess
                    // Merge the newcomer into the cached list so the Playlists
                    // tab shows it immediately; a full refresh would wipe
                    // unrelated cached tabs.
                    val updated = (cachedPlaylists.orEmpty() + created)
                        .sortedBy { it.name.lowercase() }
                    cachedPlaylists = updated
                    updateContent { copy(playlists = updated) }
                    onPlaylistMutated()
                    _messages.tryEmit(
                        shownCopy(R.string.library_playlist_created, "Created \"$trimmed\"", trimmed),
                    )
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    if (!isDataLoadCurrent(generation, profileId)) return@onFailure
                    _messages.tryEmit(
                        it.message ?: shownCopy(
                            R.string.library_playlist_create_failed,
                            "Couldn't create \"$trimmed\"",
                            trimmed,
                        ),
                    )
                }
        }
    }

    private fun normaliseSearchScope(scope: LibrarySearchScope): LibrarySearchScope = when (scope) {
        LibrarySearchScope.CurrentLibrary -> scope
        LibrarySearchScope.SpotifyGlobal -> scope.takeIf { canSearchCatalog(MediaId.PROVIDER_SPOTIFY) }
            ?: LibrarySearchScope.CurrentLibrary
        LibrarySearchScope.AppleMusicGlobal -> scope.takeIf { canSearchCatalog(MediaId.PROVIDER_APPLE_MUSIC) }
            ?: LibrarySearchScope.CurrentLibrary
    }

    private fun canSearchCatalog(providerId: String): Boolean =
        repository.currentProviderId() == providerId &&
            Capability.CATALOG_SEARCH in repository.currentCapabilities()

    private fun canReshuffleSongs(capabilities: Set<Capability>): Boolean =
        Capability.RANDOM_SONGS in capabilities && Capability.LIBRARY_SONGS !in capabilities

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

    /** [LibraryScreen] supplies this so one-shot snackbar copy can resolve. Tests leave it unset. */
    internal fun updateCopyResources(resources: Resources) {
        copyResources = resources
    }

    private fun shownCopy(id: Int, english: String, vararg args: Any): String {
        val resources = copyResources ?: return english
        return if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
    }

    private fun Throwable.uiTextOr(fallback: UiText): UiText {
        val raw = message
        return if (raw != null) UiText.Raw(raw) else fallback
    }

    private fun Throwable.snackbarOr(id: Int, english: String): String =
        message?.takeIf { it.isNotBlank() } ?: shownCopy(id, english)

    private fun Throwable.searchFailure(): UiText {
        val resolved = shownCopy(R.string.library_error_search, "Search failed.")
        val message = toUserMessage(resolved)
        return if (message == resolved) {
            UiText.Res(R.string.library_error_search)
        } else {
            UiText.Raw(message)
        }
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

private const val APPLE_MUSIC_PENDING_ENGLISH =
    "Waiting for Apple Music" // i18n-allow: LibraryViewModelTest asserts this English

private fun LibraryTab.loadFailureRes(): Int = when (this) {
    LibraryTab.Artists -> R.string.library_error_load_tab_artists
    LibraryTab.Albums -> R.string.library_error_load_tab_albums
    LibraryTab.Songs -> R.string.library_error_load_tab_songs
    LibraryTab.Playlists -> R.string.library_error_load_tab_playlists
    LibraryTab.Favorites -> R.string.library_error_load_tab_favorites
}

private fun LibraryTab.loadFailure(): UiText = UiText.Res(loadFailureRes())

private data class LibrarySearchRequest(
    val query: String,
    val scope: LibrarySearchScope,
    /** Bumped by retry so an identical query/scope re-crosses the StateFlow's equality gate. */
    val attempt: Int = 0,
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
