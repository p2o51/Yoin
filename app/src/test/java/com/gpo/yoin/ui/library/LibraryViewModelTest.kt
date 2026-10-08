package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
        val viewModel = LibraryViewModel(repository)
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
