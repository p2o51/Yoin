package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.LibraryRecents
import com.gpo.yoin.data.repository.LibraryRecentsSource
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.data.source.spotify.SpotifyLibrarySyncCoordinator
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun should_clearSearchFocusRequest_when_openingLibraryNormallyAfterShortcut() = runTest {
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SUBSONIC)
        every { repository.capabilities } returns flowOf(emptySet())
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.trackLibraryStates } returns flowOf(emptyMap())
        every { repository.currentProfileId() } returns "test-profile"
        every { repository.currentProfileIdFlow } returns flowOf("test-profile")
        every { repository.libraryRevision } returns flowOf(0L)
        coEvery { repository.getArtists() } returns emptyList()

        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.openSearchShortcut(LibrarySearchScope.CurrentLibrary)
        val shortcutState = viewModel.uiState.value as LibraryUiState.Content
        assertTrue(shortcutState.searchFocusRequestId > 0L)

        viewModel.showLibraryHome()

        val normalEntryState = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(0L, normalEntryState.searchFocusRequestId)
        assertEquals(LibrarySearchScope.CurrentLibrary, normalEntryState.searchScope)
    }

    @Test
    fun should_searchSavedAppleMusicLibrary_when_searchingFromNormalLibraryEntry() = runTest {
        val expected = SearchResults()
        val repository = repositoryFor(
            providerId = MediaId.PROVIDER_APPLE_MUSIC,
            capabilities = setOf(Capability.SEARCH, Capability.CATALOG_SEARCH),
        )
        coEvery { repository.searchCurrentLibrary("Apple") } returns expected
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.search("Apple")
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibrarySearchScope.CurrentLibrary, state.searchScope)
        assertTrue(state.canSearchAppleMusicCatalog)
        assertFalse(state.canSearchSpotifyCatalog)
        assertEquals(expected, state.searchResults)
        coVerify(exactly = 1) { repository.searchCurrentLibrary("Apple") }
        coVerify(exactly = 0) { repository.search(any()) }
    }

    @Test
    fun should_searchAppleMusicCatalog_when_catalogScopeSelected() = runTest {
        val repository = repositoryFor(
            providerId = MediaId.PROVIDER_APPLE_MUSIC,
            capabilities = setOf(Capability.SEARCH, Capability.CATALOG_SEARCH),
        )
        coEvery { repository.search("Music") } returns SearchResults()
        coEvery { repository.searchCurrentLibrary("Music") } returns SearchResults()
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.openSearchShortcut(LibrarySearchScope.AppleMusicGlobal)
        viewModel.search("Music")
        advanceUntilIdle()

        assertEquals(
            LibrarySearchScope.AppleMusicGlobal,
            (viewModel.uiState.value as LibraryUiState.Content).searchScope,
        )
        coVerify(exactly = 1) { repository.search("Music") }
        coVerify(exactly = 0) { repository.searchCurrentLibrary(any()) }

        viewModel.selectSearchScope(LibrarySearchScope.CurrentLibrary)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.searchCurrentLibrary("Music") }
    }

    @Test
    fun should_keepPendingAppleMusicShortcut_when_libraryStillLoading() = runTest {
        val repository = repositoryFor(
            providerId = MediaId.PROVIDER_APPLE_MUSIC,
            capabilities = setOf(Capability.CATALOG_SEARCH),
        )
        val viewModel = libraryViewModel(repository)
        viewModel.openSearchShortcut(LibrarySearchScope.AppleMusicGlobal)
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibrarySearchScope.AppleMusicGlobal, state.searchScope)
        assertTrue(state.searchFocusRequestId > 0L)
    }

    @Test
    fun should_rejectCatalogScope_when_providerDoesNotDeclareCatalogSearch() = runTest {
        val repository = repositoryFor(
            providerId = MediaId.PROVIDER_APPLE_MUSIC,
            capabilities = setOf(Capability.SEARCH),
        )
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.openSearchShortcut(LibrarySearchScope.AppleMusicGlobal)

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibrarySearchScope.CurrentLibrary, state.searchScope)
        assertFalse(state.canSearchAppleMusicCatalog)
    }

    @Test
    fun should_rejectOtherProvidersCatalogScope_when_usingAppleMusic() = runTest {
        val repository = repositoryFor(
            providerId = MediaId.PROVIDER_APPLE_MUSIC,
            capabilities = setOf(Capability.CATALOG_SEARCH),
        )
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.openSearchShortcut(LibrarySearchScope.SpotifyGlobal)

        assertEquals(
            LibrarySearchScope.CurrentLibrary,
            (viewModel.uiState.value as LibraryUiState.Content).searchScope,
        )
    }

    @Test
    fun should_loadSavedSongsAndHideRandomMix_when_providerSupportsLibrarySongs() = runTest {
        val repository = repositoryFor(
            providerId = MediaId.PROVIDER_APPLE_MUSIC,
            capabilities = setOf(Capability.LIBRARY_SONGS),
        )
        coEvery { repository.getLibrarySongs(size = 500) } returns emptyList()
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        val initial = viewModel.uiState.value as LibraryUiState.Content
        assertTrue(LibraryTab.Songs in initial.availableTabs)
        assertFalse(initial.canReshuffleSongs)
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        viewModel.reshuffleSongs()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getLibrarySongs(size = 500) }
        coVerify(exactly = 0) { repository.getRandomSongs(any()) }
        assertEquals(emptyList<Track>(), (viewModel.uiState.value as LibraryUiState.Content).songs)
    }

    @Test
    fun should_preventDuplicateLibraryMutation_when_songAdditionIsStillWorking() = runTest {
        val completion = CompletableDeferred<LibraryMembership>()
        var addCalls = 0
        val writes = object : MusicWriteActions by mockk(relaxed = true) {
            override suspend fun addToLibrary(trackId: MediaId): Result<LibraryMembership> {
                addCalls += 1
                return Result.success(completion.await())
            }
        }
        val repository = repositoryWithWrites(writes)
        val track = appleMusicTrack()
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.addSongToLibrary(track)
        viewModel.addSongToLibrary(track)
        assertEquals(setOf(track.id), viewModel.workingLibraryTrackIds.value)
        advanceUntilIdle()
        assertEquals(1, addCalls)
        completion.complete(LibraryMembership.Pending)
        advanceUntilIdle()
        assertEquals(emptySet<MediaId>(), viewModel.workingLibraryTrackIds.value)
        val feedback = (viewModel.uiState.value as LibraryUiState.Content).libraryActionFeedback[track.id]
        assertTrue(feedback?.message?.contains("Waiting for Apple Music") == true)
        assertFalse(feedback!!.isError)

        viewModel.addSongToLibrary(track)
        advanceUntilIdle()
        assertEquals(2, addCalls)
    }

    @Test
    fun should_showInlineSongError_when_libraryAdditionFailsInsideSearch() = runTest {
        val writes = object : MusicWriteActions by mockk(relaxed = true) {
            override suspend fun addToLibrary(trackId: MediaId): Result<LibraryMembership> =
                Result.failure(IllegalStateException("Apple Music rejected this addition"))
        }
        val repository = repositoryWithWrites(writes)
        val track = appleMusicTrack()
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.addSongToLibrary(track)
        advanceUntilIdle()

        val feedback = (viewModel.uiState.value as LibraryUiState.Content).libraryActionFeedback[track.id]
        assertTrue(feedback?.isError == true)
        assertTrue(feedback!!.message.isNotBlank())
        assertEquals(emptySet<MediaId>(), viewModel.workingLibraryTrackIds.value)
    }

    @Test
    fun should_keepNewAccountOperationBusy_when_oldAccountCompletesSameSongAddition() = runTest {
        val profileIds = MutableStateFlow<String?>("A")
        val firstCompletion = CompletableDeferred<LibraryMembership>()
        val secondCompletion = CompletableDeferred<LibraryMembership>()
        val calls = mutableListOf<String?>()
        val writes = object : MusicWriteActions by mockk(relaxed = true) {
            override suspend fun addToLibrary(trackId: MediaId): Result<LibraryMembership> {
                val profileId = profileIds.value
                calls += profileId
                val completion = if (profileId == "A") firstCompletion else secondCompletion
                return Result.success(completion.await())
            }
        }
        val repository = repositoryWithWrites(writes, profileIds)
        val viewModel = libraryViewModel(repository)
        val track = appleMusicTrack()
        advanceUntilIdle()
        viewModel.addSongToLibrary(track)
        advanceUntilIdle()
        assertEquals(setOf(track.id), viewModel.workingLibraryTrackIds.value)

        profileIds.value = "B"
        advanceUntilIdle()
        assertEquals(emptySet<MediaId>(), viewModel.workingLibraryTrackIds.value)
        viewModel.addSongToLibrary(track)
        advanceUntilIdle()
        assertEquals(listOf("A", "B"), calls)
        assertEquals(setOf(track.id), viewModel.workingLibraryTrackIds.value)

        firstCompletion.complete(LibraryMembership.Pending)
        advanceUntilIdle()
        assertEquals(setOf(track.id), viewModel.workingLibraryTrackIds.value)
        assertEquals(null, (viewModel.uiState.value as LibraryUiState.Content).libraryActionFeedback[track.id])

        secondCompletion.complete(LibraryMembership.Pending)
        advanceUntilIdle()
        assertEquals(emptySet<MediaId>(), viewModel.workingLibraryTrackIds.value)
        assertTrue((viewModel.uiState.value as LibraryUiState.Content).libraryActionFeedback[track.id] != null)
    }

    @Test
    fun should_hideLibraryAddition_when_providerCannotWriteLibrary() = runTest {
        val repository = repositoryFor(
            providerId = MediaId.PROVIDER_SUBSONIC,
            capabilities = setOf(Capability.SEARCH),
        )
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.addSongToLibrary(appleMusicTrack())
        advanceUntilIdle()

        assertFalse((viewModel.uiState.value as LibraryUiState.Content).canAddToLibrary)
        coVerify(exactly = 0) { repository.addToLibrary(any()) }
    }

    @Test
    fun should_keepRefreshedLibrary_when_oldInitialLoadCompletesLater() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, emptySet())
        val oldArtists = CompletableDeferred<List<ArtistIndex>>()
        val freshArtist = Artist(MediaId.subsonic("fresh"), "Fresh artist", null, null)
        var initialRequest = true
        coEvery { repository.getArtists() } coAnswers {
            if (initialRequest) {
                initialRequest = false
                oldArtists.await()
            } else {
                listOf(ArtistIndex("F", listOf(freshArtist)))
            }
        }
        val viewModel = libraryViewModel(repository)
        runCurrent()

        viewModel.refresh()
        advanceUntilIdle()
        oldArtists.complete(listOf(ArtistIndex("Old")))
        advanceUntilIdle()

        assertEquals(listOf(freshArtist), (viewModel.uiState.value as LibraryUiState.Content).artists)
        coVerify(exactly = 2) { repository.getArtists() }
    }

    @Test
    fun should_clearOldAccountSongsAndSearch_when_switchingProfilesOfSameProvider() = runTest {
        val repository = repositoryFor(
            MediaId.PROVIDER_APPLE_MUSIC,
            setOf(Capability.LIBRARY_SONGS, Capability.CATALOG_SEARCH),
        )
        val profileIds = MutableStateFlow<String?>("first")
        every { repository.currentProfileId() } answers { profileIds.value }
        every { repository.currentProfileIdFlow } returns profileIds
        val oldSongs = CompletableDeferred<List<Track>>()
        val newSong = appleMusicTrack().copy(title = "Second account song")
        coEvery { repository.getLibrarySongs(size = 500) } coAnswers {
            if (profileIds.value == "first") oldSongs.await() else listOf(newSong)
        }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.openSearchShortcut(LibrarySearchScope.AppleMusicGlobal)
        viewModel.selectTab(LibraryTab.Songs)
        runCurrent()

        profileIds.value = "second"
        advanceUntilIdle()
        val switched = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibrarySearchScope.CurrentLibrary, switched.searchScope)
        assertEquals("", switched.searchQuery)
        assertEquals(null, switched.songs)

        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        oldSongs.complete(listOf(appleMusicTrack().copy(title = "First account song")))
        advanceUntilIdle()

        assertEquals(listOf(newSong), (viewModel.uiState.value as LibraryUiState.Content).songs)
    }

    @Test
    fun should_reloadSavedSongsAndLibrarySearch_when_additionConfirmedElsewhere() = runTest {
        val repository = repositoryFor(
            MediaId.PROVIDER_APPLE_MUSIC,
            setOf(Capability.LIBRARY_SONGS, Capability.SEARCH),
        )
        val revision = MutableStateFlow(0L)
        every { repository.libraryRevision } returns revision
        var savedSongs = emptyList<Track>()
        coEvery { repository.getLibrarySongs(size = 500) } coAnswers { savedSongs }
        coEvery { repository.searchCurrentLibrary("song") } coAnswers { SearchResults(tracks = savedSongs) }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        viewModel.search("song")
        advanceUntilIdle()

        savedSongs = listOf(appleMusicTrack())
        revision.value = 1L
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibraryTab.Songs, state.selectedTab)
        assertEquals("song", state.searchQuery)
        assertEquals(LibrarySearchScope.CurrentLibrary, state.searchScope)
        assertEquals(savedSongs, state.songs)
        assertEquals(savedSongs, state.searchResults?.tracks)
        coVerify(exactly = 2) { repository.getLibrarySongs(size = 500) }
        coVerify(exactly = 2) { repository.searchCurrentLibrary("song") }
    }

    @Test
    fun should_reloadNewAccountsSongs_when_profilesShareRevisionAndNewAdditionIsConfirmed() = runTest {
        val repository = repositoryFor(
            MediaId.PROVIDER_APPLE_MUSIC,
            setOf(Capability.LIBRARY_SONGS),
        )
        val profileIds = MutableStateFlow<String?>("first")
        val revisions = MutableStateFlow<Map<String, Long>>(emptyMap())
        every { repository.currentProfileId() } answers { profileIds.value }
        every { repository.currentProfileIdFlow } returns profileIds
        // Repository emits account-scoped scalar revisions. A0 -> B0 does
        // not emit here; the ViewModel must observe the profile ID as well.
        every { repository.libraryRevision } returns combine(profileIds, revisions) { id, values ->
            values[id] ?: 0L
        }.distinctUntilChanged()
        var secondAccountSongs = emptyList<Track>()
        coEvery { repository.getLibrarySongs(size = 500) } coAnswers {
            if (profileIds.value == "second") secondAccountSongs else emptyList()
        }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        profileIds.value = "second"
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        val addedSong = appleMusicTrack().copy(title = "Added to second account")
        secondAccountSongs = listOf(addedSong)
        revisions.value = mapOf("second" to 1L)
        advanceUntilIdle()

        assertEquals(listOf(addedSong), (viewModel.uiState.value as LibraryUiState.Content).songs)
        coVerify(exactly = 3) { repository.getLibrarySongs(size = 500) }
    }

    @Test
    fun should_hideFavoritesTab_when_serviceFavoritesAreItsLibrary() = runTest {
        val repository = spotifyRepository()
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        // Liked songs, saved albums and followed artists are Songs, Albums and Artists.
        assertEquals(
            listOf(LibraryTab.Playlists, LibraryTab.Artists, LibraryTab.Albums, LibraryTab.Songs),
            state.availableTabs
        )
        assertFalse(state.canReshuffleSongs)
        viewModel.selectTab(LibraryTab.Favorites)
        assertEquals(LibraryTab.All, (viewModel.uiState.value as LibraryUiState.Content).selectedTab)
    }

    @Test
    fun should_keepFavoritesTab_when_subsonicDeclaresFavorites() = runTest {
        val repository = repositoryFor(
            MediaId.PROVIDER_SUBSONIC,
            setOf(Capability.FAVORITES, Capability.RANDOM_SONGS, Capability.PLAYLISTS_READ)
        )
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibraryTab.Chips, state.availableTabs)
        assertTrue(state.canReshuffleSongs)
    }

    /**
     * The provider and capability flow on its own, with the profile id held
     * still. A real profile switch reloads the library first and lands on
     * All ([LibraryViewModel] observeProfileChanges); this pins the
     * normalisation behind it, so a provider or capability change that
     * arrives without a reload can't leave the selection on a hidden tab.
     */
    @Test
    fun should_leaveFavoritesTab_when_providerFlowTurnsToServiceWhoseFavoritesAreItsLibrary() = runTest {
        val providerIds = MutableStateFlow<String?>(MediaId.PROVIDER_SUBSONIC)
        val subsonicCapabilities = setOf(Capability.FAVORITES, Capability.RANDOM_SONGS)
        val spotifyCapabilities = setOf(Capability.FAVORITES, Capability.LIBRARY_SONGS, Capability.RANDOM_SONGS)
        val capabilities = MutableStateFlow(subsonicCapabilities)
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, subsonicCapabilities)
        every { repository.currentProviderId() } answers { providerIds.value }
        every { repository.currentCapabilities() } answers { capabilities.value }
        every { repository.activeProviderId } returns providerIds
        every { repository.capabilities } returns capabilities
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Favorites)
        advanceUntilIdle()
        assertEquals(LibraryTab.Favorites, (viewModel.uiState.value as LibraryUiState.Content).selectedTab)

        providerIds.value = MediaId.PROVIDER_SPOTIFY
        capabilities.value = spotifyCapabilities
        runCurrent()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertFalse(LibraryTab.Favorites in state.availableTabs)
        assertEquals(LibraryTab.All, state.selectedTab)
    }

    @Test
    fun should_loadLikedSongs_when_spotifySongsTabSelected() = runTest {
        val repository = spotifyRepository()
        val liked = listOf(spotifyTrack("new"), spotifyTrack("old"))
        coEvery { repository.getLibrarySongs(size = 500) } returns liked
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        assertEquals(liked, (viewModel.uiState.value as LibraryUiState.Content).songs)
        coVerify(exactly = 0) { repository.getRandomSongs(any()) }
    }

    @Test
    fun should_rereadLikedSongsFromCache_when_likeChangesOnSpotifySongsTab() = runTest {
        val overrides = MutableStateFlow<Map<MediaId, Boolean>>(emptyMap())
        val repository = spotifyRepository()
        every { repository.favoriteOverrides } returns overrides
        val old = spotifyTrack("old")
        val unliked = spotifyTrack("unliked")
        val liked = spotifyTrack("liked")
        var cache = listOf(unliked, old)
        coEvery { repository.getLibrarySongs(size = 500) } coAnswers { cache }
        val read = CompletableDeferred<Unit>()
        coEvery { repository.readCachedLikedSongs(size = 500) } coAnswers {
            read.await()
            cache
        }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        // An unlike leaves at once, before the re-read lands.
        overrides.value = mapOf(unliked.id to false)
        runCurrent()
        assertEquals(listOf(old), (viewModel.uiState.value as LibraryUiState.Content).songs)

        // A like lands on top once the cache has it (newest like first).
        cache = listOf(liked, old)
        read.complete(Unit)
        overrides.value = mapOf(liked.id to true)
        advanceUntilIdle()
        assertEquals(listOf(liked, old), (viewModel.uiState.value as LibraryUiState.Content).songs)
        // The tab's first load alone checked freshness; the hearts read the cache.
        coVerify(exactly = 1) { repository.getLibrarySongs(any(), any()) }
    }

    @Test
    fun should_refreshLikedSongsFromCache_when_likeChangesOnAnotherTab() = runTest {
        val overrides = MutableStateFlow<Map<MediaId, Boolean>>(emptyMap())
        val repository = spotifyRepository()
        every { repository.favoriteOverrides } returns overrides
        val old = spotifyTrack("old")
        val liked = spotifyTrack("liked")
        var cache = listOf(old)
        coEvery { repository.getLibrarySongs(size = 500) } coAnswers { cache }
        coEvery { repository.readCachedLikedSongs(size = 500) } coAnswers { cache }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Artists)
        advanceUntilIdle()

        // A like from Now Playing while Artists shows.
        cache = listOf(liked, old)
        overrides.value = mapOf(liked.id to true)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        assertEquals(listOf(liked, old), (viewModel.uiState.value as LibraryUiState.Content).songs)
        coVerify(exactly = 1) { repository.getLibrarySongs(any(), any()) }
    }

    @Test
    fun should_rereadFollowedArtistsFromCache_when_followChangesOnSpotify() = runTest {
        val overrides = MutableStateFlow<Map<MediaId, Boolean>>(emptyMap())
        val repository = spotifyRepository()
        every { repository.favoriteOverrides } returns overrides
        val arca = Artist(MediaId.spotify("arca"), "Arca", null, null, isStarred = true)
        val bruit = Artist(MediaId.spotify("bruit"), "Bruit", null, null, isStarred = true)
        val caroline = Artist(MediaId.spotify("caroline"), "Caroline", null, null, isStarred = true)
        coEvery { repository.getArtists() } returns
            listOf(ArtistIndex("A", listOf(arca)), ArtistIndex("B", listOf(bruit)))
        var cache = listOf(ArtistIndex("A", listOf(arca)), ArtistIndex("C", listOf(caroline)))
        coEvery { repository.readCachedFollowedArtists() } coAnswers { cache }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        // Unfollow Bruit, follow Caroline on their artist pages.
        overrides.value = mapOf(bruit.id to false, caroline.id to true)
        advanceUntilIdle()
        assertEquals(listOf(arca, caroline), (viewModel.uiState.value as LibraryUiState.Content).artists)

        // The writes settle; the list is what the cache holds.
        cache = listOf(ArtistIndex("C", listOf(caroline)))
        overrides.value = emptyMap()
        advanceUntilIdle()
        assertEquals(listOf(caroline), (viewModel.uiState.value as LibraryUiState.Content).artists)
        // Neither a freshness check nor a sync: the first load's alone.
        coVerify(exactly = 1) { repository.getArtists() }
        coVerify(exactly = 1) { repository.refreshSpotifyLibrary(any()) }
        coVerify(exactly = 0) { repository.getStarred() }
    }

    @Test
    fun should_keepRandomSample_when_favoriteChangesOnSubsonicSongsTab() = runTest {
        val overrides = MutableStateFlow<Map<MediaId, Boolean>>(emptyMap())
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, setOf(Capability.FAVORITES, Capability.RANDOM_SONGS))
        every { repository.favoriteOverrides } returns overrides
        val sample = listOf(subsonicTrack("a"), subsonicTrack("b"))
        coEvery { repository.getRandomSongs(size = 50) } returns sample
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        overrides.value = mapOf(sample.first().id to true)
        advanceUntilIdle()

        // The heart flips in place; the sample is neither re-read nor cut.
        val songs = (viewModel.uiState.value as LibraryUiState.Content).songs.orEmpty()
        assertEquals(sample.map(Track::id), songs.map(Track::id))
        assertTrue(songs.first().isStarred)
        coVerify(exactly = 1) { repository.getRandomSongs(size = 50) }
        coVerify(exactly = 0) { repository.getLibrarySongs(any(), any()) }
        coVerify(exactly = 0) { repository.readCachedLikedSongs(any(), any()) }
        coVerify(exactly = 0) { repository.readCachedFollowedArtists() }
    }

    @Test
    fun should_searchEveryLibraryArtist_when_spotifyArtistsTabListsFollowedOnly() = runTest {
        val repository = spotifyRepository()
        val followed = Artist(MediaId.spotify("followed"), "Followed Band", null, null, isStarred = true)
        val fromSavedAlbum = Artist(MediaId.spotify("saved"), "Band From A Saved Album", null, null)
        coEvery { repository.getArtists() } returns listOf(ArtistIndex("F", listOf(followed)))
        coEvery { repository.getSpotifyLocalSearchSnapshot() } returns
            SpotifyLibrarySyncCoordinator.SpotifyLocalSearchSnapshot(
                artists = listOf(followed, fromSavedAlbum),
                albums = emptyList(),
                tracks = emptyList(),
                playlists = emptyList(),
                starred = Starred()
            )
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.search("band")
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(listOf(followed), state.artists)
        assertEquals(listOf(followed, fromSavedAlbum), state.searchResults?.artists)
    }

    // ── All, chips and sorts (Q12/Q13) ───────────────────────────────────

    @Test
    fun should_startOnAllAndLoadItsListsOnlyOnScreen_when_libraryOpens() = runTest {
        val repository = subsonicLibrary()
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        val cold = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibraryTab.All, cold.selectedTab)
        // What the cold start read shows at once: the artists.
        assertEquals(listOf("artist:subsonic:ar"), cold.allItems.orEmpty().map(LibraryItem::key))
        // The cold start costs what it always did: the artists only.
        coVerify(exactly = 0) { repository.getAlbumList(any(), any(), any()) }
        coVerify(exactly = 0) { repository.getPlaylists() }

        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()

        val all = (viewModel.uiState.value as LibraryUiState.Content).allItems.orEmpty()
        assertEquals(
            setOf("artist:subsonic:ar", "album:subsonic:al", "playlist:subsonic:pl"),
            all.map(LibraryItem::key).toSet()
        )
        coVerify(exactly = 0) { repository.getRandomSongs(any()) }
        coVerify(exactly = 0) { repository.getLibrarySongs(any(), any()) }
        coVerify(exactly = 1) { repository.getArtists() }
    }

    @Test
    fun should_leavePlaylistsOutOfAll_when_serviceCannotReadThem() = runTest {
        val repository = subsonicLibrary(capabilities = setOf(Capability.FAVORITES, Capability.RANDOM_SONGS))
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()

        val all = (viewModel.uiState.value as LibraryUiState.Content).allItems.orEmpty()
        assertEquals(listOf("album:subsonic:al", "artist:subsonic:ar"), all.map(LibraryItem::key))
        coVerify(exactly = 0) { repository.getPlaylists() }
    }

    @Test
    fun should_keepAllWithWhatLoaded_when_oneListFails() = runTest {
        val repository = subsonicLibrary()
        coEvery { repository.getAlbumList("newest", size = 500) } throws IllegalStateException("albums down")
        val viewModel = libraryViewModel(repository)
        val messages = mutableListOf<String>()
        backgroundScope.launch { viewModel.messages.collect { messages += it } }
        advanceUntilIdle()

        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibraryTab.All, state.selectedTab)
        assertEquals(
            setOf("artist:subsonic:ar", "playlist:subsonic:pl"),
            state.allItems.orEmpty().map(LibraryItem::key).toSet()
        )
        assertEquals(listOf("albums down"), messages)
    }

    @Test
    fun should_orderAllByLocalRecordsThenRecentlyAdded_when_sortIsRecents() = runTest {
        val records = MutableStateFlow(LibraryRecents(playlists = mapOf("pl" to 100L)))
        val repository = subsonicLibrary(
            albums = listOf(
                album("old", "Old", added = "2020-01-01T00:00:00Z"),
                album("new", "New", added = "2024-01-01T00:00:00Z")
            )
        )
        val viewModel = libraryViewModel(repository, recentsSource = { _, _ -> records })
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()

        fun allKeys() = (viewModel.uiState.value as LibraryUiState.Content).allItems.orEmpty().map(LibraryItem::key)
        // The played playlist first; then by library date; the undated artist last.
        assertEquals(
            listOf("playlist:subsonic:pl", "album:subsonic:new", "album:subsonic:old", "artist:subsonic:ar"),
            allKeys()
        )

        // An artist page opened: it moves up in place, nothing fetched again.
        records.value = records.value.copy(artists = mapOf("ar" to 200L))
        advanceUntilIdle()
        assertEquals(
            listOf("artist:subsonic:ar", "playlist:subsonic:pl", "album:subsonic:new", "album:subsonic:old"),
            allKeys()
        )
        coVerify(exactly = 1) { repository.getAlbumList(any(), any(), any()) }
    }

    @Test
    fun should_readRecentsOfTheActiveProfileAndProvider_when_observing() = runTest {
        val asked = mutableListOf<Pair<String, String>>()
        val repository = subsonicLibrary()
        libraryViewModel(
            repository,
            recentsSource = { profileId, provider ->
                asked += profileId to provider
                flowOf(LibraryRecents.None)
            }
        )
        advanceUntilIdle()

        assertEquals(listOf("test-profile" to MediaId.PROVIDER_SUBSONIC), asked)
    }

    @Test
    fun should_reorderAndRememberPerProfileAndView_when_sortChosen() = runTest {
        val store = LibrarySortStore.InMemory()
        val repository = subsonicLibrary(
            albums = listOf(
                album("b", "Beta", added = "2024-01-01T00:00:00Z"),
                album("a", "Alpha", added = "2020-01-01T00:00:00Z")
            )
        )
        val first = libraryViewModel(repository, sortStore = store)
        advanceUntilIdle()
        first.selectTab(LibraryTab.Albums)
        advanceUntilIdle()
        val resting = first.uiState.value as LibraryUiState.Content
        assertEquals(LibrarySort.Recents, resting.sorts[LibraryTab.Albums])
        assertEquals(listOf("b", "a"), resting.albums.orEmpty().map { it.id.rawId })

        first.selectSort(LibraryTab.Albums, LibrarySort.Alphabetical)
        advanceUntilIdle()

        val sorted = first.uiState.value as LibraryUiState.Content
        assertEquals(LibrarySort.Alphabetical, sorted.sorts[LibraryTab.Albums])
        assertEquals(listOf("a", "b"), sorted.albums.orEmpty().map { it.id.rawId })
        // Each view keeps its own.
        assertEquals(LibrarySort.Recents, sorted.sorts[LibraryTab.All])
        assertEquals(LibrarySort.Alphabetical, store.sortFor("test-profile", LibraryTab.Albums))

        val reopened = libraryViewModel(repository, sortStore = store)
        advanceUntilIdle()
        assertEquals(
            LibrarySort.Alphabetical,
            (reopened.uiState.value as LibraryUiState.Content).sorts[LibraryTab.Albums]
        )
    }

    @Test
    fun should_returnToAllAndReadThatProfilesSorts_when_profileSwitches() = runTest {
        val store = LibrarySortStore.InMemory()
        store.setSort("second", LibraryTab.All, LibrarySort.Creator)
        val profileIds = MutableStateFlow<String?>("first")
        val repository = subsonicLibrary()
        every { repository.currentProfileId() } answers { profileIds.value }
        every { repository.currentProfileIdFlow } returns profileIds
        val viewModel = libraryViewModel(repository, sortStore = store)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        viewModel.selectSort(LibraryTab.All, LibrarySort.Alphabetical)
        advanceUntilIdle()

        profileIds.value = "second"
        advanceUntilIdle()

        val switched = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibraryTab.All, switched.selectedTab)
        assertEquals(LibrarySort.Creator, switched.sorts[LibraryTab.All])
        assertEquals(LibrarySort.Alphabetical, store.sortFor("first", LibraryTab.All))
    }

    @Test
    fun should_goBackToAllWithoutRefetching_when_chipCleared() = runTest {
        val repository = subsonicLibrary()
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()

        viewModel.selectTab(LibraryTab.All)
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibraryTab.All, state.selectedTab)
        assertEquals(3, state.allItems.orEmpty().size)
        coVerify(exactly = 1) { repository.getAlbumList(any(), any(), any()) }
    }

    @Test
    fun should_fillSpotifysAllFromTheSyncedCache_when_libraryShows() = runTest {
        val repository = spotifyRepository()
        val followed = Artist(MediaId.spotify("ar"), "Followed", null, null, isStarred = true)
        coEvery { repository.getArtists() } returns listOf(ArtistIndex("F", listOf(followed)))
        coEvery { repository.getSpotifyLocalSearchSnapshot() } returns
            SpotifyLibrarySyncCoordinator.SpotifyLocalSearchSnapshot(
                artists = listOf(followed, Artist(MediaId.spotify("other"), "Not followed", null, null)),
                albums = listOf(album("al", "Saved").copy(id = MediaId.spotify("al"))),
                tracks = emptyList(),
                playlists = listOf(
                    Playlist(
                        id = MediaId.spotify("pl"),
                        name = "Mine",
                        owner = "me",
                        coverArt = null,
                        songCount = null,
                        durationSec = null
                    )
                ),
                starred = Starred()
            )
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()

        // Followed artists only, and no freshness-checked read that could start a sync.
        assertEquals(
            setOf("artist:spotify:ar", "album:spotify:al", "playlist:spotify:pl"),
            (viewModel.uiState.value as LibraryUiState.Content).allItems.orEmpty().map(LibraryItem::key).toSet()
        )
        coVerify(exactly = 0) { repository.getAlbumList(any(), any(), any()) }
        coVerify(exactly = 0) { repository.getPlaylists() }
        coVerify(exactly = 1) { repository.refreshSpotifyLibrary(any()) }
    }

    @Test
    fun should_keepSpotifysAllWithoutAFreshRead_when_aPlaylistChangesElsewhere() = runTest {
        val repository = spotifyRepository()
        coEvery { repository.getSpotifyLocalSearchSnapshot() } returns spotifySnapshot()
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()

        // Now Playing adds a song to a playlist: the write marked the synced cache stale.
        viewModel.invalidatePlaylists()
        advanceUntilIdle()

        // A fresh read would sync the whole library; All keeps its rows.
        coVerify(exactly = 0) { repository.getPlaylists() }
        assertTrue(
            "playlist:spotify:pl" in (viewModel.uiState.value as LibraryUiState.Content).allItems.orEmpty()
                .map(LibraryItem::key)
        )

        // The Playlists chip still reads the changed list fresh, as it always has.
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.getPlaylists() }
    }

    @Test
    fun should_readSpotifysCachedPlaylists_when_aPlaylistChangedBeforeLibraryShowed() = runTest {
        val repository = spotifyRepository()
        coEvery { repository.getSpotifyLocalSearchSnapshot() } returns spotifySnapshot()
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.invalidatePlaylists()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()

        assertTrue(
            "playlist:spotify:pl" in (viewModel.uiState.value as LibraryUiState.Content).allItems.orEmpty()
                .map(LibraryItem::key)
        )
        coVerify(exactly = 0) { repository.getPlaylists() }
        coVerify(exactly = 0) { repository.getAlbumList(any(), any(), any()) }
    }

    @Test
    fun should_rereadPlaylistsOnAll_when_aSubsonicPlaylistChanges() = runTest {
        val repository = subsonicLibrary()
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()

        viewModel.invalidatePlaylists()
        advanceUntilIdle()

        // One request, no rate limit: All shows the changed list.
        coVerify(exactly = 2) { repository.getPlaylists() }
    }

    @Test
    fun should_showEachListAsItArrives_when_allLoads() = runTest {
        val albums = CompletableDeferred<List<Album>>()
        val playlists = CompletableDeferred<List<Playlist>>()
        val repository = subsonicLibrary()
        coEvery { repository.getAlbumList("newest", size = 500) } coAnswers { albums.await() }
        coEvery { repository.getPlaylists() } coAnswers { playlists.await() }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()
        fun allKeys() = (viewModel.uiState.value as LibraryUiState.Content).allItems?.map(LibraryItem::key)
        // The slow lists hold nothing back: the artists are there already.
        assertEquals(listOf("artist:subsonic:ar"), allKeys())

        playlists.complete(
            listOf(Playlist(MediaId.subsonic("pl"), "Playlist", "me", null, null, null))
        )
        advanceUntilIdle()
        assertEquals(setOf("artist:subsonic:ar", "playlist:subsonic:pl"), allKeys().orEmpty().toSet())

        albums.complete(listOf(album("al", "Album")))
        advanceUntilIdle()
        assertEquals(
            setOf("artist:subsonic:ar", "album:subsonic:al", "playlist:subsonic:pl"),
            allKeys().orEmpty().toSet()
        )
        coVerify(exactly = 1) { repository.getAlbumList(any(), any(), any()) }
    }

    @Test
    fun should_waitRatherThanShowEmpty_when_nothingHasArrivedYet() = runTest {
        val albums = CompletableDeferred<List<Album>>()
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, setOf(Capability.FAVORITES))
        coEvery { repository.getAlbumList("newest", size = 500) } coAnswers { albums.await() }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()
        // No artists, albums on their way: the loading indicator, not "nothing here".
        assertNull((viewModel.uiState.value as LibraryUiState.Content).allItems)

        albums.complete(emptyList())
        advanceUntilIdle()
        assertEquals(emptyList<LibraryItem>(), (viewModel.uiState.value as LibraryUiState.Content).allItems)
    }

    @Test
    fun should_moveALibraryAlbumUp_when_openedFromLibrary() = runTest {
        // Apple Music lists recently-added albums by library id; the album page records its visit
        // and plays under the catalog id it resolves to, which Library can't match.
        val records = MutableStateFlow(LibraryRecents(albums = mapOf("1440000001" to 900L)))
        val repository = repositoryFor(MediaId.PROVIDER_APPLE_MUSIC, setOf(Capability.LIBRARY_SONGS))
        coEvery { repository.getAlbumList("newest", size = 500) } returns listOf(
            appleAlbum("library:l.new", "New", added = "2024-01-01T00:00:00Z"),
            appleAlbum("library:l.opened", "Opened", added = "2020-01-01T00:00:00Z")
        )
        val openStore = LibraryOpenStore.InMemory()
        val viewModel = libraryViewModel(
            repository,
            recentsSource = { _, _ -> records },
            openStore = openStore,
            clock = { 1_000L }
        )
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()
        fun albumIds() = (viewModel.uiState.value as LibraryUiState.Content).albums.orEmpty().map { it.id.rawId }
        assertEquals(listOf("library:l.new", "library:l.opened"), albumIds())

        viewModel.recordOpened(LibraryOpenKind.Album, "applemusic:library:l.opened")
        advanceUntilIdle()

        assertEquals(listOf("library:l.opened", "library:l.new"), albumIds())
        assertEquals(
            mapOf("library:l.opened" to 1_000L),
            openStore.observe("test-profile", MediaId.PROVIDER_APPLE_MUSIC).first().albums
        )
    }

    @Test
    fun should_moveAPlaylistUp_when_openedFromLibrary() = runTest {
        val repository = subsonicLibrary()
        coEvery { repository.getPlaylists() } returns listOf(
            Playlist(MediaId.subsonic("first"), "Alpha", "me", null, null, null),
            Playlist(MediaId.subsonic("opened"), "Beta", "me", null, null, null)
        )
        val viewModel = libraryViewModel(repository, clock = { 5L })
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()

        // A playlist page records no visit of its own: opening it from Library is what counts.
        viewModel.recordOpened(LibraryOpenKind.Playlist, "subsonic:opened")
        advanceUntilIdle()

        assertEquals(
            listOf("opened", "first"),
            (viewModel.uiState.value as LibraryUiState.Content).playlists.orEmpty().map { it.id.rawId }
        )
    }

    @Test
    fun should_offerOnlyWhatTheServiceCanSort_when_spotifyIsActive() = runTest {
        val repository = spotifyRepository()
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibrarySort.entries, state.sortOptions[LibraryTab.All])
        assertEquals(LibrarySort.entries, state.sortOptions[LibraryTab.Albums])
        assertEquals(listOf(LibrarySort.Recents, LibrarySort.Alphabetical), state.sortOptions[LibraryTab.Artists])
        assertEquals(listOf(LibrarySort.Recents, LibrarySort.Alphabetical), state.sortOptions[LibraryTab.Playlists])
        // Songs is Liked Songs in its own order: no sort row.
        assertNull(state.sortOptions[LibraryTab.Songs])

        viewModel.selectSort(LibraryTab.Playlists, LibrarySort.RecentlyAdded)
        viewModel.selectSort(LibraryTab.Songs, LibrarySort.Alphabetical)

        val after = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibrarySort.Recents, after.sorts[LibraryTab.Playlists])
        assertNull(after.sorts[LibraryTab.Songs])
    }

    @Test
    fun should_dropAStoredSortTheServiceNoLongerOffers_when_loading() = runTest {
        val store = LibrarySortStore.InMemory()
        store.setSort("test-profile", LibraryTab.Playlists, LibrarySort.RecentlyAdded)
        val viewModel = libraryViewModel(spotifyRepository(), sortStore = store)
        advanceUntilIdle()

        assertEquals(
            LibrarySort.Recents,
            (viewModel.uiState.value as LibraryUiState.Content).sorts[LibraryTab.Playlists]
        )
    }

    /** A Subsonic library with one artist, the given albums and one playlist. */
    private fun subsonicLibrary(
        capabilities: Set<Capability> = setOf(Capability.FAVORITES, Capability.RANDOM_SONGS, Capability.PLAYLISTS_READ),
        albums: List<Album> = listOf(album("al", "Album"))
    ): YoinRepository = repositoryFor(MediaId.PROVIDER_SUBSONIC, capabilities).also { repository ->
        coEvery { repository.getArtists() } returns
            listOf(ArtistIndex("A", listOf(Artist(MediaId.subsonic("ar"), "Artist", null, null))))
        coEvery { repository.getAlbumList("newest", size = 500) } returns albums
        coEvery { repository.getPlaylists() } returns listOf(
            Playlist(
                id = MediaId.subsonic("pl"),
                name = "Playlist",
                owner = "me",
                coverArt = null,
                songCount = null,
                durationSec = null
            )
        )
    }

    private fun spotifySnapshot() = SpotifyLibrarySyncCoordinator.SpotifyLocalSearchSnapshot(
        artists = emptyList(),
        albums = listOf(album("al", "Saved").copy(id = MediaId.spotify("al"))),
        tracks = emptyList(),
        playlists = listOf(Playlist(MediaId.spotify("pl"), "Mine", "me", null, null, null)),
        starred = Starred()
    )

    private fun appleAlbum(rawId: String, name: String, added: String) =
        album(rawId, name, added).copy(id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, rawId))

    private fun album(id: String, name: String, added: String? = null) = Album(
        id = MediaId.subsonic(id),
        name = name,
        artist = "Someone",
        artistId = null,
        coverArt = null,
        songCount = null,
        durationSec = null,
        year = null,
        genre = null,
        libraryAddedAt = added
    )

    /**
     * The ViewModel as tests drive it: sorting on the test dispatcher (so
     * advanceUntilIdle covers it) and a JVM name order, since android.icu is
     * a stub off device.
     */
    private fun libraryViewModel(
        repository: YoinRepository,
        sortStore: LibrarySortStore = LibrarySortStore.InMemory(),
        recentsSource: LibraryRecentsSource = LibraryRecentsSource.None,
        openStore: LibraryOpenStore = LibraryOpenStore.InMemory(),
        clock: () -> Long = { 0L }
    ): LibraryViewModel = LibraryViewModel(
        repository = repository,
        sortStore = sortStore,
        recentsSource = recentsSource,
        openStore = openStore,
        clock = clock,
        sortDispatcher = mainDispatcherRule.dispatcher,
        nameOrder = { String.CASE_INSENSITIVE_ORDER }
    )

    private fun repositoryFor(
        providerId: String,
        capabilities: Set<Capability>,
    ): YoinRepository = mockk<YoinRepository>(relaxed = true).also { repository ->
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
    }

    private fun TestScope.repositoryWithWrites(
        writes: MusicWriteActions,
        profileIds: MutableStateFlow<String?> = MutableStateFlow("test-profile"),
    ): YoinRepository {
        val libraryReads = mockk<MusicLibrary>(relaxed = true)
        coEvery { libraryReads.getArtists() } returns emptyList()
        val source = mockk<MusicSource> {
            every { id } returns MediaId.PROVIDER_APPLE_MUSIC
            every { capabilities } returns setOf(Capability.LIBRARY_ADD)
            every { library() } returns libraryReads
            every { writeActions() } returns writes
        }
        return YoinRepository(
            activeSource = MutableStateFlow<MusicSource?>(source),
            activeProfileId = profileIds,
            database = mockk(relaxed = true),
            geminiService = mockk(relaxed = true),
            songAboutEntryDao = mockk(relaxed = true),
            geminiConfigDao = mockk(relaxed = true),
            lyricsCacheDao = mockk(relaxed = true),
            lyricsTranslationCacheDao = mockk(relaxed = true),
            songNoteDao = mockk(relaxed = true),
            albumNoteDao = mockk(relaxed = true),
            albumRatingDao = mockk(relaxed = true),
            memoryCopyCacheDao = mockk(relaxed = true),
            neoDbSyncService = mockk(relaxed = true),
            repositoryScope = backgroundScope,
        )
    }

    private fun spotifyRepository(): YoinRepository = repositoryFor(
        MediaId.PROVIDER_SPOTIFY,
        ServiceFeatureCatalog.spotify.capabilities
    ).also { repository ->
        coEvery { repository.refreshSpotifyLibrary(any()) } returns Result.success(Unit)
    }

    private fun spotifyTrack(id: String): Track = appleMusicTrack().copy(id = MediaId.spotify(id), isStarred = true)

    private fun subsonicTrack(id: String): Track = appleMusicTrack().copy(id = MediaId.subsonic(id))

    private fun appleMusicTrack(): Track = Track(
        id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "123"),
        title = "Test song",
        artist = "Test artist",
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}
