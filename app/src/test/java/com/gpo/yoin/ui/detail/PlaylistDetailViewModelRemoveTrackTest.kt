package com.gpo.yoin.ui.detail

import app.cash.turbine.test
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

/** "Remove from playlist" on the playlist page: the row leaves at once, and comes back if the service refuses. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelRemoveTrackTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<YoinRepository>(relaxed = true)

    /** The playlist as the fake service holds it; a removal that succeeds takes its position out. */
    private var server = playlist("t1", "t2", "t3")
    private val removals = mutableListOf<Pair<List<PlaylistItemRef>, String?>>()
    private var mutations = 0

    private fun viewModel(
        capabilities: Set<Capability> = setOf(Capability.PLAYLISTS_WRITE),
        remove: suspend (List<PlaylistItemRef>) -> Result<String?> = { items ->
            server = server.copy(
                tracks = server.tracks.filterIndexed { index, _ -> items.none { it.position == index } },
                songCount = server.tracks.size - items.size,
                durationSec = (server.tracks.size - items.size) * 200,
                snapshotId = "snap-${removals.size + 1}",
            )
            Result.success(server.snapshotId)
        },
    ): PlaylistDetailViewModel {
        coEvery { repository.awaitActiveSource(any()) } returns mockk<MusicSource>()
        coEvery { repository.getPlaylist(any()) } answers { server }
        every { repository.observeTracksWithNotes(any()) } returns flowOf(emptySet())
        every { repository.currentCapabilities() } returns capabilities
        coEvery { repository.removeTracksFromPlaylist(any(), any(), any()) } coAnswers {
            val items = secondArg<List<PlaylistItemRef>>()
            removals += items to thirdArg<String?>()
            remove(items)
        }
        return PlaylistDetailViewModel(
            playlistId = "subsonic:pl-1",
            repository = repository,
            onPlaylistMutated = { mutations++ },
        )
    }

    @Test
    fun should_takeTheRowOffAtOnceAndKeepItOff_when_removalSucceeds() = runTest {
        val viewModel = viewModel()
        val messages = collectMessages(viewModel)

        viewModel.uiState.test {
            assertEquals(PlaylistDetailUiState.Loading, awaitItem())
            val loaded = awaitItem() as PlaylistDetailUiState.Content
            assertTrue(loaded.canRemoveSongs)
            assertEquals(listOf("subsonic:t1", "subsonic:t2", "subsonic:t3"), loaded.songs.map(PlaylistSong::id))

            viewModel.removeTrack(loaded.songs[1].rowKey)

            // Before the service has answered: the row is gone, the count and length follow.
            val optimistic = awaitItem() as PlaylistDetailUiState.Content
            assertEquals(listOf("subsonic:t1", "subsonic:t3"), optimistic.songs.map(PlaylistSong::id))
            assertEquals(2, optimistic.songCount)
            assertEquals(400, optimistic.totalDuration)
            assertTrue(removals.isEmpty())

            settle()
            // The playlist read again says the same: nothing more to show.
            expectNoEvents()
        }

        // The one entry, at its position, against the snapshot the page read.
        assertEquals(
            listOf(listOf(PlaylistItemRef(trackId = MediaId.subsonic("t2"), position = 1)) to "snap-0"),
            removals,
        )
        val settled = viewModel.uiState.value as PlaylistDetailUiState.Content
        assertEquals(listOf("subsonic:t1", "subsonic:t3"), settled.songs.map(PlaylistSong::id))
        assertEquals(listOf("subsonic:t1", "subsonic:t3"), viewModel.getPlaylistSongs().map { it.id.toString() })
        assertEquals(1, mutations)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun should_keepTheOtherRowsKeys_when_playlistIsReadAgainAfterRemoval() = runTest {
        val viewModel = viewModel()
        settle()
        val before = (viewModel.uiState.value as PlaylistDetailUiState.Content).songs

        viewModel.removeTrack(before[0].rowKey)
        settle()

        // The rows that stay are the same rows to the list (they move up, they don't fade in).
        val after = (viewModel.uiState.value as PlaylistDetailUiState.Content).songs
        assertEquals(before.drop(1).map(PlaylistSong::rowKey), after.map(PlaylistSong::rowKey))
    }

    @Test
    fun should_putTheRowBackWhereItWas_when_removalFails() = runTest {
        val viewModel = viewModel(remove = { Result.failure(IOException()) })
        val messages = collectMessages(viewModel)
        settle()
        val loaded = viewModel.uiState.value as PlaylistDetailUiState.Content

        viewModel.uiState.test {
            assertEquals(loaded, awaitItem())
            viewModel.removeTrack(loaded.songs[1].rowKey)
            assertEquals(
                listOf("subsonic:t1", "subsonic:t3"),
                (awaitItem() as PlaylistDetailUiState.Content).songs.map(PlaylistSong::id),
            )

            // Refused: the same row, same key, same place, and the count it had.
            assertEquals(loaded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        settle()

        assertEquals(listOf<UiText>(UiText.Res(R.string.detail_playlist_error_remove)), messages)
        assertEquals(
            listOf("subsonic:t1", "subsonic:t2", "subsonic:t3"),
            viewModel.getPlaylistSongs().map { it.id.toString() },
        )
        assertEquals(0, mutations)
    }

    @Test
    fun should_sayWhatTheServiceSaid_when_removalFailsWithAMessage() = runTest {
        val viewModel = viewModel(remove = { Result.failure(IOException("Playlist is read-only")) })
        val messages = collectMessages(viewModel)
        settle()

        viewModel.removeTrack((viewModel.uiState.value as PlaylistDetailUiState.Content).songs[0].rowKey)
        settle()

        // As the page's rename and delete do.
        assertEquals(listOf<UiText>(UiText.Raw("Playlist is read-only")), messages)
    }

    @Test
    fun should_notOfferOrSendRemoval_when_serviceDoesNotWritePlaylists() = runTest {
        val viewModel = viewModel(capabilities = setOf(Capability.FAVORITES))
        settle()
        val loaded = viewModel.uiState.value as PlaylistDetailUiState.Content
        assertTrue(loaded.canWrite)
        assertFalse(loaded.canRemoveSongs)

        viewModel.removeTrack(loaded.songs[0].rowKey)
        settle()

        assertEquals(loaded, viewModel.uiState.value)
        coVerify(exactly = 0) { repository.removeTracksFromPlaylist(any(), any(), any()) }
    }

    @Test
    fun should_notOfferOrSendRemoval_when_playlistIsNotTheAccountsToEdit() = runTest {
        server = server.copy(canWrite = false)
        val viewModel = viewModel()
        settle()
        val loaded = viewModel.uiState.value as PlaylistDetailUiState.Content
        assertFalse(loaded.canRemoveSongs)

        viewModel.removeTrack(loaded.songs[0].rowKey)
        settle()

        assertEquals(loaded, viewModel.uiState.value)
        coVerify(exactly = 0) { repository.removeTracksFromPlaylist(any(), any(), any()) }
    }

    @Test
    fun should_removeOnlyTheRowTapped_when_songIsInThePlaylistTwice() = runTest {
        server = playlist("t1", "t2", "t1")
        val viewModel = viewModel()
        settle()
        val before = (viewModel.uiState.value as PlaylistDetailUiState.Content).songs
        assertEquals(3, before.map(PlaylistSong::rowKey).toSet().size)

        viewModel.removeTrack(before[2].rowKey)
        settle()

        assertEquals(listOf(PlaylistItemRef(trackId = MediaId.subsonic("t1"), position = 2)), removals.single().first)
        // The first entry of the song is still its own row.
        val after = (viewModel.uiState.value as PlaylistDetailUiState.Content).songs
        assertEquals(before.take(2), after)
    }

    @Test
    fun should_sendEachPositionAsThePlaylistThenStands_when_twoRowsAreRemovedOneAfterAnother() = runTest {
        val gate = CompletableDeferred<Unit>()
        val viewModel = viewModel(
            remove = { items ->
                gate.await()
                server = server.copy(
                    tracks = server.tracks.filterIndexed { index, _ -> items.none { it.position == index } },
                    snapshotId = "snap-${removals.size}",
                )
                Result.success(server.snapshotId)
            },
        )
        settle()
        val loaded = (viewModel.uiState.value as PlaylistDetailUiState.Content).songs

        // The second is asked for while the first is still out.
        viewModel.removeTrack(loaded[0].rowKey)
        viewModel.removeTrack(loaded[2].rowKey)
        settle()
        assertEquals(
            listOf("subsonic:t2"),
            (viewModel.uiState.value as PlaylistDetailUiState.Content).songs.map(PlaylistSong::id),
        )
        assertEquals(1, removals.size)

        gate.complete(Unit)
        settle()

        // t3 was third on the page; it is second once t1 has gone, and goes against the new snapshot.
        assertEquals(
            listOf(
                listOf(PlaylistItemRef(trackId = MediaId.subsonic("t1"), position = 0)) to "snap-0",
                listOf(PlaylistItemRef(trackId = MediaId.subsonic("t3"), position = 1)) to "snap-1",
            ),
            removals,
        )
        assertEquals(
            listOf("subsonic:t2"),
            (viewModel.uiState.value as PlaylistDetailUiState.Content).songs.map(PlaylistSong::id),
        )
    }

    private fun TestScope.collectMessages(viewModel: PlaylistDetailViewModel): List<UiText> {
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { viewModel.messages.collect { messages += it } }
        return messages
    }

    /** Runs all that is queued; the message collector too, a background job advanceUntilIdle leaves waiting. */
    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    private fun playlist(vararg songs: String) = Playlist(
        id = MediaId.subsonic("pl-1"),
        name = "Playlist",
        owner = "51",
        coverArt = null,
        songCount = songs.size,
        durationSec = songs.size * 200,
        tracks = songs.map(::track),
        canWrite = true,
        snapshotId = "snap-0",
    )

    private fun track(rawId: String): Track = Track(
        id = MediaId.subsonic(rawId),
        title = "Song $rawId",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = MediaId.subsonic("al-1"),
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}
