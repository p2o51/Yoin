package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.LibraryRecents
import com.gpo.yoin.data.repository.LibraryRecentsSource
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.data.source.spotify.SpotifyLibrarySyncCoordinator
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.component.FastScrollSection
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
    fun should_giveLibrarySearchArtistsTheirLoadedPortraits_when_appleMusicSearchHasNone() = runTest {
        val repository = repositoryFor(
            providerId = MediaId.PROVIDER_APPLE_MUSIC,
            capabilities = setOf(Capability.SEARCH, Capability.CATALOG_SEARCH)
        )
        val portrait = CoverRef.Url("https://example.com/portrait.jpg")
        val known = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:r.known")
        val unknown = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:r.unknown")
        coEvery { repository.getArtists() } returns
            listOf(ArtistIndex("*", listOf(Artist(known, "Known", null, portrait))))
        coEvery { repository.searchCurrentLibrary("k") } returns SearchResults(
            artists = listOf(Artist(known, "Known", null, null), Artist(unknown, "Kept", null, null))
        )
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.search("k")
        advanceUntilIdle()

        val found = content(viewModel).searchResults?.artists.orEmpty().associate { it.id to it.coverArt }
        // The Artists list's portrait, by id; one Library doesn't hold keeps its type icon.
        assertEquals(mapOf(known to portrait, unknown to null), found)
        coVerify(exactly = 1) { repository.getArtists() }
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
    fun should_keepAllOnScreenWhileRereading_when_appleAdditionConfirmed() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_APPLE_MUSIC, ServiceFeatureCatalog.appleMusic.capabilities)
        val revision = MutableStateFlow(0L)
        every { repository.libraryRevision } returns revision
        val first = Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:r.1"), "First", null, null)
        val added = Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:r.2"), "Added", null, null)
        var artists = listOf(first)
        coEvery { repository.getArtists() } coAnswers { listOf(ArtistIndex("*", artists)) }
        val oldAlbum = appleAlbum("library:l.old", "Old", added = "2020-01-01T00:00:00Z")
        val newAlbum = appleAlbum("library:l.new", "New", added = "2026-10-10T00:00:00Z")
        val reread = CompletableDeferred<List<Album>>()
        var albumReads = 0
        coEvery { repository.getAlbumList("alphabeticalByName", size = 100, offset = 0) } coAnswers {
            if (albumReads++ == 0) listOf(oldAlbum) else reread.await()
        }
        coEvery { repository.getPlaylists() } returns listOf(
            Playlist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "p.1"), "Mine", null, null, null, null)
        )
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()
        val shown = mutableListOf<List<LibraryItem>?>()
        backgroundScope.launch { viewModel.uiState.collect { shown += (it as LibraryUiState.Content).allItems } }
        runCurrent()

        // A song added elsewhere brought its album and artist into the library.
        artists = listOf(first, added)
        revision.value = 1L
        advanceUntilIdle()
        assertTrue("album:${oldAlbum.id}" in content(viewModel).allItems.orEmpty().map(LibraryItem::key))
        reread.complete(listOf(newAlbum, oldAlbum))
        advanceUntilIdle()

        val keys = content(viewModel).allItems.orEmpty().map(LibraryItem::key).toSet()
        val expected = setOf(
            "artist:${first.id}",
            "artist:${added.id}",
            "album:${newAlbum.id}",
            "album:${oldAlbum.id}",
            "playlist:applemusic:p.1"
        )
        assertEquals(expected, keys)
        // All never fell back to its loading indicator, and the playlists weren't read again.
        assertTrue(shown.none { it == null })
        coVerify(exactly = 1) { repository.getPlaylists() }
    }

    @Test
    fun should_dropAlbumNoLongerInLibrary_when_rereadCompletes() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_APPLE_MUSIC, ServiceFeatureCatalog.appleMusic.capabilities)
        val revision = MutableStateFlow(0L)
        every { repository.libraryRevision } returns revision
        val kept = appleAlbum("library:l.kept", "Kept", added = "2020-01-01T00:00:00Z")
        val gone = appleAlbum("library:l.gone", "Gone", added = "2021-01-01T00:00:00Z")
        val added = appleAlbum("library:l.added", "Added", added = "2026-10-10T00:00:00Z")
        var library = listOf(gone, kept)
        coEvery { repository.getAlbumList("alphabeticalByName", size = 100, offset = 0) } coAnswers { library }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()

        // A song added elsewhere brought its album in; another album left the library meanwhile.
        library = listOf(added, kept)
        revision.value = 1L
        advanceUntilIdle()

        // The whole collection read again: it replaces the list, so the album gone from the library leaves.
        val inAll = content(viewModel).allItems.orEmpty().filterIsInstance<LibraryItem.AlbumItem>().map { it.album.id }
        assertEquals(listOf(added.id, kept.id), inAll)
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()
        assertEquals(listOf(added.id, kept.id), content(viewModel).albums.orEmpty().map(Album::id))
    }

    @Test
    fun should_keepMergedAlbums_when_rereadFailsMidway() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_APPLE_MUSIC, ServiceFeatureCatalog.appleMusic.capabilities)
        val revision = MutableStateFlow(0L)
        every { repository.libraryRevision } returns revision
        val firstPage = appleNumbered(0 until 100)
        val tail = appleAlbum("library:l.tail", "Tail", added = "2020-01-01T00:00:00Z")
        val added = appleAlbum("library:l.added", "Added", added = "2026-10-10T00:00:00Z")
        var rereading = false
        coEvery { repository.getAlbumList("alphabeticalByName", size = 100, offset = 0) } coAnswers {
            if (rereading) appleNumbered(0 until 99) + added else firstPage
        }
        coEvery { repository.getAlbumList("alphabeticalByName", size = 100, offset = 100) } coAnswers {
            if (rereading) error("Apple Music unavailable") else listOf(tail)
        }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()
        assertEquals(101, content(viewModel).albums.orEmpty().size)

        rereading = true
        revision.value = 1L
        advanceUntilIdle()

        // The read stopped at its second page: the albums it didn't get to again stay, the new one is in.
        assertEquals(
            (firstPage + tail + added).map(Album::id).toSet(),
            content(viewModel).albums.orEmpty().map(Album::id).toSet()
        )
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
        // Songs is the albums' songs, no random mix to redraw.
        assertFalse(state.canReshuffleSongs)
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
    fun should_flipHeartInPlace_when_favoriteChangesOnSubsonicSongsTab() = runTest {
        val overrides = MutableStateFlow<Map<MediaId, Boolean>>(emptyMap())
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, setOf(Capability.FAVORITES, Capability.RANDOM_SONGS))
        every { repository.favoriteOverrides } returns overrides
        repository.stubNewestAlbums(listOf(albumWithSongs("al", "a", "b")))
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        overrides.value = mapOf(MediaId.subsonic("a") to true)
        advanceUntilIdle()

        // The heart flips in place; the list is neither re-read nor cut.
        val songs = (viewModel.uiState.value as LibraryUiState.Content).songs.orEmpty()
        assertEquals(listOf("a", "b"), songs.map { it.id.rawId })
        assertTrue(songs.first().isStarred)
        coVerify(exactly = 1) { repository.getAlbumList("newest", size = 10, offset = 0) }
        coVerify(exactly = 0) { repository.getRandomSongs(any()) }
        coVerify(exactly = 0) { repository.getLibrarySongs(any(), any()) }
        coVerify(exactly = 0) { repository.readCachedLikedSongs(any(), any()) }
        coVerify(exactly = 0) { repository.readCachedFollowedArtists() }
    }

    // ── Songs from the newest albums (Q9, Subsonic) ─────────────────────

    @Test
    fun should_listTheNewestAlbumsSongs_when_subsonicSongsTabOpens() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities)
        repository.stubNewestAlbums(twelveAlbums())
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        // The first page: ten albums, newest first, each album's songs in its order.
        assertEquals((1..10).flatMap { listOf("al$it-1", "al$it-2") }, state.songs.orEmpty().map { it.id.rawId })
        assertEquals(LibrarySongsMore.Available, state.songsMore)
        assertFalse(state.canReshuffleSongs)
        coVerify(exactly = 1) { repository.getAlbumList("newest", size = 10, offset = 0) }
        coVerify(exactly = 10) { repository.getAlbum(any()) }
        coVerify(exactly = 0) { repository.getRandomSongs(any()) }
        coVerify(exactly = 0) { repository.getLibrarySongs(any(), any()) }
    }

    @Test
    fun should_appendTheNextAlbums_when_moreSongsAreAskedFor() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities)
        repository.stubNewestAlbums(twelveAlbums())
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        viewModel.loadMoreSongs()
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals((1..12).flatMap { listOf("al$it-1", "al$it-2") }, state.songs.orEmpty().map { it.id.rawId })
        // Two albums on the last page: the end, and nothing at the foot.
        assertEquals(LibrarySongsMore.None, state.songsMore)

        viewModel.loadMoreSongs()
        advanceUntilIdle()
        // The second page lists the five albums before it again (NewestAlbumSongsPager.ALBUMS_REREAD).
        coVerify(exactly = 1) { repository.getAlbumList("newest", size = 15, offset = 5) }
        coVerify(exactly = 2) { repository.getAlbumList("newest", size = match { it < 100 }, offset = any()) }
    }

    @Test
    fun should_readOnePageAtATime_when_moreIsAskedForAgainWhileReading() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities)
        val secondPage = CompletableDeferred<Unit>()
        repository.stubNewestAlbums(twelveAlbums(), beforePage = { offset -> if (offset == 5) secondPage.await() })
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        viewModel.loadMoreSongs()
        runCurrent()
        assertEquals(LibrarySongsMore.Loading, (viewModel.uiState.value as LibraryUiState.Content).songsMore)
        viewModel.loadMoreSongs()
        viewModel.loadMoreSongs()
        secondPage.complete(Unit)
        advanceUntilIdle()

        assertEquals(24, (viewModel.uiState.value as LibraryUiState.Content).songs.orEmpty().size)
        coVerify(exactly = 1) { repository.getAlbumList("newest", size = 15, offset = 5) }
    }

    @Test
    fun should_offerRetryAndReadThePageAgain_when_thePageFails() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities)
        var secondPageFails = true
        repository.stubNewestAlbums(
            twelveAlbums(),
            beforePage = { offset -> if (offset == 5 && secondPageFails) error("server down") }
        )
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        viewModel.loadMoreSongs()
        advanceUntilIdle()
        val failed = viewModel.uiState.value as LibraryUiState.Content
        // The rows already read stay; the foot offers a retry.
        assertEquals(LibraryTab.Songs, failed.selectedTab)
        assertEquals(20, failed.songs.orEmpty().size)
        assertEquals(LibrarySongsMore.Failed, failed.songsMore)

        secondPageFails = false
        viewModel.loadMoreSongs()
        advanceUntilIdle()

        val retried = viewModel.uiState.value as LibraryUiState.Content
        assertEquals((1..12).flatMap { listOf("al$it-1", "al$it-2") }, retried.songs.orEmpty().map { it.id.rawId })
        assertEquals(LibrarySongsMore.None, retried.songsMore)
        coVerify(exactly = 2) { repository.getAlbumList("newest", size = 15, offset = 5) }
    }

    @Test
    fun should_readPastAnAlbumThatFailsAgain_when_retryIsTapped() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities)
        repository.stubNewestAlbums(twelveAlbums())
        // "al11" never opens: the server lists it but can't build it.
        coEvery { repository.getAlbum(MediaId.subsonic("al11")) } throws SubsonicException(code = 70, message = null)
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        viewModel.loadMoreSongs()
        advanceUntilIdle()
        assertEquals(LibrarySongsMore.Failed, (viewModel.uiState.value as LibraryUiState.Content).songsMore)
        viewModel.loadMoreSongs()
        advanceUntilIdle()

        // Failing again, "al11" is left out, and the albums after it are read.
        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(
            ((1..10) + 12).flatMap { listOf("al$it-1", "al$it-2") },
            state.songs.orEmpty().map { it.id.rawId }
        )
        assertEquals(LibrarySongsMore.None, state.songsMore)
        coVerify(exactly = 2) { repository.getAlbum(MediaId.subsonic("al11")) }
    }

    @Test
    fun should_openSongsPastAnAlbumThatFailsAgain_when_theChipIsTappedAgain() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities)
        repository.stubNewestAlbums(twelveAlbums())
        coEvery { repository.getAlbum(MediaId.subsonic("al3")) } throws SubsonicException(code = 70, message = null)
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        val before = (viewModel.uiState.value as LibraryUiState.Content).selectedTab

        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        // The first page fails: Songs steps back to where it came from.
        assertEquals(before, (viewModel.uiState.value as LibraryUiState.Content).selectedTab)
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        // The same pager reads again, and "al3", failing again, is left out.
        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(LibraryTab.Songs, state.selectedTab)
        assertEquals(
            (1..10).filter { it != 3 }.flatMap { listOf("al$it-1", "al$it-2") },
            state.songs.orEmpty().map { it.id.rawId }
        )
        assertEquals(LibrarySongsMore.Available, state.songsMore)
        coVerify(exactly = 2) { repository.getAlbum(MediaId.subsonic("al3")) }
    }

    @Test
    fun should_cancelThePageBeingRead_when_libraryRefreshes() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities)
        val secondPage = CompletableDeferred<Unit>()
        var secondPageCancelled = false
        repository.stubNewestAlbums(twelveAlbums(), beforePage = { offset ->
            if (offset == 5) {
                try {
                    secondPage.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    secondPageCancelled = true
                    throw e
                }
            }
        })
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        viewModel.loadMoreSongs()
        runCurrent()

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(secondPageCancelled)
        val refreshed = viewModel.uiState.value as LibraryUiState.Content
        assertNull(refreshed.songs)
        assertEquals(LibrarySongsMore.None, refreshed.songsMore)

        // Songs starts again from its first page.
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        val reopened = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(20, reopened.songs.orEmpty().size)
        assertEquals(LibrarySongsMore.Available, reopened.songsMore)
        coVerify(exactly = 2) { repository.getAlbumList("newest", size = 10, offset = 0) }
    }

    @Test
    fun should_cancelThePageBeingRead_when_profileSwitches() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities)
        val profileIds = MutableStateFlow<String?>("first")
        every { repository.currentProfileId() } answers { profileIds.value }
        every { repository.currentProfileIdFlow } returns profileIds
        val secondPage = CompletableDeferred<Unit>()
        var secondPageCancelled = false
        repository.stubNewestAlbums(twelveAlbums(), beforePage = { offset ->
            if (offset == 5) {
                try {
                    secondPage.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    secondPageCancelled = true
                    throw e
                }
            }
        })
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        viewModel.loadMoreSongs()
        runCurrent()

        profileIds.value = "second"
        advanceUntilIdle()

        assertTrue(secondPageCancelled)
        val switched = viewModel.uiState.value as LibraryUiState.Content
        assertNull(switched.songs)
        assertEquals(LibrarySongsMore.None, switched.songsMore)
        // The old account's pager is gone: nothing reads on from it.
        viewModel.loadMoreSongs()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.getAlbumList("newest", size = 15, offset = 5) }
    }

    /**
     * The repository already answers for another profile, but Library hasn't
     * heard yet (its profile collector hasn't run), so nothing has cancelled
     * the read: only loadMoreSongs' own check keeps the page out.
     */
    @Test
    fun should_dropTheOldAccountsPage_when_itArrivesAfterTheProfileChanged() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities)
        var profileId = "first"
        every { repository.currentProfileId() } answers { profileId }
        every { repository.currentProfileIdFlow } returns MutableStateFlow("first")
        val secondPage = CompletableDeferred<Unit>()
        repository.stubNewestAlbums(twelveAlbums(), beforePage = { offset -> if (offset == 5) secondPage.await() })
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        viewModel.loadMoreSongs()
        runCurrent()

        profileId = "second"
        secondPage.complete(Unit)
        advanceUntilIdle()

        // The page was read whole ("al12" opened) but landed nowhere: the first page's rows stand.
        coVerify(exactly = 1) { repository.getAlbum(MediaId.subsonic("al12")) }
        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals((1..10).flatMap { listOf("al$it-1", "al$it-2") }, state.songs.orEmpty().map { it.id.rawId })
    }

    @Test
    fun should_offerNoRetryForTheOldAccount_when_itsPageFailsAfterTheProfileChanged() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities)
        var profileId = "first"
        every { repository.currentProfileId() } answers { profileId }
        every { repository.currentProfileIdFlow } returns MutableStateFlow("first")
        val secondPage = CompletableDeferred<Unit>()
        repository.stubNewestAlbums(twelveAlbums(), beforePage = { offset ->
            if (offset == 5) {
                secondPage.await()
                error("server down")
            }
        })
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        viewModel.loadMoreSongs()
        runCurrent()

        profileId = "second"
        secondPage.complete(Unit)
        advanceUntilIdle()

        // The old account's failure isn't this account's to retry.
        val state = viewModel.uiState.value as LibraryUiState.Content
        assertTrue(state.songsMore != LibrarySongsMore.Failed)
        assertEquals(20, state.songs.orEmpty().size)
    }

    @Test
    fun should_askForNotesAFewHundredAtATime_when_songsOutgrowOneQuery() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities)
        // Sixty albums of ten: six hundred songs once three pages are read.
        repository.stubNewestAlbums((1..60).map { a -> albumWithSongs("al$a", *Array(10) { "al$a-$it" }) })
        val asked = mutableListOf<Int>()
        every { repository.observeTracksWithNotes(any()) } answers {
            val ids = firstArg<Collection<MediaId>>()
            asked += ids.size
            flowOf(ids.filterTo(HashSet()) { it.rawId == "al1-0" || it.rawId == "al60-9" })
        }
        val viewModel = libraryViewModel(repository)
        backgroundScope.launch { viewModel.notedSongIds.collect {} }
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        repeat(5) {
            viewModel.loadMoreSongs()
            advanceUntilIdle()
        }

        assertEquals(600, (viewModel.uiState.value as LibraryUiState.Content).songs.orEmpty().size)
        assertEquals(setOf("subsonic:al1-0", "subsonic:al60-9"), viewModel.notedSongIds.value)
        assertTrue(asked.all { it <= 500 })
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

        // An artist page opened, and Library came back into view: it moves up in place, nothing fetched again.
        records.value = records.value.copy(artists = mapOf("ar" to 200L))
        viewModel.onLibraryShown()
        advanceUntilIdle()
        assertEquals(
            listOf("artist:subsonic:ar", "playlist:subsonic:pl", "album:subsonic:new", "album:subsonic:old"),
            allKeys()
        )
        coVerify(exactly = 1) { repository.getAlbumList(any(), any(), any()) }
    }

    @Test
    fun should_holdRecentsBack_when_libraryStaysInView() = runTest {
        val records = MutableStateFlow(LibraryRecents.None)
        val repository = subsonicLibrary(
            albums = listOf(
                album("old", "Old", added = "2020-01-01T00:00:00Z"),
                album("new", "New", added = "2024-01-01T00:00:00Z")
            )
        )
        val viewModel = libraryViewModel(repository, recentsSource = { _, _ -> records })
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()
        fun albumIds() = content(viewModel).albums.orEmpty().map { it.id.rawId }
        assertEquals(listOf("new", "old"), albumIds())

        // Played while the list is on screen (a Wide split, the album beside it): the order holds.
        records.value = LibraryRecents(albums = mapOf("old" to 500L))
        advanceUntilIdle()
        assertEquals(listOf("new", "old"), albumIds())

        // The detail column closes: Library is back in view, and takes it in once.
        viewModel.onLibraryShown()
        advanceUntilIdle()
        assertEquals(listOf("old", "new"), albumIds())
    }

    @Test
    fun should_readRecentsOnceForABurst_when_libraryComesIntoViewTwiceAtOnce() = runTest {
        var reads = 0
        val repository = subsonicLibrary()
        val viewModel = libraryViewModel(
            repository,
            recentsSource = { _, _ ->
                reads++
                flowOf(LibraryRecents.None)
            }
        )
        advanceUntilIdle()
        assertEquals(1, reads)

        // Composed and resumed in the same moment.
        viewModel.onLibraryShown()
        viewModel.onLibraryShown()
        advanceUntilIdle()

        assertEquals(2, reads)
    }

    @Test
    fun should_resortOnlyTheRecentsViews_when_recentsChange() = runTest {
        val records = MutableStateFlow(LibraryRecents.None)
        val store = LibrarySortStore.InMemory()
        LibraryTab.entries.forEach { store.setSort("test-profile", it, LibrarySort.Alphabetical) }
        var sortPasses = 0
        val viewModel = LibraryViewModel(
            repository = subsonicLibrary(),
            sortStore = store,
            recentsSource = { _, _ -> records },
            sortDispatcher = mainDispatcherRule.dispatcher,
            nameOrder = {
                sortPasses++
                String.CASE_INSENSITIVE_ORDER
            },
            scrollIndex = { JvmLibraryScrollIndex }
        )
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()
        val sortedBefore = sortPasses

        // Every view is A–Z: new recents leave each of them as it was.
        records.value = LibraryRecents(albums = mapOf("al" to 500L))
        viewModel.onLibraryShown()
        advanceUntilIdle()
        assertEquals(sortedBefore, sortPasses)

        // Albums back on Recents: that view alone sorts again.
        viewModel.selectSort(LibraryTab.Albums, LibrarySort.Recents)
        advanceUntilIdle()
        assertEquals(sortedBefore + 1, sortPasses)
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
    fun should_dropAlbumFromAllAndAlbums_when_albumRemovedFromLibrary() = runTest {
        val repository = spotifyRepository()
        val albumsRevision = MutableStateFlow(0L)
        every { repository.libraryAlbumsRevision } returns albumsRevision
        val kept = album("kept", "Kept").copy(id = MediaId.spotify("kept"))
        val removed = album("removed", "Removed").copy(id = MediaId.spotify("removed"))
        var mirror = spotifySnapshot().copy(albums = listOf(kept, removed))
        coEvery { repository.getSpotifyLocalSearchSnapshot() } coAnswers { mirror }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()
        assertEquals(listOf("kept", "removed"), content(viewModel).albums.orEmpty().map { it.id.rawId }.sorted())

        // Removed on its album page: the write dropped its mirror row, then told Library.
        mirror = mirror.copy(albums = listOf(kept))
        albumsRevision.value = 1L
        advanceUntilIdle()

        val state = content(viewModel)
        assertEquals(listOf("kept"), state.albums.orEmpty().map { it.id.rawId })
        assertFalse("album:spotify:removed" in state.allItems.orEmpty().map(LibraryItem::key))
        // From the mirror alone: neither a service read nor a sync.
        coVerify(exactly = 0) { repository.getAlbumList(any(), any(), any()) }
        coVerify(exactly = 1) { repository.refreshSpotifyLibrary(any()) }
    }

    @Test
    fun should_addAlbumToAllAndAlbums_when_albumSavedToLibrary() = runTest {
        val repository = spotifyRepository()
        val albumsRevision = MutableStateFlow(0L)
        every { repository.libraryAlbumsRevision } returns albumsRevision
        val saved = album("new", "New").copy(id = MediaId.spotify("new"), libraryAddedAt = "2026-10-10T08:00:00Z")
        var mirror = spotifySnapshot()
        coEvery { repository.getSpotifyLocalSearchSnapshot() } coAnswers { mirror }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()

        mirror = mirror.copy(albums = listOf(saved) + mirror.albums)
        albumsRevision.value = 1L
        advanceUntilIdle()

        assertTrue("album:spotify:new" in content(viewModel).allItems.orEmpty().map(LibraryItem::key))
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()
        assertEquals("new", content(viewModel).albums.orEmpty().first().id.rawId)
    }

    @Test
    fun should_leaveRemovedAlbumOutOfLibrarySearch_when_albumRemovedOnItsPage() = runTest {
        val repository = spotifyRepository()
        val albumsRevision = MutableStateFlow(0L)
        every { repository.libraryAlbumsRevision } returns albumsRevision
        val first = album("one", "Saved One").copy(id = MediaId.spotify("one"))
        val second = album("two", "Saved Two").copy(id = MediaId.spotify("two"))
        var mirror = spotifySnapshot().copy(albums = listOf(first, second))
        coEvery { repository.getSpotifyLocalSearchSnapshot() } coAnswers { mirror }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()
        viewModel.search("Saved")
        advanceUntilIdle()
        val found = content(viewModel).searchResults?.albums.orEmpty().map { it.id.rawId }
        assertEquals(setOf("one", "two"), found.toSet())

        mirror = mirror.copy(albums = listOf(first))
        albumsRevision.value = 1L
        advanceUntilIdle()

        // The search on screen runs again against the mirror, not the albums All loaded.
        assertEquals(listOf("one"), content(viewModel).searchResults?.albums.orEmpty().map { it.id.rawId })
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
        // Apple Music lists a library album with no catalog match by its library id; the album
        // page records its visit and plays under the id it resolves to, which Library can't match.
        val records = MutableStateFlow(LibraryRecents(albums = mapOf("1440000001" to 900L)))
        val repository = repositoryFor(MediaId.PROVIDER_APPLE_MUSIC, setOf(Capability.LIBRARY_SONGS))
        coEvery { repository.getAlbumList("alphabeticalByName", size = 100, offset = 0) } returns listOf(
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
        // Back from the album page.
        viewModel.onLibraryShown()
        advanceUntilIdle()

        assertEquals(listOf("library:l.opened", "library:l.new"), albumIds())
        assertEquals(
            mapOf("library:l.opened" to 1_000L),
            openStore.observe("test-profile", MediaId.PROVIDER_APPLE_MUSIC).first().albums
        )
    }

    @Test
    fun should_recordASearchOpenOnlyInTheLibraryScope_when_openedFromSearch() = runTest {
        val repository = spotifyRepository()
        val openStore = LibraryOpenStore.InMemory()
        val viewModel = libraryViewModel(repository, openStore = openStore, clock = { 7L })
        advanceUntilIdle()
        suspend fun opened() = openStore.observe("test-profile", MediaId.PROVIDER_SPOTIFY).first().albums

        // A catalog result: not in the library, so it takes no place among its records.
        viewModel.openSearchShortcut(LibrarySearchScope.SpotifyGlobal)
        viewModel.recordOpened(LibraryOpenKind.Album, "spotify:catalog", fromSearch = true)
        advanceUntilIdle()
        assertEquals(emptyMap<String, Long>(), opened())

        // A result of the library's own search counts like any open from Library.
        viewModel.selectSearchScope(LibrarySearchScope.CurrentLibrary)
        viewModel.recordOpened(LibraryOpenKind.Album, "spotify:saved", fromSearch = true)
        viewModel.recordOpened(LibraryOpenKind.Album, "spotify:grid")
        advanceUntilIdle()
        assertEquals(mapOf("saved" to 7L, "grid" to 7L), opened())
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
        viewModel.onLibraryShown()
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
        assertEquals(
            listOf(LibrarySort.Recents, LibrarySort.Alphabetical, LibrarySort.Creator),
            state.sortOptions[LibraryTab.Playlists]
        )
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

    // ── The whole album collection (provider-runtime-1) ─────────────────

    @Test
    fun should_readTheRestOfTheAlbumsInTheBackground_when_theFirstBatchIsFull() = runTest {
        val repository = subsonicLibrary(albums = numberedAlbums(0 until 500))
        coEvery { repository.getAlbumList("newest", size = 500, offset = 500) } returns numberedAlbums(500 until 620)
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        viewModel.selectSort(LibraryTab.Albums, LibrarySort.Alphabetical)
        advanceUntilIdle()

        // Every album, the oldest too: A–Z runs over the whole library, not its newest 500.
        val state = content(viewModel)
        assertEquals(620, state.albums.orEmpty().size)
        assertEquals(620, state.allItems.orEmpty().count { it is LibraryItem.AlbumItem })
        // A batch short of 500 was the last: nothing past it is asked for.
        coVerify(exactly = 2) { repository.getAlbumList(any(), any(), any()) }
    }

    @Test
    fun should_readAppleMusicsLibraryAlbums_when_appleMusicIsActive() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_APPLE_MUSIC, ServiceFeatureCatalog.appleMusic.capabilities)
        val first = numberedAlbums(0 until 100, provider = MediaId.PROVIDER_APPLE_MUSIC)
        val rest = numberedAlbums(100 until 103, provider = MediaId.PROVIDER_APPLE_MUSIC)
        coEvery { repository.getAlbumList("alphabeticalByName", size = 100, offset = 0) } returns first
        coEvery { repository.getAlbumList("alphabeticalByName", size = 100, offset = 100) } returns rest
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()

        assertEquals(103, content(viewModel).albums.orEmpty().size)
        // The library's albums, not the recently-added window.
        coVerify(exactly = 0) { repository.getAlbumList("newest", any(), any()) }
    }

    @Test
    fun should_showAppleMusicsAlbumsAPageAtATime_when_allLoads() = runTest {
        val repository = repositoryFor(MediaId.PROVIDER_APPLE_MUSIC, ServiceFeatureCatalog.appleMusic.capabilities)
        val secondPage = CompletableDeferred<List<Album>>()
        coEvery { repository.getAlbumList("alphabeticalByName", size = 100, offset = 0) } returns
            numberedAlbums(0 until 100, provider = MediaId.PROVIDER_APPLE_MUSIC)
        coEvery { repository.getAlbumList("alphabeticalByName", size = 100, offset = 100) } coAnswers {
            secondPage.await()
        }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()
        // One request in: the first page is in All while the next is on its way.
        fun albumsInAll() = content(viewModel).allItems?.count { it is LibraryItem.AlbumItem }
        assertEquals(100, albumsInAll())

        secondPage.complete(numberedAlbums(100 until 130, provider = MediaId.PROVIDER_APPLE_MUSIC))
        advanceUntilIdle()
        // Merged in where it belongs; All never went back to its loading indicator.
        assertEquals(130, albumsInAll())
        coVerify(exactly = 2) { repository.getAlbumList(any(), any(), any()) }
    }

    @Test
    fun should_holdLaterPagesBack_when_theyWouldResortTheListOnScreen() = runTest {
        // Apple Music's library albums come A–Z; All sorts by Recents, here Recently added.
        applePagedAlbums(
            0 to CompletableDeferred(appleNumbered(0 until 100)),
            100 to CompletableDeferred(),
            200 to CompletableDeferred()
        ) { pages ->
            val viewModel = libraryViewModel(this)
            advanceUntilIdle()
            viewModel.ensureSelectedTabLoaded()
            advanceUntilIdle()
            fun albumKeys() = content(viewModel).allItems.orEmpty().filterIsInstance<LibraryItem.AlbumItem>()
                .map(LibraryItem::key)
            val firstShown = albumKeys()
            assertEquals(100, firstShown.size)

            // A full page whose newest album came last week: All holds as it is while the read goes on.
            val zero7 = appleAlbum("library:l.zero7", "Zero 7", added = "2026-10-10T00:00:00Z")
            pages.getValue(100).complete(appleNumbered(100 until 199) + zero7)
            advanceUntilIdle()
            assertEquals(firstShown, albumKeys())

            // The read ends: everything joins at once, the newest on top.
            pages.getValue(200).complete(appleNumbered(200 until 230))
            advanceUntilIdle()
            val keys = albumKeys()
            assertEquals(230, keys.size)
            assertEquals("album:${zero7.id}", keys.first())
        }
    }

    @Test
    fun should_addEachPageAsItLands_when_theViewSortsInTheOrderPagesCome() = runTest {
        applePagedAlbums(
            0 to CompletableDeferred(appleNumbered(0 until 100)),
            100 to CompletableDeferred(appleNumbered(100 until 200)),
            200 to CompletableDeferred()
        ) {
            val store = LibrarySortStore.InMemory()
            store.setSort("test-profile", LibraryTab.Albums, LibrarySort.Alphabetical)
            val viewModel = libraryViewModel(this, sortStore = store)
            advanceUntilIdle()
            viewModel.selectTab(LibraryTab.Albums)
            advanceUntilIdle()

            // A–Z, as Apple Music sends them: the second page extends the list's end while the third is on its way.
            assertEquals(
                appleNumbered(0 until 200).map { it.id },
                content(viewModel).albums.orEmpty().map(Album::id)
            )
        }
    }

    @Test
    fun should_letHeldAlbumsIn_when_libraryComesIntoView() = runTest {
        applePagedAlbums(
            0 to CompletableDeferred(appleNumbered(0 until 100)),
            100 to CompletableDeferred(appleNumbered(100 until 200)),
            200 to CompletableDeferred()
        ) {
            val viewModel = libraryViewModel(this)
            advanceUntilIdle()
            viewModel.ensureSelectedTabLoaded()
            advanceUntilIdle()
            fun albumsInAll() = content(viewModel).allItems.orEmpty().count { it is LibraryItem.AlbumItem }
            assertEquals(100, albumsInAll())

            // Back from a detail page, the read still going: Library re-sorts once, with the page it held.
            viewModel.onLibraryShown()
            advanceUntilIdle()
            assertEquals(200, albumsInAll())
        }
    }

    @Test
    fun should_letHeldAlbumsIn_when_theSortChanges() = runTest {
        applePagedAlbums(
            0 to CompletableDeferred(appleNumbered(0 until 100)),
            100 to CompletableDeferred(appleNumbered(100 until 200)),
            200 to CompletableDeferred(),
            300 to CompletableDeferred()
        ) { pages ->
            val viewModel = libraryViewModel(this)
            advanceUntilIdle()
            viewModel.selectTab(LibraryTab.Albums)
            advanceUntilIdle()
            fun albums() = content(viewModel).albums.orEmpty().size
            assertEquals(100, albums())

            // Every album moves to its A–Z place anyway: the held page joins in the same move.
            viewModel.selectSort(LibraryTab.Albums, LibrarySort.Alphabetical)
            advanceUntilIdle()
            assertEquals(200, albums())

            // Now in the order the pages come, the next one joins as it lands.
            pages.getValue(200).complete(appleNumbered(200 until 300))
            advanceUntilIdle()
            assertEquals(300, albums())
        }
    }

    @Test
    fun should_addEachBatchAsItLands_when_subsonicSortsByRecentlyAdded() = runTest {
        val repository = subsonicLibrary(albums = numberedAlbums(0 until 500))
        coEvery { repository.getAlbumList("newest", size = 500, offset = 500) } returns numberedAlbums(500 until 1_000)
        val lastBatch = CompletableDeferred<List<Album>>()
        coEvery { repository.getAlbumList("newest", size = 500, offset = 1_000) } coAnswers { lastBatch.await() }
        val store = LibrarySortStore.InMemory()
        store.setSort("test-profile", LibraryTab.Albums, LibrarySort.RecentlyAdded)
        val viewModel = libraryViewModel(repository, sortStore = store)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()

        // Subsonic's batches come newest first: the second only extends the list's end.
        assertEquals(1_000, content(viewModel).albums.orEmpty().size)
    }

    @Test
    fun should_keepSpotifysAlbumsAsTheyAre_when_theListIsRead() = runTest {
        val repository = spotifyRepository()
        coEvery { repository.getAlbumList("newest", size = 500, offset = 0) } returns
            numberedAlbums(0 until 200, provider = MediaId.PROVIDER_SPOTIFY)
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()

        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()

        // The synced 200 stay the list (Q10): no further batch.
        assertEquals(200, content(viewModel).albums.orEmpty().size)
        coVerify(exactly = 1) { repository.getAlbumList(any(), any(), any()) }
    }

    @Test
    fun should_dropTheOldAccountsAlbums_when_theProfileSwitchesMidRead() = runTest {
        val repository = subsonicLibrary(albums = numberedAlbums(0 until 500))
        val profiles = MutableStateFlow("first")
        every { repository.currentProfileId() } answers { profiles.value }
        every { repository.currentProfileIdFlow } returns profiles
        val secondBatch = CompletableDeferred<List<Album>>()
        coEvery { repository.getAlbumList("newest", size = 500, offset = 500) } coAnswers { secondBatch.await() }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()

        profiles.value = "second"
        advanceUntilIdle()
        secondBatch.complete(numberedAlbums(500 until 510))
        advanceUntilIdle()

        // The read was cancelled with the account: the new one starts from nothing.
        assertNull(content(viewModel).albums)
    }

    @Test
    fun should_readOnFromWhereItStopped_when_aBatchFailed() = runTest {
        val repository = subsonicLibrary(albums = numberedAlbums(0 until 500))
        var failing = true
        coEvery { repository.getAlbumList("newest", size = 500, offset = 500) } coAnswers {
            if (failing) error("server down") else numberedAlbums(500 until 501)
        }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()
        // The first batch stays; nothing was lost.
        assertEquals(500, content(viewModel).albums.orEmpty().size)

        failing = false
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()

        assertEquals(501, content(viewModel).albums.orEmpty().size)
        coVerify(exactly = 1) { repository.getAlbumList("newest", size = 500, offset = 0) }
    }

    @Test
    fun should_stopReading_when_aBatchBringsNothingNew() = runTest {
        // A server that ignores offset: every request answers with the same newest 500.
        val repository = subsonicLibrary(albums = numberedAlbums(0 until 500))
        coEvery { repository.getAlbumList("newest", size = 500, offset = any()) } returns numberedAlbums(0 until 500)
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()

        assertEquals(500, content(viewModel).albums.orEmpty().size)
        // The first batch, then the background read's own first, then one with nothing it hadn't had: done.
        coVerify(exactly = 3) { repository.getAlbumList(any(), any(), any()) }

        // Read to its end: Library's next look asks for nothing more.
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()
        coVerify(exactly = 3) { repository.getAlbumList(any(), any(), any()) }
    }

    @Test
    fun should_stopReading_when_theCollectionReachesTheCeiling() = runTest {
        // Full batches of albums never sent before, however far the offset goes.
        val repository = subsonicLibrary(albums = numberedAlbums(0 until 500))
        coEvery { repository.getAlbumList("newest", size = 500, offset = any()) } coAnswers {
            val offset = thirdArg<Int>()
            numberedAlbums(offset until offset + 500)
        }
        val viewModel = libraryViewModel(repository, albumsReadCeiling = 1_500)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()

        assertEquals(1_500, content(viewModel).albums.orEmpty().size)
        coVerify(exactly = 3) { repository.getAlbumList(any(), any(), any()) }

        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()
        coVerify(exactly = 3) { repository.getAlbumList(any(), any(), any()) }
    }

    // ── Fast scroller sections (U2) ─────────────────────────────────────

    @Test
    fun should_cutArtistsByLetter_when_sortedAlphabetically() = runTest {
        val repository = subsonicLibrary(
            artists = listOf("Beta", "alpha", "The Cure", "2Pac", "Bravo")
        )
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Artists)
        advanceUntilIdle()

        // Recents: the handle alone.
        assertEquals(emptyList<FastScrollSection>(), content(viewModel).scrollSections[LibraryTab.Artists])

        viewModel.selectSort(LibraryTab.Artists, LibrarySort.Alphabetical)
        advanceUntilIdle()

        val state = content(viewModel)
        // Subsonic skips "The"; digits and symbols last.
        assertEquals(listOf("alpha", "Beta", "Bravo", "The Cure", "2Pac"), state.artists.orEmpty().map { it.name })
        assertEquals(
            listOf(section("A", 0), section("B", 1), section("C", 3), section("#", 4)),
            state.scrollSections[LibraryTab.Artists]
        )
    }

    @Test
    fun should_cutRecentsByWhenEachWasLastOpened_when_viewSortsByRecents() = runTest {
        // Wednesday 14 October 2026, noon UTC.
        val now = 1_791_979_200_000L
        val day = 86_400_000L
        val records = MutableStateFlow(
            LibraryRecents(albums = mapOf("today" to now - 1_000L, "sep" to now - 30 * day, "jul" to now - 90 * day))
        )
        val repository = subsonicLibrary(
            albums = listOf(
                album("never", "Never", added = "2020-01-01T00:00:00Z"),
                album("jul", "July"),
                album("today", "Today"),
                album("sep", "September")
            )
        )
        val viewModel = libraryViewModel(repository, recentsSource = { _, _ -> records }, clock = { now })
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()

        val state = content(viewModel)
        assertEquals(LibrarySort.Recents, state.sorts[LibraryTab.Albums])
        assertEquals(listOf("today", "sep", "jul", "never"), state.albums.orEmpty().map { it.id.rawId })
        // Today, then months; what was never opened in Yoin is the last section.
        assertEquals(
            listOf(
                section(RecentsLabel.Today.key, 0),
                FastScrollSection("2026-09", 1, "09"),
                FastScrollSection("2026-07", 2, "07"),
                section(RecentsLabel.NotOpened.key, 3)
            ),
            state.scrollSections[LibraryTab.Albums]
        )
    }

    @Test
    fun should_followTheSort_when_albumsSortChanges() = runTest {
        val albums = listOf(
            album("n", "Nocturne", added = "2024-03-01T00:00:00Z").copy(artist = "Zola"),
            album("a", "Aurora", added = "2023-06-01T00:00:00Z").copy(artist = "Moss"),
            album("b", "Bloom", added = "2021-01-01T00:00:00Z").copy(artist = "Avery"),
            album("m", "Meridian", added = "2019-09-01T00:00:00Z").copy(artist = null)
        )
        val viewModel = libraryViewModel(subsonicLibrary(albums = albums))
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()

        fun shown() = content(viewModel).albums.orEmpty().map { it.id.rawId }
        fun sections() = content(viewModel).scrollSections[LibraryTab.Albums]

        assertEquals(emptyList<FastScrollSection>(), sections())

        viewModel.selectSort(LibraryTab.Albums, LibrarySort.RecentlyAdded)
        advanceUntilIdle()
        assertEquals(listOf("n", "a", "b", "m"), shown())
        assertEquals(
            listOf(section("2024", 0), section("2023", 1), section("2021", 2), section("2019", 3)),
            sections()
        )

        viewModel.selectSort(LibraryTab.Albums, LibrarySort.Alphabetical)
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "m", "n"), shown())
        assertEquals(
            listOf(section("A", 0), section("B", 1), section("M", 2), section("N", 3)),
            sections()
        )

        // By the artist's letter; the album with no artist trails under "#".
        viewModel.selectSort(LibraryTab.Albums, LibrarySort.Creator)
        advanceUntilIdle()
        assertEquals(listOf("b", "a", "n", "m"), shown())
        assertEquals(
            listOf(section("A", 0), section("M", 1), section("Z", 2), section("#", 3)),
            sections()
        )
    }

    @Test
    fun should_keepEachLetterOneRun_when_allMixesKindsAlphabetically() = runTest {
        val repository = subsonicLibrary(
            artists = listOf("Beta", "Echo"),
            albums = listOf(album("al", "Bloom"), album("a2", "Echoes"))
        )
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        viewModel.selectSort(LibraryTab.All, LibrarySort.Alphabetical)
        advanceUntilIdle()

        val state = content(viewModel)
        val names = state.allItems.orEmpty().map { item ->
            when (item) {
                is LibraryItem.ArtistItem -> item.artist.name
                is LibraryItem.AlbumItem -> item.album.name
                is LibraryItem.PlaylistItem -> item.playlist.name
            }
        }
        assertEquals(listOf("Beta", "Bloom", "Echo", "Echoes", "Playlist"), names)
        assertEquals(
            listOf(section("B", 0), section("E", 2), section("P", 4)),
            state.scrollSections[LibraryTab.All]
        )
        // Playlists has no scroller: no sections of its own.
        assertNull(state.scrollSections[LibraryTab.Playlists])
    }

    private fun content(viewModel: LibraryViewModel) = viewModel.uiState.value as LibraryUiState.Content

    private fun section(label: String, start: Int) = FastScrollSection(label, startIndex = start)

    /** A Subsonic library with the given artists (one by default), albums and one playlist. */
    private fun subsonicLibrary(
        capabilities: Set<Capability> = setOf(Capability.FAVORITES, Capability.RANDOM_SONGS, Capability.PLAYLISTS_READ),
        albums: List<Album> = listOf(album("al", "Album")),
        artists: List<String>? = null
    ): YoinRepository = repositoryFor(MediaId.PROVIDER_SUBSONIC, capabilities).also { repository ->
        coEvery { repository.getArtists() } returns if (artists == null) {
            listOf(ArtistIndex("A", listOf(Artist(MediaId.subsonic("ar"), "Artist", null, null))))
        } else {
            listOf(
                ArtistIndex(
                    "*",
                    artists.mapIndexed { i, name -> Artist(MediaId.subsonic("ar$i"), name, null, null) }
                )
            )
        }
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

    /** Albums "n000"… named after their number. */
    private fun numberedAlbums(numbers: IntRange, provider: String = MediaId.PROVIDER_SUBSONIC): List<Album> =
        numbers.map { n ->
            val rawId = "n%03d".format(n)
            album(rawId, rawId, added = "2020-01-01T00:00:00Z").copy(id = MediaId(provider, rawId))
        }

    private fun appleNumbered(numbers: IntRange): List<Album> =
        numberedAlbums(numbers, provider = MediaId.PROVIDER_APPLE_MUSIC)

    /**
     * An Apple Music account whose library albums come a page of 100 at a
     * time, each page answering when its [pages] entry (by offset) completes,
     * and [block] run against it.
     */
    private suspend fun applePagedAlbums(
        vararg pages: Pair<Int, CompletableDeferred<List<Album>>>,
        block: suspend YoinRepository.(Map<Int, CompletableDeferred<List<Album>>>) -> Unit
    ) {
        val repository = repositoryFor(MediaId.PROVIDER_APPLE_MUSIC, ServiceFeatureCatalog.appleMusic.capabilities)
        val byOffset = pages.toMap()
        coEvery { repository.getAlbumList("alphabeticalByName", size = 100, offset = any()) } coAnswers {
            byOffset[thirdArg<Int>()]?.await().orEmpty()
        }
        repository.block(byOffset)
    }

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
     * advanceUntilIdle covers it) and a JVM name order and scroll index,
     * since android.icu is a stub off device.
     */
    private fun libraryViewModel(
        repository: YoinRepository,
        sortStore: LibrarySortStore = LibrarySortStore.InMemory(),
        recentsSource: LibraryRecentsSource = LibraryRecentsSource.None,
        openStore: LibraryOpenStore = LibraryOpenStore.InMemory(),
        clock: () -> Long = { 0L },
        // Production's ceiling.
        albumsReadCeiling: Int = 100_000
    ): LibraryViewModel = LibraryViewModel(
        repository = repository,
        sortStore = sortStore,
        recentsSource = recentsSource,
        openStore = openStore,
        clock = clock,
        sortDispatcher = mainDispatcherRule.dispatcher,
        nameOrder = { String.CASE_INSENSITIVE_ORDER },
        scrollIndex = { JvmLibraryScrollIndex },
        albumsReadCeiling = albumsReadCeiling
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
        every { repository.libraryAlbumsRevision } returns flowOf(0L)
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

    /** Twelve albums, newest first, two songs each. */
    private fun twelveAlbums(): List<Album> = (1..12).map { albumWithSongs("al$it", "al$it-1", "al$it-2") }

    private fun albumWithSongs(rawId: String, vararg songs: String): Album =
        album(rawId, rawId).copy(tracks = songs.map(::subsonicTrack))

    /**
     * Subsonic's newest albums, a page at a time ([NewestAlbumSongsPager]: the
     * first page is offset 0 size 10, the second offset 5 size 15), and each
     * album as its page opens it. [beforePage] runs before a page is read.
     * Songs' pages only: the Albums list reads 500 at once.
     */
    private fun YoinRepository.stubNewestAlbums(albums: List<Album>, beforePage: suspend (offset: Int) -> Unit = {}) {
        coEvery { getAlbumList("newest", size = match { it < 100 }, offset = any()) } coAnswers {
            val offset = thirdArg<Int>()
            beforePage(offset)
            albums.drop(offset).take(secondArg<Int>())
        }
        coEvery { getAlbum(any()) } coAnswers { albums.firstOrNull { it.id == firstArg<MediaId>() } }
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
