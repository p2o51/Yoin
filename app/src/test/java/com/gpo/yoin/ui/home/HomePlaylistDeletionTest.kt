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
import com.gpo.yoin.ui.library.PlaylistDeletions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/**
 * A playlist deleted on its page leaves Home (Activities, Jump Back In, Your
 * Playlists) once the service took it, and only then: one still on its way
 * out, or refused, stays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomePlaylistDeletionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val deleted = Playlist(
        id = MediaId.subsonic("pl-1"),
        name = "Gone",
        owner = "owner",
        coverArt = null,
        songCount = 5,
        durationSec = 600,
    )
    private val other = deleted.copy(id = MediaId.subsonic("pl-2"), name = "Other")
    private val profile = "delete-profile"
    private val key = PlaylistDeletions.Key(profile, deleted.id)

    @Test
    fun should_dropItFromActivitiesJumpBackInAndPlaylists_when_theServiceDeletedIt() = runTest {
        val deletions = MutableStateFlow(PlaylistDeletions.State())
        val repository = repository()
        val viewModel = home(repository, deletions)
        advanceUntilIdle()
        val before = viewModel.uiState.value as HomeUiState.Content
        assertNotNull(before.cardTitle(deleted))

        deletions.value = PlaylistDeletions.State(done = listOf(PlaylistDeletions.Done(1, key)))
        advanceUntilIdle()

        val after = viewModel.uiState.value as HomeUiState.Content
        assertEquals(listOf(other.id.rawId), after.activities.map { it.entityId })
        assertNull(after.cardTitle(deleted))
        assertEquals("Other", after.cardTitle(other))
        assertEquals(listOf("Other"), after.playlists.map { it.name })
        // Without reading Home again.
        coVerify(exactly = 1) { repository.getPlaylists() }
    }

    @Test
    fun should_keepIt_when_theDeletionIsOutOrRefused() = runTest {
        val deletions = MutableStateFlow(PlaylistDeletions.State())
        val viewModel = home(repository(), deletions)
        advanceUntilIdle()
        val before = viewModel.uiState.value

        // On its way out, then refused: it never left Home.
        deletions.value = PlaylistDeletions.State(pending = setOf(key))
        advanceUntilIdle()
        deletions.value = PlaylistDeletions.State()
        advanceUntilIdle()

        assertSame(before, viewModel.uiState.value)
    }

    @Test
    fun should_leaveFeed_when_theDeletionIsAnotherAccounts() = runTest {
        val deletions = MutableStateFlow(PlaylistDeletions.State())
        val viewModel = home(repository(), deletions)
        advanceUntilIdle()
        val before = viewModel.uiState.value

        val elsewhere = PlaylistDeletions.Key("someone-else", deleted.id)
        deletions.value = PlaylistDeletions.State(done = listOf(PlaylistDeletions.Done(1, elsewhere)))
        advanceUntilIdle()

        assertSame(before, viewModel.uiState.value)
    }

    @Test
    fun should_matchStoredMediaIdForm_when_droppingActivityRows() {
        val content = HomeUiState.Content(
            activities = listOf(playlistPlayed(deleted, "p", entityId = deleted.id.toString())),
        )

        val next = content.withoutPlaylist(deleted.id)

        assertEquals(emptyList<ActivityEvent>(), next.activities)
        assertSame(next, next.withoutPlaylist(deleted.id))
    }

    private fun home(repository: YoinRepository, deletions: MutableStateFlow<PlaylistDeletions.State>) = HomeViewModel(
        repository = repository,
        activeProfileId = MutableStateFlow(profile),
        homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true).also { store ->
            every { store.layoutFlow(any()) } returns flowOf(null)
        },
        playlistDeletions = deletions,
    )

    private fun repository(): YoinRepository = repository(
        activities = listOf(
            playlistPlayed(deleted, profile, entityId = deleted.id.rawId),
            playlistPlayed(other, profile, entityId = other.id.rawId),
        ),
    )

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
        coEvery { repository.getPlaylists() } returns listOf(deleted, other)
        coEvery {
            repository.replaceHomeGridPools(albums = any(), tracks = any(), playlists = any())
        } just runs
        return repository
    }
}
