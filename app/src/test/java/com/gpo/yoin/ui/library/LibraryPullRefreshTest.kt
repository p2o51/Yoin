package com.gpo.yoin.ui.library

import app.cash.turbine.test
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.data.source.spotify.SpotifyLibrarySyncCoordinator
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A pull on a Library view ([LibraryViewModel.pullRefresh]): the view's lists
 * are read again and replace the rows in place, the indicator's flag up for
 * as long as that takes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPullRefreshTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun should_keepAllOnScreenAndTakeTheNewLists_when_allIsPulled() = runTest {
        var artists = listOf(artist("ar1", "First"))
        var albums = listOf(album("al1", "One"))
        var playlists = listOf(playlist("pl1", "Mine"))
        val rereadAlbums = CompletableDeferred<Unit>()
        var albumReads = 0
        val repository = subsonic()
        coEvery { repository.getArtists() } coAnswers { listOf(ArtistIndex("*", artists)) }
        coEvery { repository.getAlbumList("newest", size = 500) } coAnswers {
            if (albumReads++ > 0) rereadAlbums.await()
            albums
        }
        coEvery { repository.getPlaylists() } coAnswers { playlists }
        val viewModel = shownLibrary(repository)
        val shown = mutableListOf<List<LibraryItem>?>()
        backgroundScope.launch { viewModel.uiState.collect { shown += (it as LibraryUiState.Content).allItems } }
        runCurrent()

        artists = artists + artist("ar2", "Second")
        albums = listOf(album("al2", "Two")) + albums
        playlists = playlists + playlist("pl2", "Theirs")
        viewModel.refreshing().test {
            assertFalse(awaitItem())
            viewModel.pullRefresh()
            assertTrue(awaitItem())
            advanceUntilIdle()
            // The artists and playlists are in; the albums still being read keep the indicator down.
            expectNoEvents()
            assertEquals(
                setOf(
                    "artist:subsonic:ar1",
                    "artist:subsonic:ar2",
                    "album:subsonic:al1",
                    "playlist:subsonic:pl1",
                    "playlist:subsonic:pl2"
                ),
                viewModel.allKeys()
            )

            rereadAlbums.complete(Unit)
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue("album:subsonic:al2" in viewModel.allKeys())
        // All never went back to its loading indicator, and stayed the view shown.
        assertTrue(shown.none { it == null })
        assertEquals(LibraryTab.All, content(viewModel).selectedTab)
    }

    @Test
    fun should_readOnlyTheViewShown_when_aChipsViewIsPulled() = runTest {
        var playlists = listOf(playlist("pl1", "Mine"))
        val repository = subsonic()
        coEvery { repository.getPlaylists() } coAnswers { playlists }
        val viewModel = shownLibrary(repository)
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()

        playlists = playlists + playlist("pl2", "New")
        viewModel.pullRefresh()
        advanceUntilIdle()

        assertEquals(listOf("pl1", "pl2"), content(viewModel).playlists.orEmpty().map { it.id.rawId }.sorted())
        assertFalse(content(viewModel).isRefreshing)
        // All read each list once; the pull read the playlists alone.
        coVerify(exactly = 2) { repository.getPlaylists() }
        coVerify(exactly = 1) { repository.getArtists() }
        coVerify(exactly = 1) { repository.getAlbumList("newest", size = 500) }
    }

    @Test
    fun should_keepTheRowsAndSayWhich_when_theListCannotBeReadAgain() = runTest {
        var down = false
        val repository = subsonic()
        coEvery { repository.getPlaylists() } coAnswers {
            if (down) throw IllegalStateException() else listOf(playlist("pl1", "Mine"))
        }
        val viewModel = shownLibrary(repository)
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()

        down = true
        viewModel.messages.test {
            viewModel.pullRefresh()
            assertEquals("Failed to load Playlists", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()

        val state = content(viewModel)
        assertEquals(listOf("pl1"), state.playlists.orEmpty().map { it.id.rawId })
        assertEquals(LibraryTab.Playlists, state.selectedTab)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun should_bringInTheListThatFailedBefore_when_allIsPulled() = runTest {
        var down = true
        val repository = subsonic()
        coEvery { repository.getPlaylists() } coAnswers {
            if (down) throw IllegalStateException() else listOf(playlist("pl1", "Mine"))
        }
        val viewModel = shownLibrary(repository)
        assertFalse("playlist:subsonic:pl1" in viewModel.allKeys())

        down = false
        viewModel.pullRefresh()
        advanceUntilIdle()

        assertTrue("playlist:subsonic:pl1" in viewModel.allKeys())
    }

    @Test
    fun should_sayTheAlbumsStoppedShort_when_aLaterBatchFails() = runTest {
        var down = false
        val repository = subsonic()
        coEvery { repository.getAlbumList("newest", size = 500) } returns numberedAlbums(0 until 500)
        coEvery { repository.getAlbumList("newest", size = 500, offset = 500) } coAnswers {
            if (down) throw IllegalStateException() else numberedAlbums(500 until 510)
        }
        val viewModel = shownLibrary(repository)
        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()
        assertEquals(510, content(viewModel).albums.orEmpty().size)

        down = true
        viewModel.messages.test {
            viewModel.pullRefresh()
            assertEquals("Failed to load Albums", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()

        // The albums it didn't get to again stay.
        assertEquals(510, content(viewModel).albums.orEmpty().size)
        assertFalse(content(viewModel).isRefreshing)
    }

    @Test
    fun should_startSongsOverFromTheNewestAlbum_when_songsIsPulled() = runTest {
        var albums = (1..12).map { albumWithSongs("al$it", "al$it-1", "al$it-2") }
        val repository = subsonic()
        coEvery { repository.getAlbumList("newest", size = match { it < 100 }, offset = any()) } coAnswers {
            albums.drop(thirdArg<Int>()).take(secondArg<Int>())
        }
        coEvery { repository.getAlbum(any()) } coAnswers { albums.firstOrNull { it.id == firstArg<MediaId>() } }
        val viewModel = shownLibrary(repository)
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        viewModel.loadMoreSongs()
        advanceUntilIdle()
        assertEquals(24, content(viewModel).songs.orEmpty().size)

        // An album added since is the newest: it is the first the list reads again.
        albums = listOf(albumWithSongs("new", "new-1", "new-2")) + albums
        viewModel.pullRefresh()
        advanceUntilIdle()

        val songs = content(viewModel).songs.orEmpty().map { it.id.rawId }
        assertEquals(listOf("new-1", "new-2", "al1-1", "al1-2"), songs.take(4))
        // One page again, the rest read as the list nears its end.
        assertEquals(20, songs.size)
        assertEquals(LibrarySongsMore.Available, content(viewModel).songsMore)
        viewModel.loadMoreSongs()
        advanceUntilIdle()
        assertEquals(26, content(viewModel).songs.orEmpty().size)
    }

    @Test
    fun should_readPastAnAlbumThatFailsAgain_when_songsIsPulledTwice() = runTest {
        val albums = (1..3).map { albumWithSongs("al$it", "al$it-1") }
        var broken = false
        val repository = subsonic()
        coEvery { repository.getAlbumList("newest", size = match { it < 100 }, offset = any()) } coAnswers {
            albums.drop(thirdArg<Int>()).take(secondArg<Int>())
        }
        coEvery { repository.getAlbum(any()) } coAnswers {
            val id = firstArg<MediaId>()
            if (broken && id.rawId == "al2") throw IllegalStateException()
            albums.firstOrNull { it.id == id }
        }
        val viewModel = shownLibrary(repository)
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()
        assertEquals(3, content(viewModel).songs.orEmpty().size)

        broken = true
        viewModel.pullRefresh()
        advanceUntilIdle()
        // The pull failed on the album: the rows stay.
        assertEquals(listOf("al1-1", "al2-1", "al3-1"), content(viewModel).songs.orEmpty().map { it.id.rawId })

        viewModel.pullRefresh()
        advanceUntilIdle()
        // The second pull leaves the album out rather than failing on it for good.
        assertEquals(listOf("al1-1", "al3-1"), content(viewModel).songs.orEmpty().map { it.id.rawId })
    }

    @Test
    fun should_readTheLibrarysSongsAgain_when_appleSongsIsPulled() = runTest {
        var songs = listOf(track(MediaId.PROVIDER_APPLE_MUSIC, "s1", "Alpha"))
        val repository = repositoryFor(MediaId.PROVIDER_APPLE_MUSIC, ServiceFeatureCatalog.appleMusic.capabilities)
        coEvery { repository.getLibrarySongs(size = 500) } coAnswers { songs }
        val viewModel = shownLibrary(repository)
        viewModel.selectTab(LibraryTab.Songs)
        advanceUntilIdle()

        songs = songs + track(MediaId.PROVIDER_APPLE_MUSIC, "s2", "Beta")
        viewModel.pullRefresh()
        advanceUntilIdle()

        assertEquals(listOf("s1", "s2"), content(viewModel).songs.orEmpty().map { it.id.rawId })
        coVerify(exactly = 2) { repository.getLibrarySongs(size = 500) }
    }

    @Test
    fun should_readTheFavoritesAgain_when_favoritesIsPulled() = runTest {
        var starred = Starred(tracks = listOf(starredTrack("s1", "Alpha")))
        val repository = subsonic()
        coEvery { repository.getStarred() } coAnswers { starred }
        val viewModel = shownLibrary(repository)
        viewModel.selectTab(LibraryTab.Favorites)
        advanceUntilIdle()

        starred = Starred(tracks = starred.tracks + starredTrack("s2", "Beta"))
        viewModel.pullRefresh()
        advanceUntilIdle()

        assertEquals(listOf("s1", "s2"), content(viewModel).favorites?.tracks.orEmpty().map { it.id.rawId })
    }

    @Test
    fun should_waitForTheForcedSync_when_spotifyIsPulled() = runTest {
        val sync = CompletableDeferred<Result<Unit>>()
        val repository = spotify()
        coEvery { repository.refreshSpotifyLibrary(true) } coAnswers { sync.await() }
        val viewModel = shownLibrary(repository)

        viewModel.refreshing().test {
            assertFalse(awaitItem())
            viewModel.pullRefresh()
            assertTrue(awaitItem())
            advanceUntilIdle()
            expectNoEvents()

            sync.complete(Result.success(Unit))
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // Its lists are the mirror's: the pull syncs the mirror and reads no list of its own.
        coVerify(exactly = 1) { repository.refreshSpotifyLibrary(true) }
        coVerify(exactly = 1) { repository.getArtists() }
        coVerify(exactly = 0) { repository.getPlaylists() }
    }

    @Test
    fun should_takeTheFreshMirrorIn_when_spotifysSyncLands() = runTest {
        val synced = MutableStateFlow(0L)
        var followed = listOf(artist("ar1", "First", MediaId.PROVIDER_SPOTIFY))
        val repository = spotify()
        every { repository.spotifyLibrarySynced } returns synced
        coEvery { repository.getArtists() } coAnswers { listOf(ArtistIndex("*", followed)) }
        coEvery { repository.refreshSpotifyLibrary(true) } coAnswers {
            followed = followed + artist("ar2", "Second", MediaId.PROVIDER_SPOTIFY)
            synced.value += 1
            Result.success(Unit)
        }
        val viewModel = shownLibrary(repository)
        val shown = mutableListOf<List<LibraryItem>?>()
        backgroundScope.launch { viewModel.uiState.collect { shown += (it as LibraryUiState.Content).allItems } }
        runCurrent()

        viewModel.pullRefresh()
        advanceUntilIdle()

        assertEquals(
            setOf("artist:spotify:ar1", "artist:spotify:ar2", "album:spotify:al1", "playlist:spotify:pl1"),
            viewModel.allKeys()
        )
        assertTrue(shown.none { it == null })
        assertFalse(content(viewModel).isRefreshing)
    }

    @Test
    fun should_sayTheLibraryCouldNotBeRead_when_spotifysSyncFails() = runTest {
        val repository = spotify()
        coEvery { repository.refreshSpotifyLibrary(true) } returns Result.failure(IllegalStateException())
        val viewModel = shownLibrary(repository)

        viewModel.messages.test {
            viewModel.pullRefresh()
            assertEquals("Failed to load All", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()

        assertFalse(content(viewModel).isRefreshing)
    }

    @Test
    fun should_answerOnePullAtATime_when_pulledAgainWhileReading() = runTest {
        val reread = CompletableDeferred<Unit>()
        var reads = 0
        val repository = subsonic()
        coEvery { repository.getPlaylists() } coAnswers {
            if (reads++ > 0) reread.await()
            listOf(playlist("pl1", "Mine"))
        }
        val viewModel = shownLibrary(repository)
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()

        viewModel.pullRefresh()
        runCurrent()
        viewModel.pullRefresh()
        runCurrent()
        reread.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getPlaylists() }
        assertFalse(content(viewModel).isRefreshing)
    }

    @Test
    fun should_endThePull_when_theLibraryReloads() = runTest {
        val reread = CompletableDeferred<List<Playlist>>()
        var reads = 0
        val repository = subsonic()
        coEvery { repository.getPlaylists() } coAnswers {
            if (reads++ == 1) reread.await() else listOf(playlist("pl1", "Mine"))
        }
        val viewModel = shownLibrary(repository)
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()
        viewModel.pullRefresh()
        runCurrent()
        assertTrue(content(viewModel).isRefreshing)

        // Another account's settings landed: Library loads again from the start.
        viewModel.refresh()
        advanceUntilIdle()

        assertFalse(content(viewModel).isRefreshing)
        // What the pull was reading no longer lands.
        reread.complete(listOf(playlist("stale", "Stale")))
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()
        assertEquals(listOf("pl1"), content(viewModel).playlists.orEmpty().map { it.id.rawId })
        assertFalse(content(viewModel).isRefreshing)
    }

    private fun content(viewModel: LibraryViewModel) = viewModel.uiState.value as LibraryUiState.Content

    private fun LibraryViewModel.allKeys(): Set<String> = content(this).allItems.orEmpty().map(LibraryItem::key).toSet()

    /** Whether the pull indicator is down, each change once. */
    private fun LibraryViewModel.refreshing(): Flow<Boolean> =
        uiState.map { (it as? LibraryUiState.Content)?.isRefreshing == true }.distinctUntilChanged()

    /** Library loaded and on screen, All showing. */
    private fun TestScope.shownLibrary(repository: YoinRepository): LibraryViewModel {
        val viewModel = LibraryViewModel(
            repository = repository,
            clock = { 0L },
            sortDispatcher = mainDispatcherRule.dispatcher,
            nameOrder = { String.CASE_INSENSITIVE_ORDER },
            scrollIndex = { JvmLibraryScrollIndex }
        )
        advanceUntilIdle()
        viewModel.ensureSelectedTabLoaded()
        advanceUntilIdle()
        return viewModel
    }

    /** A Subsonic account with one artist, one album and one playlist. */
    private fun subsonic(): YoinRepository =
        repositoryFor(MediaId.PROVIDER_SUBSONIC, ServiceFeatureCatalog.subsonic.capabilities).also { repository ->
            coEvery { repository.getArtists() } returns listOf(ArtistIndex("*", listOf(artist("ar1", "First"))))
            coEvery { repository.getAlbumList("newest", size = 500) } returns listOf(album("al1", "One"))
            coEvery { repository.getPlaylists() } returns listOf(playlist("pl1", "Mine"))
        }

    /** A Spotify account whose mirror holds one followed artist, one saved album and one playlist. */
    private fun spotify(): YoinRepository =
        repositoryFor(MediaId.PROVIDER_SPOTIFY, ServiceFeatureCatalog.spotify.capabilities).also { repository ->
            val album = album("al1", "One").copy(id = MediaId.spotify("al1"))
            val playlist = playlist("pl1", "Mine", MediaId.PROVIDER_SPOTIFY)
            coEvery { repository.refreshSpotifyLibrary(false) } returns Result.success(Unit)
            coEvery { repository.getArtists() } returns
                listOf(ArtistIndex("*", listOf(artist("ar1", "First", MediaId.PROVIDER_SPOTIFY))))
            coEvery { repository.getSpotifyLocalSearchSnapshot() } returns
                SpotifyLibrarySyncCoordinator.SpotifyLocalSearchSnapshot(
                    artists = emptyList(),
                    albums = listOf(album),
                    tracks = emptyList(),
                    playlists = listOf(playlist),
                    starred = Starred()
                )
            coEvery { repository.getAlbumList("newest", size = 500) } returns listOf(album)
            coEvery { repository.getPlaylists() } returns listOf(playlist)
        }

    private fun repositoryFor(providerId: String, capabilities: Set<Capability>): YoinRepository =
        mockk<YoinRepository>(relaxed = true).also { repository ->
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
            every { repository.spotifyLibrarySynced } returns flowOf(0L)
            coEvery { repository.getArtists() } returns emptyList()
        }

    private fun artist(rawId: String, name: String, provider: String = MediaId.PROVIDER_SUBSONIC) =
        Artist(MediaId(provider, rawId), name, null, null)

    private fun playlist(rawId: String, name: String, provider: String = MediaId.PROVIDER_SUBSONIC) =
        Playlist(MediaId(provider, rawId), name, "me", null, null, null)

    private fun album(rawId: String, name: String) = Album(
        id = MediaId.subsonic(rawId),
        name = name,
        artist = "Someone",
        artistId = null,
        coverArt = null,
        songCount = null,
        durationSec = null,
        year = null,
        genre = null,
        libraryAddedAt = "2020-01-01T00:00:00Z"
    )

    private fun numberedAlbums(numbers: IntRange): List<Album> =
        numbers.map { n -> "n%03d".format(n) }.map { name -> album(name, name) }

    private fun albumWithSongs(rawId: String, vararg songs: String): Album =
        album(rawId, rawId).copy(tracks = songs.map { track(MediaId.PROVIDER_SUBSONIC, it, it) })

    private fun starredTrack(rawId: String, title: String) =
        track(MediaId.PROVIDER_SUBSONIC, rawId, title).copy(isStarred = true)

    private fun track(provider: String, rawId: String, title: String) = Track(
        id = MediaId(provider, rawId),
        title = title,
        artist = "Someone",
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
