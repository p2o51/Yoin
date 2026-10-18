package com.gpo.yoin.ui.detail

import com.gpo.yoin.R
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.transfer.MatchDecision
import com.gpo.yoin.data.transfer.MatchMethod
import com.gpo.yoin.data.transfer.PlaylistCopyService
import com.gpo.yoin.data.transfer.PlaylistCopyTarget
import com.gpo.yoin.data.transfer.TrackResolution
import com.gpo.yoin.data.transfer.TransferReport
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.common.UiText
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelCopyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<YoinRepository>(relaxed = true)
    private val target = PlaylistCopyTarget("p-apple", "Apple Music", MediaId.PROVIDER_APPLE_MUSIC)

    @Before
    fun setUp() {
        coEvery { repository.getPlaylist(any()) } returns Playlist(
            id = MediaId.spotify("pl-1"),
            name = "Mix",
            owner = null,
            coverArt = null,
            songCount = 2,
            durationSec = 0,
            tracks = listOf(track("a"), track("b")),
        )
        every { repository.observeTracksWithNotes(any()) } returns flowOf(emptySet())
    }

    @Test
    fun should_showProgressThenOutcome_when_copyRuns() = runTest {
        val release = CompletableDeferred<Unit>()
        val viewModel = viewModel { _, _, onProgress ->
            onProgress(1, 2)
            release.await()
            report(matched = listOf("a"), missing = listOf("b"))
        }
        advanceUntilIdle()

        viewModel.openCopy()
        assertEquals(PlaylistCopyState.Choosing, viewModel.copyState.value)
        viewModel.copyTo(target)
        advanceUntilIdle()
        assertEquals(PlaylistCopyState.Copying("Apple Music", done = 1, total = 2), viewModel.copyState.value)

        release.complete(Unit)
        advanceUntilIdle()
        val copied = viewModel.copyState.value as PlaylistCopyState.Copied
        assertEquals(1, copied.added)
        assertEquals(2, copied.total)
        assertEquals(listOf("Song b"), copied.missing.map { it.title })
    }

    @Test
    fun should_reportOnSnackbar_when_sheetClosedWhileCopying() = runTest {
        val release = CompletableDeferred<Unit>()
        val viewModel = viewModel { _, _, _ ->
            release.await()
            report(matched = listOf("a", "b"), missing = emptyList())
        }
        advanceUntilIdle()

        viewModel.openCopy()
        viewModel.copyTo(target)
        advanceUntilIdle()
        viewModel.dismissCopy()
        val message = async { viewModel.messages.first() }
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(PlaylistCopyState.Closed, viewModel.copyState.value)
        assertEquals(
            UiText.Plural(R.plurals.detail_playlist_copy_done, 2, listOf(2, 2, "Apple Music")),
            message.await(),
        )
    }

    @Test
    fun should_showFailure_when_copyThrows() = runTest {
        val viewModel = viewModel { _, _, _ -> throw IOException("offline") }
        advanceUntilIdle()

        viewModel.openCopy()
        viewModel.copyTo(target)
        advanceUntilIdle()

        assertEquals(PlaylistCopyState.Failed("Apple Music"), viewModel.copyState.value)
    }

    @Test
    fun should_offerOtherImportingProfiles_when_listingTargets() {
        val profiles = listOf(
            Profile("p-spotify", MediaId.PROVIDER_SPOTIFY, "Spotify", ""),
            Profile("p-apple", MediaId.PROVIDER_APPLE_MUSIC, "Apple Music", ""),
            Profile("p-nas", MediaId.PROVIDER_SUBSONIC, "NAS", ""),
            Profile("p-odd", "unknown", "Odd", ""),
        )

        val targets = PlaylistCopyService.copyTargets(profiles, activeId = "p-spotify")

        assertEquals(listOf("p-apple", "p-nas"), targets.map { it.profileId })
    }

    private fun viewModel(
        copy: suspend (MediaId, String, (Int, Int) -> Unit) -> TransferReport,
    ) = PlaylistDetailViewModel(
        playlistId = "spotify:pl-1",
        repository = repository,
        copyTargets = flowOf(listOf(target)),
        copyPlaylist = copy,
    )

    private fun report(matched: List<String>, missing: List<String>): TransferReport {
        val resolutions = (matched.map { it to true } + missing.map { it to false }).mapIndexed { index, (id, found) ->
            TrackResolution(
                position = index,
                source = track(id),
                decision = if (found) MatchDecision.Matched(track(id), MatchMethod.Isrc) else MatchDecision.NotFound,
            )
        }
        return TransferReport(
            playlistName = "Mix",
            created = Playlist(MediaId("applemusic", "library:p.new"), "Mix", null, null, matched.size, null),
            resolutions = resolutions,
            added = matched.size,
        )
    }

    private fun track(rawId: String) = Track(
        id = MediaId.spotify(rawId),
        title = "Song $rawId",
        artist = "Artist",
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}
