package com.gpo.yoin.ui.library

import android.content.res.Resources
import android.util.Log
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
import com.gpo.yoin.data.repository.LibraryRecents
import com.gpo.yoin.data.repository.LibraryRecentsSource
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.component.FastScrollSection
import com.gpo.yoin.ui.component.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: YoinRepository,
    private val onPlaylistMutated: () -> Unit = {},
    private val sortStore: LibrarySortStore = LibrarySortStore.InMemory(),
    private val recentsSource: LibraryRecentsSource = LibraryRecentsSource.None,
    /** What was opened from Library, by the id Library lists it under ([recordOpened]). */
    private val openStore: LibraryOpenStore = LibraryOpenStore.InMemory(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where the lists are sorted: off the main thread (tests pass their own dispatcher). */
    private val sortDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** A fresh name order for each sort pass: an ICU collator isn't thread-safe ([libraryNameOrder]). */
    private val nameOrder: () -> Comparator<String> = { libraryNameOrder() },
    /**
     * The fast scroller's alphabet and calendar, fresh for each sort pass like
     * [nameOrder] ([LibraryScrollIndex.icu]). Off device android.icu is a
     * stub: a pass that cannot index keeps the sorter's order, handle only.
     */
    private val scrollIndex: () -> LibraryScrollIndex = { LibraryScrollIndex.icu() }
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

    /**
     * Where Songs comes a page at a time ([songsFromNewestAlbums]): what reads
     * the next page, set as the first one is read and kept through a failed
     * first read (its next try leaves out an album that fails again); null
     * for a list read whole, and once Songs is forgotten ([forgetSongs]).
     */
    private var songsPager: NewestAlbumSongsPager? = null

    /** What follows [cachedSongs]; [publishSongsMore] keeps the state's copy in step. */
    private var songsMoreState = LibrarySongsMore.None
    private var songsMoreJob: Job? = null
    private var cachedPlaylists: List<Playlist>? = null
    private var cachedFavorites: Starred? = null

    /** A playlist changed somewhere: the cached list still shows until a fresh one replaces it. */
    private var playlistsStale = false

    /** All's lists have each loaded or failed since they were last cleared. */
    private var allSettled = false

    /**
     * By You under Playlists is on: kept through chips and refreshes, dropped
     * with the profile ([observeProfileChanges]), never persisted.
     */
    private var playlistsByYouSelected = false

    /**
     * What the sorted lists are made of. Every change to a cached list, a sort,
     * or the profile's recents goes through here, and [observeSortedLists]
     * alone writes the sorted Artists, Albums, Playlists and All into the
     * state, so the last change always wins.
     */
    private val listInputs = MutableStateFlow(LibraryListInputs())
    private var lastSorted: SortedLibraryLists? = null
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
        .map { state ->
            when (state) {
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
        }
        // Only a change in the rows shown asks again (not the foot turning to loading).
        .distinctUntilChanged()
        .flatMapLatest(::observeNotedTracks)
        .map { ids -> ids.mapTo(linkedSetOf(), MediaId::toString) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * [trackIds]' notes, asked [NOTE_KEYS_PER_QUERY] at a time: a Songs list
     * read a page at a time has no ceiling, and SQLite before 3.32 (Android 11
     * and older) binds at most 999 values to one query.
     */
    private fun observeNotedTracks(trackIds: List<MediaId>): Flow<Set<MediaId>> {
        if (trackIds.isEmpty()) return flowOf(emptySet())
        val chunks = trackIds.chunked(NOTE_KEYS_PER_QUERY)
        if (chunks.size == 1) return repository.observeTracksWithNotes(trackIds)
        return combine(chunks.map(repository::observeTracksWithNotes)) { noted ->
            noted.flatMapTo(HashSet()) { it }
        }
    }

    init {
        observeSortedLists()
        observeRecents()
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
        forgetSongs()
        cachedPlaylists = null
        cachedFavorites = null
        playlistsStale = false
        allSettled = false
        publishLists()
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
                // The cold start reads what it always has (the artists, which
                // also tell a dead server from a live one). All's albums and
                // playlists wait for Library to come on screen
                // (ensureSelectedTabLoaded), so the app's launch costs no
                // more requests than before.
                val artists = loadArtistsFlat()
                if (!isDataLoadCurrent(generation, profileId)) return@launch
                cachedArtists = artists
                val capabilities = repository.currentCapabilities()
                val providerId = repository.currentProviderId()
                val sortSettings = sortSettings(providerId, profileId)
                val canSearchSpotifyCatalog = canSearchCatalog(MediaId.PROVIDER_SPOTIFY)
                val hasPendingSearchShortcut = pendingSearchShortcutScope != null
                val pendingScope = pendingSearchShortcutScope
                    ?.let(::normaliseSearchScope)
                    ?: LibrarySearchScope.CurrentLibrary
                pendingSearchShortcutScope = null
                _uiState.value = LibraryUiState.Content(
                    selectedTab = LibraryTab.All,
                    // null = not loaded yet; the sorted lists arrive through
                    // observeSortedLists, each view lazy-loads on first
                    // selection, and the UI shows a loading indicator instead
                    // of a misleading "No X found" empty state.
                    artists = null,
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
                    availableSearchFilters = searchFiltersFor(capabilities, pendingScope),
                    availableTabs = visibleTabs(capabilities, providerId),
                    canCreatePlaylists = Capability.PLAYLISTS_WRITE in capabilities,
                    canReshuffleSongs = canReshuffleSongs(capabilities, providerId),
                    canAddToLibrary = Capability.LIBRARY_ADD in capabilities,
                    sorts = sortSettings.sorts,
                    sortOptions = sortSettings.options,
                    playlistsByYou = PlaylistsByYou(selected = playlistsByYouSelected)
                ).withLastSorted()
                applySortSettings(sortSettings, capabilities)
                publishLists()
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
                    val capabilities = repository.currentCapabilities()
                    val sortSettings = sortSettings(repository.currentProviderId(), profileId)
                    _uiState.value = LibraryUiState.Content(
                        selectedTab = LibraryTab.All,
                        artists = null,
                        albums = null,
                        songs = null,
                        playlists = null,
                        favorites = null,
                        searchQuery = "",
                        searchResults = null,
                        isSearching = false,
                        searchScope = LibrarySearchScope.CurrentLibrary,
                        canSearchSpotifyCatalog = canSearchCatalog(MediaId.PROVIDER_SPOTIFY),
                        availableSearchFilters = searchFiltersFor(capabilities, LibrarySearchScope.CurrentLibrary),
                        availableTabs = visibleTabs(capabilities, repository.currentProviderId()),
                        canCreatePlaylists = Capability.PLAYLISTS_WRITE in capabilities,
                        canReshuffleSongs = canReshuffleSongs(capabilities, repository.currentProviderId()),
                        canAddToLibrary = Capability.LIBRARY_ADD in capabilities,
                        sorts = sortSettings.sorts,
                        sortOptions = sortSettings.options,
                        playlistsByYou = PlaylistsByYou(selected = playlistsByYouSelected)
                    ).withLastSorted()
                    applySortSettings(sortSettings, capabilities)
                    publishLists()
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
            combine(repository.activeProviderId, repository.capabilities) { providerId, capabilities ->
                providerId to capabilities
            }.distinctUntilChanged().collectLatest { (providerId, capabilities) ->
                val current = _uiState.value as? LibraryUiState.Content
                    ?: return@collectLatest
                val visible = visibleTabs(capabilities, providerId)
                // A chip the service no longer offers falls back to All, the
                // view with no chip on.
                val normalisedSelected = current.selectedTab
                    .takeIf { it == LibraryTab.All || it in visible }
                    ?: LibraryTab.All
                val sortSettings = sortSettings(providerId, repository.currentProfileId(), held = current.sorts)
                _uiState.value = current.copy(
                    availableTabs = visible,
                    selectedTab = normalisedSelected,
                    canCreatePlaylists = Capability.PLAYLISTS_WRITE in capabilities,
                    canReshuffleSongs = canReshuffleSongs(capabilities, providerId),
                    canAddToLibrary = Capability.LIBRARY_ADD in capabilities,
                    sorts = sortSettings.sorts,
                    sortOptions = sortSettings.options
                ).withSearchFilters(capabilities)
                applySortSettings(sortSettings, capabilities)
            }
        }
    }

    /**
     * Playlists disappear from the tab row when the provider doesn't support
     * reading them. Songs can be a saved-library list, the albums' songs
     * ([com.gpo.yoin.data.source.ServiceFeatures.songsFromNewestAlbums]) or a
     * provider's random sample; favorite controls retain their own capability gate.
     * Favorites is left out where the favorites are the library itself
     * ([com.gpo.yoin.data.source.ServiceFeatures.favoritesAreLibrary], Spotify):
     * its liked songs, saved albums and followed artists are the Songs, Albums
     * and Artists tabs.
     */
    private fun visibleTabs(capabilities: Set<Capability>, providerId: String?): List<LibraryTab> {
        val features = ServiceFeatureCatalog.forProvider(providerId)
        val listsSongs = Capability.LIBRARY_SONGS in capabilities || features.songsFromNewestAlbums ||
            Capability.RANDOM_SONGS in capabilities
        return LibraryTab.Chips.filter { tab ->
            when (tab) {
                LibraryTab.Playlists -> Capability.PLAYLISTS_READ in capabilities
                LibraryTab.Favorites -> Capability.FAVORITES in capabilities && !features.favoritesAreLibrary
                LibraryTab.Songs -> listsSongs
                else -> true
            }
        }
    }

    /**
     * Turns [tab]'s chip on, or every chip off with [LibraryTab.All], and loads
     * that view if it hasn't been.
     */
    fun selectTab(tab: LibraryTab) {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        if (tab != LibraryTab.All && tab !in current.availableTabs) return
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
                    LibraryTab.All -> loadAll(::isCurrent)
                    LibraryTab.Artists -> {
                        val artists = cachedArtists ?: loadArtistsFlat()
                        if (!isCurrent()) return@launch
                        cachedArtists = artists
                        publishLists()
                    }
                    LibraryTab.Albums -> {
                        val albums = cachedAlbums ?: loadLibraryAlbums()
                        if (!isCurrent()) return@launch
                        cachedAlbums = albums
                        publishLists()
                    }
                    LibraryTab.Songs -> {
                        // The albums' songs start from their first page; the
                        // list then reads on as it scrolls (loadMoreSongs). A
                        // first page that failed keeps its pager, so the chip's
                        // next tap gets past an album that fails again.
                        val firstPage = cachedSongs == null && songsFromNewestAlbums()
                        val pager = if (firstPage) {
                            songsPager ?: newestAlbumSongsPager().also { songsPager = it }
                        } else {
                            null
                        }
                        val songs = cachedSongs ?: run {
                            val loaded = when {
                                pager != null -> pager.next()
                                Capability.LIBRARY_SONGS in repository.currentCapabilities() ->
                                    repository.getLibrarySongs(size = LIBRARY_SONGS_SIZE)
                                else -> repository.getRandomSongs(size = 50)
                            }
                            loaded.applySongsOverrides(repository.favoriteOverrides.value)
                        }
                        if (!isCurrent()) return@launch
                        cachedSongs = songs
                        if (pager != null) songsMoreState = pager.moreState()
                        updateContent { copy(songs = cachedSongs.orEmpty(), songsMore = songsMoreState) }
                    }
                    LibraryTab.Playlists -> {
                        val playlists = currentPlaylists() ?: loadPlaylists()
                        if (!isCurrent()) return@launch
                        cachedPlaylists = playlists
                        publishLists()
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
                // All with nothing to show and no view to step back to: the
                // page's own error, with Retry.
                val nowhereBack = tab == LibraryTab.All && previousTab == LibraryTab.All
                if (content == null || nowhereBack) {
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
     * All's lists side by side. Each joins All as soon as it arrives (the
     * artists the cold start read are there at once), so the slowest list
     * never holds the others back. One that fails leaves All without it (a
     * snackbar says so; its chip loads it again); only when none loads does
     * the view fail as a whole.
     */
    private suspend fun loadAll(isCurrent: () -> Boolean) {
        val holdsPlaylists = Capability.PLAYLISTS_READ in repository.currentCapabilities()
        val spotify = isSpotifyProvider()
        publishLists()
        // Spotify: the rows on hand or the synced cache as it is, never a
        // freshness-checked read, so opening Library never sets off a sync
        // (the cold start refreshed the cache when it was due). That holds for
        // stale playlists too: a playlist write marks the whole cache stale,
        // so reading them "fresh" here would sync every list. The chips then
        // reuse these rows; only the Playlists chip, after a playlist write,
        // reads its list fresh, as it always has.
        val spotifyCache = if (spotify && (cachedAlbums == null || cachedPlaylists == null)) {
            attempt { repository.getSpotifyLocalSearchSnapshot() }.getOrNull()
        } else {
            null
        }
        val loads = coroutineScope {
            val artists = allPart(isCurrent, { cachedArtists ?: loadArtistsFlat() }) { cachedArtists = it }
            val albums = allPart(isCurrent, { cachedAlbums ?: spotifyCache?.albums ?: loadLibraryAlbums() }) {
                cachedAlbums = it
            }
            val playlists = if (holdsPlaylists) {
                val load: suspend () -> List<Playlist> = if (spotify) {
                    { cachedPlaylists ?: spotifyCache?.playlists ?: loadPlaylists() }
                } else {
                    { currentPlaylists() ?: loadPlaylists() }
                }
                allPart(isCurrent, load) { cachedPlaylists = it }
            } else {
                null
            }
            Triple(artists.await(), albums.await(), playlists?.await())
        }
        if (!isCurrent()) return
        val (artists, albums, playlists) = loads
        val failures = listOfNotNull(
            artists.exceptionOrNull()?.let { LibraryTab.Artists to it },
            albums.exceptionOrNull()?.let { LibraryTab.Albums to it },
            playlists?.exceptionOrNull()?.let { LibraryTab.Playlists to it }
        )
        if (listOfNotNull(artists, albums, playlists).none { it.isSuccess }) throw failures.first().second
        allSettled = true
        publishLists()
        failures.firstOrNull()?.let { (kind, error) ->
            _messages.tryEmit(error.snackbarOr(kind.loadFailureRes(), "Failed to load ${kind.name}"))
        }
    }

    /** One of All's lists, loading: kept and shown the moment it arrives, while the others are on their way. */
    private fun <T> CoroutineScope.allPart(
        isCurrent: () -> Boolean,
        load: suspend () -> T,
        keep: (T) -> Unit
    ): Deferred<Result<T>> = async {
        attempt(load).onSuccess { loaded ->
            if (isCurrent()) {
                keep(loaded)
                publishLists()
            }
        }
    }

    /** Recently added first, as every service lists it; Library re-sorts it ([LibrarySorter]). */
    private suspend fun loadLibraryAlbums(): List<Album> = repository.getAlbumList("newest", size = 500)

    private suspend fun loadPlaylists(): List<Playlist> = repository.getPlaylists().also { playlistsStale = false }

    /** The cached playlists, unless a change elsewhere made them stale. */
    private fun currentPlaylists(): List<Playlist>? = cachedPlaylists?.takeUnless { playlistsStale }

    /**
     * Library came on screen: loads the view it shows if it hasn't loaded yet.
     * All's albums and playlists wait for this rather than the cold start.
     */
    fun ensureSelectedTabLoaded() {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        if (tabLoadJob?.isActive == true) return
        val loaded = when (current.selectedTab) {
            LibraryTab.All -> allSettled
            LibraryTab.Artists -> cachedArtists != null
            LibraryTab.Albums -> cachedAlbums != null
            LibraryTab.Songs -> cachedSongs != null
            LibraryTab.Playlists -> cachedPlaylists != null
            LibraryTab.Favorites -> cachedFavorites != null
        }
        if (!loaded) selectTab(current.selectedTab)
    }

    /**
     * Reads Songs' next page where Songs comes a page at a time
     * ([songsFromNewestAlbums]): as the list nears its end, or from the retry
     * button at its foot after a read failed. One read at a time and none past
     * the end; a refresh or another profile cancels it ([cancelDataLoads]).
     */
    fun loadMoreSongs() {
        val pager = songsPager ?: return
        if (songsMoreState != LibrarySongsMore.Available && songsMoreState != LibrarySongsMore.Failed) return
        val generation = libraryDataGeneration
        val profileId = repository.currentProfileId()
        fun isCurrent(): Boolean = isDataLoadCurrent(generation, profileId) && songsPager === pager
        publishSongsMore(LibrarySongsMore.Loading)
        songsMoreJob = viewModelScope.launch {
            try {
                val more = pager.next().applySongsOverrides(repository.favoriteOverrides.value)
                if (!isCurrent()) return@launch
                cachedSongs = cachedSongs.orEmpty() + more
                songsMoreState = pager.moreState()
                updateContent { copy(songs = cachedSongs, songsMore = songsMoreState) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!isCurrent()) return@launch
                // The rows stay; the foot's retry reads the same page again.
                publishSongsMore(LibrarySongsMore.Failed)
            }
        }
    }

    private fun publishSongsMore(more: LibrarySongsMore) {
        songsMoreState = more
        updateContent { copy(songsMore = more) }
    }

    /** Songs as if never read: the next visit starts again from the top. */
    private fun forgetSongs() {
        cachedSongs = null
        songsPager = null
        songsMoreState = LibrarySongsMore.None
    }

    private fun NewestAlbumSongsPager.moreState(): LibrarySongsMore =
        if (reachedEnd) LibrarySongsMore.None else LibrarySongsMore.Available

    /**
     * The service lists no songs of its own: Songs is its albums' songs, newest
     * album first ([com.gpo.yoin.data.source.ServiceFeatures.songsFromNewestAlbums], Subsonic).
     */
    private fun songsFromNewestAlbums(): Boolean =
        ServiceFeatureCatalog.forProvider(repository.currentProviderId()).songsFromNewestAlbums

    /** Albums by when they joined the library, opened through the album page's cache. */
    private fun newestAlbumSongsPager(): NewestAlbumSongsPager = NewestAlbumSongsPager(
        loadAlbums = { offset, size -> repository.getAlbumList("newest", size = size, offset = offset) },
        loadAlbum = { id -> repository.getAlbum(id) }
    )

    /**
     * [id] was opened from Library: Recents moves it up by the id Library
     * lists it under, whatever the page it opens resolves it to (an Apple
     * Music library album opens as its catalog album, and records its visit
     * by that id), and for playlists, whose pages record no visit.
     */
    fun recordOpened(kind: LibraryOpenKind, id: String) {
        val mediaId = MediaId.parseOrNull(id) ?: return
        val profileId = repository.currentProfileId() ?: return
        val at = clock()
        viewModelScope.launch {
            try {
                openStore.recordOpened(profileId, mediaId.provider, kind, mediaId.rawId, at)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Only an order hint: the page opens regardless.
            }
        }
    }

    /** Orders [view] by [sort] and remembers it for this profile. */
    fun selectSort(view: LibraryTab, sort: LibrarySort) {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        if (sort !in current.sortOptions[view].orEmpty() || current.sorts[view] == sort) return
        repository.currentProfileId()?.let { profileId -> sortStore.setSort(profileId, view, sort) }
        val sorts = current.sorts + (view to sort)
        _uiState.value = current.copy(sorts = sorts)
        listInputs.update { it.copy(sorts = sorts) }
    }

    /**
     * Turns Playlists' By You sub-chip on or off. It only turns on while it
     * shows ([PlaylistsByYou.available]); off is always allowed.
     */
    fun selectPlaylistsByYou(selected: Boolean) {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        if (selected && !current.playlistsByYou.available) return
        playlistsByYouSelected = selected
        _uiState.value = current.copy(playlistsByYou = current.playlistsByYou.copy(selected = selected))
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
                searchFilter = LibrarySearchFilter.All,
                // The shortcut id is a one-shot UI trigger. Leaving it set
                // would reopen Search when Library remounts after Home.
                searchFocusRequestId = 0L,
            ).withSearchFilters(repository.currentCapabilities())
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
            searchFilter = LibrarySearchFilter.All,
            searchFocusRequestId = nextSearchFocusRequestId(),
        ).withSearchFilters(repository.currentCapabilities())
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
        ).withSearchFilters(repository.currentCapabilities())
        searchRequestFlow.value = LibrarySearchRequest(current.searchQuery, effectiveScope)
    }

    /**
     * Lists one result type (or All again). Only what the search already
     * returned is shown, so nothing is fetched; a type the provider can't
     * search in this scope is ignored.
     */
    fun selectSearchFilter(filter: LibrarySearchFilter) {
        updateContent {
            if (filter == searchFilter || filter !in availableSearchFilters) this else copy(searchFilter = filter)
        }
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
                searchFilter = LibrarySearchFilter.All,
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

    /** Hands the cached lists to [observeSortedLists]. */
    private fun publishLists() {
        listInputs.update {
            it.copy(
                generation = libraryDataGeneration,
                artists = cachedArtists,
                albums = cachedAlbums,
                playlists = cachedPlaylists,
                allSettled = allSettled
            )
        }
    }

    /**
     * The one writer of the sorted Artists, Albums, Playlists and All. Sorting
     * runs off the main thread; a newer input cancels an older pass.
     */
    private fun observeSortedLists() {
        viewModelScope.launch {
            listInputs.collectLatest { inputs ->
                val sorted = withContext(sortDispatcher) { sortLists(inputs) }
                if (inputs.generation != libraryDataGeneration) return@collectLatest
                lastSorted = sorted
                updateContent { withSorted(sorted) }
            }
        }
    }

    private fun sortLists(inputs: LibraryListInputs): SortedLibraryLists {
        val sorter = LibrarySorter(nameOrder(), inputs.recents, inputs.ignoredArticles)
        val indexer = LibraryScrollIndexer(scrollIndex(), inputs.ignoredArticles)
        fun sortOf(view: LibraryTab) = inputs.sorts[view] ?: LibrarySort.Recents
        val sections = mutableMapOf<LibraryTab, List<FastScrollSection>>()

        // Sorted, then put in the fast scroller's order with its sections.
        fun <T> indexed(view: LibraryTab, list: List<T>, index: (List<T>) -> LibraryIndex.Sorted<T>): List<T> {
            val result = indexedOrPlain(list, index)
            sections[view] = result.sections
            return result.items
        }
        val artists = inputs.artists?.let { list ->
            val sort = sortOf(LibraryTab.Artists)
            indexed(LibraryTab.Artists, sorter.artists(list, sort)) { indexer.artists(it, sort) }
        }
        val albums = inputs.albums?.let { list ->
            val sort = sortOf(LibraryTab.Albums)
            indexed(LibraryTab.Albums, sorter.albums(list, sort)) { indexer.albums(it, sort) }
        }
        val playlists = inputs.playlists?.let { list ->
            val sort = sortOf(LibraryTab.Playlists)
            // No scroller over Playlists: only the same A–Z as the other views.
            indexedOrPlain(sorter.playlists(list, sort)) { indexer.playlists(it, sort) }.items
        }
        val all = allOf(sorter, inputs, sortOf(LibraryTab.All))?.let { list ->
            val sort = sortOf(LibraryTab.All)
            indexed(LibraryTab.All, list) { indexer.all(it, sort, albums = inputs.albums.orEmpty()) }
        }
        return SortedLibraryLists(
            generation = inputs.generation,
            artists = artists,
            albums = albums,
            playlists = playlists,
            all = all,
            sections = sections,
            playlistsMixed = inputs.playlists?.hasMixedOwnership() == true
        )
    }

    /**
     * [sorted] through [index], or as it is with no sections when the index
     * can't be built (ICU missing data on a device, a stub off device): the
     * lists never wait on the scroller's ticks.
     */
    private fun <T> indexedOrPlain(
        sorted: List<T>,
        index: (List<T>) -> LibraryIndex.Sorted<T>
    ): LibraryIndex.Sorted<T> = try {
        index(sorted)
    } catch (e: RuntimeException) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Library fast-scroller index failed; showing the sorted list without sections", e)
        LibraryIndex.Sorted(sorted, emptyList())
    }

    /**
     * All from whatever has loaded, never songs: a list still on its way, or
     * one that failed, is left out, and one arriving later moves in on the
     * grid's item springs. Null (the loading indicator) only while nothing
     * has loaded; empty (the empty state) only once every list settled.
     */
    private fun allOf(sorter: LibrarySorter, inputs: LibraryListInputs, sort: LibrarySort): List<LibraryItem>? {
        val all = sorter.all(
            artists = inputs.artists.orEmpty(),
            albums = inputs.albums.orEmpty(),
            playlists = if (inputs.allHoldsPlaylists) inputs.playlists.orEmpty() else emptyList(),
            sort = sort
        )
        return all.takeIf { inputs.allSettled || it.isNotEmpty() }
    }

    private fun LibraryUiState.Content.withSorted(sorted: SortedLibraryLists): LibraryUiState.Content = copy(
        artists = sorted.artists,
        albums = sorted.albums,
        playlists = sorted.playlists,
        allItems = sorted.all,
        scrollSections = sorted.sections,
        // The sub-chip follows the list: it goes when the list stops being
        // mixed (and comes back on, as it was, when it mixes again).
        playlistsByYou = PlaylistsByYou(available = sorted.playlistsMixed, selected = playlistsByYouSelected)
    )

    /** A Content built anew starts from the latest sorted lists of this generation. */
    private fun LibraryUiState.Content.withLastSorted(): LibraryUiState.Content =
        lastSorted?.takeIf { it.generation == libraryDataGeneration }?.let { withSorted(it) } ?: this

    /**
     * This profile's Yoin records of what it opened and played, for Recents:
     * the visit and play rows, and what was opened from Library itself.
     */
    private fun observeRecents() {
        viewModelScope.launch {
            combine(repository.currentProfileIdFlow, repository.activeProviderId) { profileId, providerId ->
                profileId to providerId
            }
                .distinctUntilChanged()
                .flatMapLatest { (profileId, providerId) ->
                    if (profileId.isNullOrBlank() || providerId == null) {
                        flowOf(LibraryRecents.None)
                    } else {
                        combine(
                            recentsSource.observe(profileId, providerId),
                            openStore.observe(profileId, providerId),
                            LibraryRecents::latestWith
                        )
                    }
                }
                .distinctUntilChanged()
                .collect { recents -> listInputs.update { it.copy(recents = recents) } }
        }
    }

    /**
     * The orders each view offers on [providerId] and the one each shows: the
     * profile's choice when it is still offered, else the default (Recents).
     */
    private fun sortSettings(
        providerId: String?,
        profileId: String?,
        held: Map<LibraryTab, LibrarySort> = emptyMap()
    ): LibrarySortSettings {
        val features = ServiceFeatureCatalog.forProvider(providerId)
        val options = LibraryTab.entries
            .associateWith { view -> librarySortOptions(view, features) }
            .filterValues { it.isNotEmpty() }
        val sorts = options.mapValues { (view, offered) ->
            val chosen = profileId?.let { sortStore.sortFor(it, view) } ?: held[view]
            chosen?.takeIf { it in offered } ?: offered.first()
        }
        val articles = if (features.sortIgnoresArticles) LibraryIgnoredArticles else emptyList()
        return LibrarySortSettings(options = options, sorts = sorts, ignoredArticles = articles)
    }

    private fun applySortSettings(settings: LibrarySortSettings, capabilities: Set<Capability>) {
        listInputs.update {
            it.copy(
                sorts = settings.sorts,
                ignoredArticles = settings.ignoredArticles,
                allHoldsPlaylists = Capability.PLAYLISTS_READ in capabilities
            )
        }
    }

    /** [block]'s value or failure; a cancellation still cancels. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private inline fun updateContent(
        transform: LibraryUiState.Content.() -> LibraryUiState.Content,
    ) {
        val current = _uiState.value as? LibraryUiState.Content ?: return
        _uiState.value = current.transform()
    }

    /** Re-derives the type chips for this scope and drops a selected type they no longer offer. */
    private fun LibraryUiState.Content.withSearchFilters(capabilities: Set<Capability>): LibraryUiState.Content {
        val available = searchFiltersFor(capabilities, searchScope)
        return copy(availableSearchFilters = available, searchFilter = searchFilter.normalisedTo(available))
    }

    private fun observeFavoriteOverrides() {
        viewModelScope.launch {
            var previous: Map<MediaId, Boolean>? = null
            repository.favoriteOverrides.collectLatest { overrides ->
                // The value already there on subscribe changed nothing the
                // first loads didn't read.
                val changed = previous != null && previous != overrides
                previous = overrides
                applyFavoriteOverrides(overrides)
                val current = _uiState.value as? LibraryUiState.Content ?: return@collectLatest
                // The Favorites tab re-reads live: getStarred() returns a
                // stable ordered set, so a newly-favorited track inserts cleanly
                // and an un-favorited one drops. So do Songs and Artists where
                // they are the liked and followed lists (Spotify): a like lands
                // on top, an unlike fades out, a follow or unfollow comes and
                // goes. Any other Songs is NOT re-read: the albums' songs
                // (Subsonic) would lose the pages already read, and a random
                // sample (getRandomSongs does .shuffled().take(50)) would
                // reshuffle on every toggle; their heart icons are already
                // updated in-place by applyFavoriteOverrides above.
                if (changed && favoritesAreLibrary()) {
                    rereadCachedLibrary(overrides)
                }
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

    /**
     * The service's favorites are its library
     * ([com.gpo.yoin.data.source.ServiceFeatures.favoritesAreLibrary], Spotify).
     */
    private fun favoritesAreLibrary(): Boolean =
        ServiceFeatureCatalog.forProvider(repository.currentProviderId()).favoritesAreLibrary

    /**
     * Songs is the service's liked list ([favoritesAreLibrary]): a like adds a
     * row and an unlike takes one away, unlike a random sample.
     */
    private fun songsAreLikedSongs(): Boolean =
        favoritesAreLibrary() && Capability.LIBRARY_SONGS in repository.currentCapabilities()

    /**
     * Re-reads the liked Songs and followed Artists a heart or follow just
     * changed, on any tab, from the synced cache only — the write filed its
     * row there before it published the override. No freshness check, so a
     * heart never sets off a library sync, wherever it was tapped (Now
     * Playing, an album, the notification). Only lists already loaded: a
     * first visit loads through [selectTab].
     */
    private suspend fun rereadCachedLibrary(overrides: Map<MediaId, Boolean>) {
        val generation = libraryDataGeneration
        val profileId = repository.currentProfileId()
        try {
            val songs = cachedSongs?.takeIf { songsAreLikedSongs() }?.let {
                repository.readCachedLikedSongs(size = LIBRARY_SONGS_SIZE)?.applySongsOverrides(overrides)
            }
            val artists = cachedArtists?.let {
                repository.readCachedFollowedArtists()?.flatMap(ArtistIndex::artists)
            }
            if (!isDataLoadCurrent(generation, profileId)) return
            if (songs != null) {
                cachedSongs = songs
                updateContent { copy(songs = songs) }
            }
            if (artists != null) {
                cachedArtists = artists
                publishLists()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // The lists stay; the override pass already took an unlike out.
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
                    ).withSearchFilters(capabilities)
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
        // Every artist the library holds, not only the followed ones the
        // Artists tab lists: the saved albums' and liked songs' artists too.
        val artists = snapshot.artists
            .plus(favorites.artists)
            .distinctBy(Artist::id)
        val albums = (cachedAlbums ?: snapshot.albums)
            .plus(favorites.albums)
            .distinctBy(Album::id)
        val songs = snapshot.tracks.ifEmpty { favorites.tracks }
        val playlists = cachedPlaylists ?: snapshot.playlists

        // Every match, not a page: the All list keeps SEARCH_ALL_ROWS_PER_TYPE
        // of each type (shownFor), a type filter shows the rest. The snapshot
        // itself holds at most 200 of each type.
        return SearchResults(
            artists = artists.filter { artist -> artist.matches(needle) },
            albums = albums.filter { album -> album.matches(needle) },
            tracks = songs.filter { track -> track.matches(needle) },
            playlists = playlists.filter { playlist -> playlist.matches(needle) },
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
                    playlistsByYouSelected = false
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
                forgetSongs()
                allSettled = false
                publishLists()
                if (current == null) {
                    loadInitialData()
                } else {
                    _uiState.value = current.copy(
                        artists = null,
                        albums = null,
                        songs = null,
                        songsMore = LibrarySongsMore.None,
                        allItems = null
                    )
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
        songsMoreJob?.cancel()
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

        cachedSongs = cachedSongs?.applySongsOverrides(overrides)
        cachedFavorites = cachedFavorites?.applyFavoriteOverrides(overrides)

        updateContent {
            copy(
                songs = songs?.applySongsOverrides(overrides),
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

    /** The Songs tab's [applyFavoriteOverrides]: on a liked list an unliked song also leaves it. */
    private fun List<Track>.applySongsOverrides(overrides: Map<MediaId, Boolean>): List<Track> {
        val applied = applyFavoriteOverrides(overrides)
        return if (songsAreLikedSongs()) applied.filter(Track::isStarred) else applied
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

    /**
     * A playlist changed elsewhere. The lists that show playlists (Playlists,
     * All) keep the old ones on screen until the fresh list replaces them;
     * otherwise the next visit reads them again. Spotify's All doesn't read
     * them again: the write marked the synced cache stale, so a fresh read
     * would sync the whole library (see [loadAll]); its rows stay, and the
     * Playlists chip reads the list fresh, as it always has.
     */
    fun invalidatePlaylists() {
        playlistsStale = true
        val current = _uiState.value as? LibraryUiState.Content ?: return
        val rereads = when (current.selectedTab) {
            LibraryTab.Playlists -> true
            LibraryTab.All -> !isSpotifyProvider()
            else -> false
        }
        if (!rereads || cachedPlaylists == null) return
        val generation = libraryDataGeneration
        val profileId = repository.currentProfileId()
        viewModelScope.launch {
            runCatching { loadPlaylists() }
                .onSuccess { playlists ->
                    if (!isDataLoadCurrent(generation, profileId)) return@onSuccess
                    cachedPlaylists = playlists
                    publishLists()
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
                    // Placed by the Playlists and All sorts like any other.
                    cachedPlaylists = cachedPlaylists.orEmpty() + created
                    publishLists()
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

    /** Songs is a random sample: neither a saved list nor the albums' songs ([songsFromNewestAlbums]). */
    private fun canReshuffleSongs(capabilities: Set<Capability>, providerId: String?): Boolean =
        Capability.RANDOM_SONGS in capabilities && Capability.LIBRARY_SONGS !in capabilities &&
            !ServiceFeatureCatalog.forProvider(providerId).songsFromNewestAlbums

    private fun isSpotifyProvider(): Boolean =
        repository.currentProviderId() == MediaId.PROVIDER_SPOTIFY

    private fun hasSpotifyTabCache(tab: LibraryTab): Boolean = when (tab) {
        LibraryTab.All -> !cachedArtists.isNullOrEmpty() || !cachedAlbums.isNullOrEmpty() ||
            !cachedPlaylists.isNullOrEmpty()
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
                sortStore = container.librarySortStore,
                recentsSource = container.libraryRecentsSource,
                openStore = container.libraryOpenStore
            ) as T
    }

    companion object {
        private const val TAG = "LibraryViewModel"
        private const val SEARCH_DEBOUNCE_MS = 300L

        /** Max wait for the active [MusicSource] to resolve on a cold start
         *  before the first library load gives up (see [loadInitialData]). */
        private const val ACTIVE_SOURCE_WAIT_MS = 4_000L
        /** Songs read for a library list (Apple's saved songs, Spotify's likes). */
        private const val LIBRARY_SONGS_SIZE = 500

        /** Well under SQLite's old 999-value limit, with room for the query's own values. */
        private const val NOTE_KEYS_PER_QUERY = 500
    }
}

private const val APPLE_MUSIC_PENDING_ENGLISH =
    "Waiting for Apple Music" // i18n-allow: LibraryViewModelTest asserts this English

private fun LibraryTab.loadFailureRes(): Int = when (this) {
    LibraryTab.All -> R.string.library_error_load_library
    LibraryTab.Artists -> R.string.library_error_load_tab_artists
    LibraryTab.Albums -> R.string.library_error_load_tab_albums
    LibraryTab.Songs -> R.string.library_error_load_tab_songs
    LibraryTab.Playlists -> R.string.library_error_load_tab_playlists
    LibraryTab.Favorites -> R.string.library_error_load_tab_favorites
}

private fun LibraryTab.loadFailure(): UiText = UiText.Res(loadFailureRes())

/** What [LibraryViewModel]'s sort pass reads; see its `listInputs`. */
private data class LibraryListInputs(
    val generation: Long = 0L,
    val artists: List<Artist>? = null,
    val albums: List<Album>? = null,
    val playlists: List<Playlist>? = null,
    val allSettled: Boolean = false,
    val allHoldsPlaylists: Boolean = false,
    val sorts: Map<LibraryTab, LibrarySort> = emptyMap(),
    val recents: LibraryRecents = LibraryRecents.None,
    val ignoredArticles: List<String> = emptyList()
)

private class SortedLibraryLists(
    val generation: Long,
    val artists: List<Artist>?,
    val albums: List<Album>?,
    val playlists: List<Playlist>?,
    val all: List<LibraryItem>?,
    /** The fast scroller's sections over [artists], [albums] and [all], as listed. */
    val sections: Map<LibraryTab, List<FastScrollSection>>,
    /** The playlists' [hasMixedOwnership]: Playlists shows By You. */
    val playlistsMixed: Boolean
)

private class LibrarySortSettings(
    val options: Map<LibraryTab, List<LibrarySort>>,
    val sorts: Map<LibraryTab, LibrarySort>,
    val ignoredArticles: List<String>
)

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
