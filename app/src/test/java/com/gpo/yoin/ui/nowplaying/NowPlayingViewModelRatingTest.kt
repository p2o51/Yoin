package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.FavoriteState
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.CastManager
import com.gpo.yoin.player.ConnectionPhase
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Now Playing's rating writes ([NowPlayingViewModel.setRating]): one per
 * press, and they land in the order they were made. A second press while
 * the first one's provider call is out waits for it instead of racing it,
 * so the provider and the stored rating end on the newest value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelRatingTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val song = track("tr-1116")
    private val repository = mockk<YoinRepository>(relaxed = true)

    /** Each rating write as it starts, and the gate that holds the first one at the provider. */
    private val writes = mutableListOf<Float>()
    private val firstAnswer = CompletableDeferred<Unit>()

    @Test
    fun should_writeInCommitOrder_when_aSecondPressLandsWhileTheFirstIsOut() = runTest {
        coEvery { repository.setRating(song.id, any()) } coAnswers {
            writes += secondArg<Float>()
            if (writes.size == 1) firstAnswer.await()
        }
        val viewModel = viewModel()

        viewModel.setRating(3.0f)
        runCurrent()
        viewModel.setRating(7.7f)
        runCurrent()
        // The newer press waits: nothing overtakes the write still out.
        assertEquals(listOf(3.0f), writes)

        firstAnswer.complete(Unit)
        runCurrent()

        assertEquals(listOf(3.0f, 7.7f), writes)
    }

    private fun TestScope.viewModel(): NowPlayingViewModel {
        val manager = mockk<PlaybackManager>(relaxed = true)
        every { manager.playbackState } returns MutableStateFlow(playing(song))
        every { manager.currentActivityContext } returns MutableStateFlow<ActivityContext>(ActivityContext.None)
        every { repository.currentProfileIdFlow } returns MutableStateFlow<String?>("profile")
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.getRating(any()) } returns flowOf(null)
        every { repository.observeFavoriteState(any()) } returns MutableStateFlow(FavoriteState(false))
        every { repository.observeLibraryMembership(any()) } returns flowOf(LibraryMembership.Unknown)
        every { repository.resolveCoverUrl(any()) } returns null
        val viewModel = NowPlayingViewModel(manager, repository, mockk<CastManager>(relaxed = true))
        runCurrent()
        return viewModel
    }

    private fun playing(track: Track) = PlaybackState(
        currentTrack = track,
        isPlaying = true,
        duration = 60_000L,
        queue = listOf(track),
        currentIndex = 0,
        connectionPhase = ConnectionPhase.Ready
    )

    private fun track(rawId: String) = Track(
        id = MediaId.subsonic(rawId),
        title = rawId,
        artist = "Artist",
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = 60,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )
}
