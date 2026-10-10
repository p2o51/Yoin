package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.data.source.ServiceFeatures
import com.gpo.yoin.data.source.spotify.SpotifyLibrarySyncCoordinator
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySearchFilterTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    // ── Capability gate ────────────────────────────────────────────────

    @Test
    fun should_offerPlaylistsInBothScopes_when_providerSearchesPlaylists() {
        listOf(ServiceFeatureCatalog.spotify, ServiceFeatureCatalog.appleMusic).forEach { service ->
            LibrarySearchScope.entries.forEach { scope ->
                assertEquals(
                    "${service.id} in $scope",
                    LibrarySearchFilter.entries,
                    searchFiltersFor(service.capabilities, scope)
                )
            }
        }
    }

    @Test
    fun should_leaveOutPlaylists_when_providerCannotSearchPlaylists() {
        assertEquals(
            listOf(
                LibrarySearchFilter.All,
                LibrarySearchFilter.Artists,
                LibrarySearchFilter.Albums,
                LibrarySearchFilter.Songs
            ),
            searchFiltersFor(ServiceFeatureCatalog.subsonic.capabilities, LibrarySearchScope.CurrentLibrary)
        )
    }

    @Test
    fun should_leaveOutLibraryPlaylists_when_libraryHoldsNoPlaylists() {
        val capabilities = setOf(Capability.CATALOG_SEARCH, Capability.SEARCH_PLAYLISTS)

        assertTrue(LibrarySearchFilter.Playlists in searchFiltersFor(capabilities, LibrarySearchScope.SpotifyGlobal))
        assertFalse(LibrarySearchFilter.Playlists in searchFiltersFor(capabilities, LibrarySearchScope.CurrentLibrary))
    }

    @Test
    fun should_fallBackToAll_when_filterIsNotOffered() {
        val offered = listOf(LibrarySearchFilter.All, LibrarySearchFilter.Songs)

        assertEquals(LibrarySearchFilter.Songs, LibrarySearchFilter.Songs.normalisedTo(offered))
        assertEquals(LibrarySearchFilter.All, LibrarySearchFilter.Playlists.normalisedTo(offered))
    }

    // ── Filtering ──────────────────────────────────────────────────────

    @Test
    fun should_listOnlyThatType_when_typeFilterSelected() {
        val results = SearchResults(
            tracks = tracks(2),
            artists = listOf(artist("a")),
            playlists = listOf(playlist("p"))
        )

        assertEquals(SearchResults(tracks = results.tracks), results.shownFor(LibrarySearchFilter.Songs))
        assertEquals(SearchResults(artists = results.artists), results.shownFor(LibrarySearchFilter.Artists))
        assertEquals(SearchResults(playlists = results.playlists), results.shownFor(LibrarySearchFilter.Playlists))
        assertTrue(results.shownFor(LibrarySearchFilter.Albums).isEmpty)
        assertEquals(results, results.shownFor(LibrarySearchFilter.All))
    }

    @Test
    fun should_capEachTypeInAllButNotInItsOwnFilter_when_searchMatchesMany() {
        val results = SearchResults(tracks = tracks(SEARCH_ALL_ROWS_PER_TYPE + 25))

        assertEquals(SEARCH_ALL_ROWS_PER_TYPE, results.shownFor(LibrarySearchFilter.All).tracks.size)
        assertEquals(SEARCH_ALL_ROWS_PER_TYPE + 25, results.shownFor(LibrarySearchFilter.Songs).tracks.size)
    }

    // ── Scroll memory ──────────────────────────────────────────────────

    @Test
    fun should_keepOnePositionPerScopeAndFilter_when_queryStaysTheSame() {
        val memory = LibrarySearchScrollMemory()
        val all = memory.listState("q", LibrarySearchScope.SpotifyGlobal, LibrarySearchFilter.All)

        assertSame(all, memory.listState("q", LibrarySearchScope.SpotifyGlobal, LibrarySearchFilter.All))
        assertNotSame(all, memory.listState("q", LibrarySearchScope.SpotifyGlobal, LibrarySearchFilter.Songs))
        assertNotSame(all, memory.listState("q", LibrarySearchScope.CurrentLibrary, LibrarySearchFilter.All))
        assertSame(all, memory.listState("q", LibrarySearchScope.SpotifyGlobal, LibrarySearchFilter.All))
    }

    @Test
    fun should_forgetPositions_when_anotherQueryIsShown() {
        val memory = LibrarySearchScrollMemory()
        val list = memory.listState("q", LibrarySearchScope.CurrentLibrary, LibrarySearchFilter.Songs)
        val grid = memory.gridState("q", LibrarySearchScope.CurrentLibrary, LibrarySearchFilter.Albums)

        memory.listState("qu", LibrarySearchScope.CurrentLibrary, LibrarySearchFilter.All)

        assertNotSame(list, memory.listState("q", LibrarySearchScope.CurrentLibrary, LibrarySearchFilter.Songs))
        assertNotSame(grid, memory.gridState("q", LibrarySearchScope.CurrentLibrary, LibrarySearchFilter.Albums))
    }

    @Test
    fun should_startAtTheTop_when_sameQueryIsSearchedAfterTheSearchEnded() {
        val memory = LibrarySearchScrollMemory()
        val list = memory.listState("q", LibrarySearchScope.CurrentLibrary, LibrarySearchFilter.Songs)
        val grid = memory.gridState("q", LibrarySearchScope.CurrentLibrary, LibrarySearchFilter.Albums)

        memory.onSearchQuery("")

        assertNotSame(list, memory.listState("q", LibrarySearchScope.CurrentLibrary, LibrarySearchFilter.Songs))
        assertNotSame(grid, memory.gridState("q", LibrarySearchScope.CurrentLibrary, LibrarySearchFilter.Albums))
    }

    @Test
    fun should_keepPositions_when_typedQueryIsNotBlank() {
        val memory = LibrarySearchScrollMemory()
        val list = memory.listState("q", LibrarySearchScope.CurrentLibrary, LibrarySearchFilter.All)

        // Typing ahead of the shown results, or a Wide detail column that
        // kept the query on collapse.
        memory.onSearchQuery("qu")
        memory.onSearchQuery("q")

        assertSame(list, memory.listState("q", LibrarySearchScope.CurrentLibrary, LibrarySearchFilter.All))
    }

    // ── ViewModel write points ────────────────────────────────────────

    @Test
    fun should_offerChipsByCapability_when_libraryLoads() = runTest {
        val subsonic = viewModelFor(ServiceFeatureCatalog.subsonic)
        val apple = viewModelFor(ServiceFeatureCatalog.appleMusic)
        advanceUntilIdle()

        assertFalse(LibrarySearchFilter.Playlists in subsonic.content().availableSearchFilters)
        assertEquals(LibrarySearchFilter.entries, apple.content().availableSearchFilters)
        assertEquals(LibrarySearchFilter.All, apple.content().searchFilter)
    }

    @Test
    fun should_ignoreFilter_when_providerCannotSearchThatType() = runTest {
        val viewModel = viewModelFor(ServiceFeatureCatalog.subsonic)
        advanceUntilIdle()

        viewModel.selectSearchFilter(LibrarySearchFilter.Playlists)

        assertEquals(LibrarySearchFilter.All, viewModel.content().searchFilter)
    }

    @Test
    fun should_keepFilterWithoutSearchingAgain_when_typeSelectedOrQueryChanges() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities)
        coEvery { repository.searchCurrentLibrary(any()) } returns SearchResults(tracks = tracks(1))
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()
        viewModel.search("song")
        advanceUntilIdle()

        viewModel.selectSearchFilter(LibrarySearchFilter.Songs)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.searchCurrentLibrary(any()) }

        viewModel.search("songs")
        advanceUntilIdle()
        assertEquals(LibrarySearchFilter.Songs, viewModel.content().searchFilter)
    }

    @Test
    fun should_resetFilterToAll_when_searchIsCleared() = runTest {
        val viewModel = viewModelFor(ServiceFeatureCatalog.subsonic)
        advanceUntilIdle()
        viewModel.selectSearchFilter(LibrarySearchFilter.Albums)

        viewModel.clearSearch()

        assertEquals(LibrarySearchFilter.All, viewModel.content().searchFilter)
    }

    @Test
    fun should_resetFilterToAll_when_libraryHomeIsShown() = runTest {
        val viewModel = viewModelFor(ServiceFeatureCatalog.subsonic)
        advanceUntilIdle()
        viewModel.selectSearchFilter(LibrarySearchFilter.Albums)

        viewModel.showLibraryHome()

        assertEquals(LibrarySearchFilter.All, viewModel.content().searchFilter)
    }

    @Test
    fun should_resetFilterToAll_when_searchShortcutOpens() = runTest {
        val viewModel = viewModelFor(ServiceFeatureCatalog.spotify)
        advanceUntilIdle()
        viewModel.selectSearchFilter(LibrarySearchFilter.Playlists)

        viewModel.openSearchShortcut(LibrarySearchScope.SpotifyGlobal)

        val state = viewModel.content()
        assertEquals(LibrarySearchScope.SpotifyGlobal, state.searchScope)
        assertEquals(LibrarySearchFilter.All, state.searchFilter)
    }

    @Test
    fun should_fallBackToAll_when_newScopeCannotSearchThatType() = runTest {
        // Catalog playlists, but no saved playlists to search in the library.
        val capabilities = setOf(Capability.CATALOG_SEARCH, Capability.SEARCH_PLAYLISTS)
        val viewModel = LibraryViewModel(repositoryFor(MediaId.PROVIDER_SPOTIFY, capabilities))
        advanceUntilIdle()
        viewModel.selectSearchScope(LibrarySearchScope.SpotifyGlobal)
        viewModel.selectSearchFilter(LibrarySearchFilter.Playlists)
        assertEquals(LibrarySearchFilter.Playlists, viewModel.content().searchFilter)

        viewModel.selectSearchScope(LibrarySearchScope.CurrentLibrary)

        val state = viewModel.content()
        assertEquals(LibrarySearchFilter.All, state.searchFilter)
        assertFalse(LibrarySearchFilter.Playlists in state.availableSearchFilters)
    }

    @Test
    fun should_keepSupportedFilter_when_scopeSwitches() = runTest {
        val viewModel = viewModelFor(ServiceFeatureCatalog.spotify)
        advanceUntilIdle()
        viewModel.selectSearchFilter(LibrarySearchFilter.Songs)

        viewModel.selectSearchScope(LibrarySearchScope.SpotifyGlobal)

        assertEquals(LibrarySearchFilter.Songs, viewModel.content().searchFilter)
    }

    @Test
    fun should_fallBackToAll_when_capabilitiesStopCoveringThatType() = runTest {
        // Two collectors read repository.capabilities: observeCapabilities,
        // then observeProviderSearchAvailability (init order). Only the first
        // gets the flow that moves, so this proves observeCapabilities
        // normalises on its own; with one shared flow the provider observer
        // would hide a missing normalisation. The provider observer has its
        // own test below.
        val initial = ServiceFeatureCatalog.appleMusic.capabilities
        val moving = MutableStateFlow(initial)
        val repository = repositoryFor(MediaId.PROVIDER_APPLE_MUSIC, initial)
        every { repository.capabilities } returnsMany listOf(moving, flowOf(initial))
        every { repository.currentCapabilities() } answers { moving.value }
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectSearchFilter(LibrarySearchFilter.Playlists)
        assertTrue(viewModel.content().canAddToLibrary)

        moving.value = initial - Capability.SEARCH_PLAYLISTS - Capability.LIBRARY_ADD
        advanceUntilIdle()

        val state = viewModel.content()
        // Only observeCapabilities writes canAddToLibrary: the moving flow
        // reached the collector this test is about.
        assertFalse(state.canAddToLibrary)
        assertEquals(LibrarySearchFilter.All, state.searchFilter)
        assertFalse(LibrarySearchFilter.Playlists in state.availableSearchFilters)
        verify(exactly = 2) { repository.capabilities }
    }

    @Test
    fun should_fallBackToAll_when_providerChangeMovesSearchOffTheCatalog() = runTest {
        // Same capability flow throughout: only the provider id moves, so
        // observeProviderSearchAvailability alone has to normalise.
        val capabilities = setOf(Capability.CATALOG_SEARCH, Capability.SEARCH_PLAYLISTS)
        val providerId = MutableStateFlow(MediaId.PROVIDER_SPOTIFY)
        val repository = repositoryFor(MediaId.PROVIDER_SPOTIFY, capabilities)
        every { repository.activeProviderId } returns providerId
        every { repository.currentProviderId() } answers { providerId.value }
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()
        viewModel.openSearchShortcut(LibrarySearchScope.SpotifyGlobal)
        viewModel.selectSearchFilter(LibrarySearchFilter.Playlists)

        providerId.value = MediaId.PROVIDER_SUBSONIC
        advanceUntilIdle()

        val state = viewModel.content()
        assertEquals(LibrarySearchScope.CurrentLibrary, state.searchScope)
        assertEquals(LibrarySearchFilter.All, state.searchFilter)
    }

    @Test
    fun should_returnEverySavedMatch_when_searchingSpotifyLibrary() = runTest {
        val saved = tracks(SEARCH_ALL_ROWS_PER_TYPE + 10, provider = MediaId.PROVIDER_SPOTIFY)
        val repository = repositoryFor(MediaId.PROVIDER_SPOTIFY, ServiceFeatureCatalog.spotify.capabilities)
        coEvery { repository.getSpotifyLocalSearchSnapshot() } returns
            SpotifyLibrarySyncCoordinator.SpotifyLocalSearchSnapshot(
                artists = emptyList(),
                albums = emptyList(),
                tracks = saved,
                playlists = emptyList(),
                starred = Starred()
            )
        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        viewModel.search("song")
        advanceUntilIdle()

        val results = viewModel.content().searchResults!!
        assertEquals(saved, results.tracks)
        assertEquals(SEARCH_ALL_ROWS_PER_TYPE, results.shownFor(LibrarySearchFilter.All).tracks.size)
    }

    private fun LibraryViewModel.content(): LibraryUiState.Content = uiState.value as LibraryUiState.Content

    private fun viewModelFor(service: ServiceFeatures): LibraryViewModel =
        LibraryViewModel(repositoryFor(service.id, service.capabilities))

    private fun repositoryFor(providerId: String, capabilities: Set<Capability>): YoinRepository {
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProviderId() } returns providerId
        every { repository.currentCapabilities() } returns capabilities
        every { repository.activeProviderId } returns flowOf(providerId)
        every { repository.capabilities } returns flowOf(capabilities)
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.trackLibraryStates } returns flowOf(emptyMap())
        every { repository.currentProfileId() } returns "test-profile"
        every { repository.currentProfileIdFlow } returns flowOf("test-profile")
        every { repository.libraryRevision } returns flowOf(0L)
        coEvery { repository.getArtists() } returns emptyList()
        coEvery { repository.refreshSpotifyLibrary(any()) } returns Result.success(Unit)
        return repository
    }

    private fun tracks(count: Int, provider: String = MediaId.PROVIDER_SUBSONIC): List<Track> = (1..count).map { n ->
        Track(
            id = MediaId(provider, "t$n"),
            title = "Song $n",
            artist = null,
            artistId = null,
            album = null,
            albumId = null,
            coverArt = null,
            durationSec = null,
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null
        )
    }

    private fun artist(rawId: String) = Artist(MediaId.subsonic(rawId), "Artist $rawId", null, null)

    private fun playlist(rawId: String) = Playlist(
        id = MediaId.spotify(rawId),
        name = "Playlist $rawId",
        owner = null,
        coverArt = null,
        songCount = null,
        durationSec = null
    )
}
