package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeLayoutStore
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomePlaylistRenameTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val renamed = Playlist(
        id = MediaId.subsonic("pl-1"),
        name = "Old name",
        owner = "owner",
        coverArt = null,
        songCount = 5,
        durationSec = 600,
    )
    private val other = renamed.copy(id = MediaId.subsonic("pl-2"), name = "Other")

    @Test
    fun should_showNewNameOnActivitiesJumpBackInAndPlaylists_when_playlistRenamed() = runTest {
        val profile = "rename-profile"
        val renames = MutableSharedFlow<YoinRepository.PlaylistRename>(extraBufferCapacity = 4)
        val repository = repository(
            activities = listOf(
                playlistPlayed(renamed, profile, entityId = renamed.id.rawId),
                playlistPlayed(other, profile, entityId = other.id.rawId),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            activeProfileId = MutableStateFlow(profile),
            homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true).also { store ->
                every { store.layoutFlow(any()) } returns flowOf(null)
            },
            playlistRenames = renames,
        )
        advanceUntilIdle()
        val before = viewModel.uiState.value as HomeUiState.Content
        assertEquals("Old name", before.cardTitle(renamed))
        coVerify(exactly = 1) { repository.getPlaylists() }

        renames.emit(YoinRepository.PlaylistRename(profile, renamed.id, "New name"))
        advanceUntilIdle()

        val after = viewModel.uiState.value as HomeUiState.Content
        assertEquals("New name", after.activities.single { it.entityId == renamed.id.rawId }.title)
        assertEquals("Other", after.activities.single { it.entityId == other.id.rawId }.title)
        assertEquals("New name", after.cardTitle(renamed))
        assertEquals("Other", after.cardTitle(other))
        assertEquals(listOf("New name", "Other"), after.playlists.map { it.name })
        // Without reading Home again.
        coVerify(exactly = 1) { repository.getPlaylists() }
    }

    @Test
    fun should_leaveFeed_when_renameIsAnotherAccounts() = runTest {
        val profile = "rename-profile-other"
        val renames = MutableSharedFlow<YoinRepository.PlaylistRename>(extraBufferCapacity = 4)
        val repository = repository(activities = listOf(playlistPlayed(renamed, profile, entityId = renamed.id.rawId)))
        val viewModel = HomeViewModel(
            repository = repository,
            activeProfileId = MutableStateFlow(profile),
            homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true).also { store ->
                every { store.layoutFlow(any()) } returns flowOf(null)
            },
            playlistRenames = renames,
        )
        advanceUntilIdle()
        val before = viewModel.uiState.value

        renames.emit(YoinRepository.PlaylistRename("someone-else", renamed.id, "New name"))
        advanceUntilIdle()

        assertSame(before, viewModel.uiState.value)
    }

    @Test
    fun should_matchStoredMediaIdForm_when_renamingActivityRows() {
        val content = HomeUiState.Content(
            activities = listOf(playlistPlayed(renamed, "p", entityId = renamed.id.toString())),
        )

        val next = content.withPlaylistName(renamed.id, "New name")

        assertEquals("New name", next.activities.single().title)
        assertSame(next, next.withPlaylistName(renamed.id, "New name"))
    }

    private fun HomeUiState.Content.cardTitle(playlist: Playlist): String? =
        widgetGrid.firstOrNull { it.target == HomeWidgetTarget.PlaylistDetail(playlist.id.toString()) }?.title

    private fun playlistPlayed(playlist: Playlist, profile: String, entityId: String) = ActivityEvent(
        id = playlist.id.rawId.hashCode().toLong(),
        entityType = ActivityEntityType.PLAYLIST.name,
        actionType = ActivityActionType.PLAYED.name,
        entityId = entityId,
        profileId = profile,
        provider = MediaId.PROVIDER_SUBSONIC,
        title = playlist.name,
        subtitle = "owner",
        timestamp = 1_000L,
    )

    private fun repository(activities: List<ActivityEvent>): YoinRepository {
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
        every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SUBSONIC)
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.getRecentActivities(limit = any()) } returns flowOf(activities)
        every { repository.observeMemorySignalStamp() } returns flowOf()
        every { repository.observeMostRecentPlay() } returns flowOf()
        every { repository.resolveCoverUrl(any(), any()) } returns null
        every { repository.getRating(any()) } returns flowOf(null)
        coEvery { repository.getRecentSongNotes(any()) } returns emptyList()
        coEvery { repository.getMostRecentPlay(any()) } returns null
        coEvery { repository.getStarred() } returns Starred()
        coEvery { repository.getCachedHomeGridPools(any()) } returns null
        coEvery { repository.getCachedHomeGridPools(isNull()) } returns null
        coEvery { repository.getAlbumList("random", any(), any()) } returns emptyList()
        coEvery { repository.getRandomSongs(any()) } returns emptyList()
        coEvery { repository.getPlaylists() } returns listOf(renamed, other)
        coEvery {
            repository.replaceHomeGridPools(albums = any(), tracks = any(), playlists = any())
        } just runs
        return repository
    }
}
