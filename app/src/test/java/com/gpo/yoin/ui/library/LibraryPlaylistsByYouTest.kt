package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Playlists' By You sub-chip: when it shows, what it lists, and how long the
 * choice lasts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPlaylistsByYouTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val mine = playlist("mine", ownedByMe = true)
    private val alsoMine = playlist("also-mine", ownedByMe = true)
    private val theirs = playlist("theirs", ownedByMe = false)
    private val unknown = playlist("unknown", ownedByMe = null)

    @Test
    fun should_offerByYou_when_playlistsMixOwnedAndOthers() {
        assertTrue(listOf(mine, theirs).hasMixedOwnership())
        assertTrue(listOf(mine, theirs, unknown).hasMixedOwnership())
    }

    @Test
    fun should_notOfferByYou_when_ownershipIsUniformOrUnknown() {
        assertFalse(listOf(mine, alsoMine).hasMixedOwnership())
        assertFalse(listOf(theirs).hasMixedOwnership())
        assertFalse(listOf(mine, unknown).hasMixedOwnership())
        assertFalse(listOf(theirs, unknown).hasMixedOwnership())
        assertFalse(listOf(unknown).hasMixedOwnership())
        assertFalse(emptyList<Playlist>().hasMixedOwnership())
    }

    @Test
    fun should_listOnlyOwnPlaylists_when_byYouFilters() {
        val shown = shownPlaylists(
            listOf(mine, theirs, unknown, alsoMine),
            PlaylistsByYou(available = true, selected = true)
        )

        // A playlist of unknown ownership is no one's: By You leaves it out.
        assertEquals(listOf(mine, alsoMine), shown)
    }

    @Test
    fun should_listEveryPlaylist_when_byYouIsOffOrNotShowing() {
        val all = listOf(mine, theirs, unknown)

        assertEquals(all, shownPlaylists(all, PlaylistsByYou(available = true, selected = false)))
        assertEquals(all, shownPlaylists(all, PlaylistsByYou(available = false, selected = true)))
        assertNull(shownPlaylists(null, PlaylistsByYou(available = true, selected = true)))
    }

    @Test
    fun should_showByYou_when_loadedPlaylistsAreMixed() = runTest {
        val viewModel = libraryViewModel(subsonicLibrary(listOf(mine, theirs)))
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()

        assertEquals(PlaylistsByYou(available = true, selected = false), content(viewModel).playlistsByYou)
    }

    @Test
    fun should_hideByYouAndIgnoreIt_when_ownershipIsUnknown() = runTest {
        // An old server that sends no owner: nothing to narrow by.
        val viewModel = libraryViewModel(subsonicLibrary(listOf(unknown, playlist("unknown-2", ownedByMe = null))))
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()

        viewModel.selectPlaylistsByYou(true)

        assertEquals(PlaylistsByYou(available = false, selected = false), content(viewModel).playlistsByYou)
    }

    @Test
    fun should_filterPlaylistsOnly_when_byYouSelected() = runTest {
        val viewModel = libraryViewModel(subsonicLibrary(listOf(mine, theirs, unknown)))
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()

        viewModel.selectPlaylistsByYou(true)
        advanceUntilIdle()

        val state = content(viewModel)
        assertTrue(state.playlistsByYou.filtering)
        val shown = shownPlaylists(state.playlists, state.playlistsByYou).orEmpty()
        assertEquals(listOf("mine"), shown.map { it.id.rawId })
        // The whole list stays in the state, and All still mixes everyone's.
        assertEquals(3, state.playlists.orEmpty().size)
        viewModel.selectTab(LibraryTab.All)
        advanceUntilIdle()
        assertEquals(3, content(viewModel).allItems.orEmpty().count { it is LibraryItem.PlaylistItem })
    }

    @Test
    fun should_keepByYou_when_switchingChipsAndRefreshing() = runTest {
        val viewModel = libraryViewModel(subsonicLibrary(listOf(mine, theirs)))
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()
        viewModel.selectPlaylistsByYou(true)

        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()
        assertTrue(content(viewModel).playlistsByYou.selected)
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()
        assertTrue(content(viewModel).playlistsByYou.filtering)

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()
        assertTrue(content(viewModel).playlistsByYou.filtering)
    }

    @Test
    fun should_dropTheFilterButKeepTheChoice_when_listStopsBeingMixed() = runTest {
        var playlists = listOf(mine, theirs)
        val repository = subsonicLibrary(playlists)
        coEvery { repository.getPlaylists() } answers { playlists }
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()
        viewModel.selectPlaylistsByYou(true)

        // The last playlist of someone else's goes: the sub-chip folds away
        // and the list is everyone's again.
        playlists = listOf(mine)
        viewModel.invalidatePlaylists()
        advanceUntilIdle()
        assertEquals(PlaylistsByYou(available = false, selected = true), content(viewModel).playlistsByYou)
        assertFalse(content(viewModel).playlistsByYou.filtering)

        playlists = listOf(mine, theirs)
        viewModel.invalidatePlaylists()
        advanceUntilIdle()
        assertTrue(content(viewModel).playlistsByYou.filtering)
    }

    @Test
    fun should_resetByYou_when_profileSwitches() = runTest {
        val profileIds = MutableStateFlow<String?>("first")
        val repository = subsonicLibrary(listOf(mine, theirs))
        every { repository.currentProfileId() } answers { profileIds.value }
        every { repository.currentProfileIdFlow } returns profileIds
        val viewModel = libraryViewModel(repository)
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()
        viewModel.selectPlaylistsByYou(true)

        profileIds.value = "second"
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()

        assertEquals(PlaylistsByYou(available = true, selected = false), content(viewModel).playlistsByYou)
    }

    private fun content(viewModel: LibraryViewModel) = viewModel.uiState.value as LibraryUiState.Content

    private fun playlist(id: String, ownedByMe: Boolean?) = Playlist(
        id = MediaId.subsonic(id),
        name = id,
        owner = null,
        coverArt = null,
        songCount = null,
        durationSec = null,
        ownedByMe = ownedByMe
    )

    /** A Subsonic library with no artists, one album and [playlists]. */
    private fun subsonicLibrary(playlists: List<Playlist>): YoinRepository =
        mockk<YoinRepository>(relaxed = true).also { repository ->
            val capabilities = setOf(Capability.FAVORITES, Capability.RANDOM_SONGS, Capability.PLAYLISTS_READ)
            every { repository.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
            every { repository.currentCapabilities() } returns capabilities
            every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SUBSONIC)
            every { repository.capabilities } returns flowOf(capabilities)
            every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
            every { repository.trackLibraryStates } returns flowOf(emptyMap())
            every { repository.currentProfileId() } returns "test-profile"
            every { repository.currentProfileIdFlow } returns flowOf("test-profile")
            every { repository.libraryRevision } returns flowOf(0L)
            coEvery { repository.getArtists() } returns emptyList()
            coEvery { repository.getAlbumList("newest", size = 500) } returns listOf(
                Album(
                    id = MediaId.subsonic("al"),
                    name = "Album",
                    artist = "Someone",
                    artistId = null,
                    coverArt = null,
                    songCount = null,
                    durationSec = null,
                    year = null,
                    genre = null
                )
            )
            coEvery { repository.getPlaylists() } returns playlists
        }

    /** Sorting on the test dispatcher, by a JVM name order (android.icu is a stub off device). */
    private fun libraryViewModel(repository: YoinRepository) = LibraryViewModel(
        repository = repository,
        sortDispatcher = mainDispatcherRule.dispatcher,
        nameOrder = { String.CASE_INSENSITIVE_ORDER }
    )
}
