package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.CastManager
import com.gpo.yoin.player.ConnectionPhase
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Wave-2 gate fix: `skipDirection` (the cover's ride-in direction) used to stay
 * at −1 after one skip-previous, so every later auto-advance rode in backwards.
 * −1 now belongs to the one change a PREVIOUS tap causes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelSkipDirectionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private fun track(id: String) = Track(
        id = MediaId(MediaId.PROVIDER_SUBSONIC, id),
        title = "Song $id", artist = "Artist", artistId = null,
        album = null, albumId = null, coverArt = null,
        durationSec = 240, trackNumber = null, year = null,
        genre = null, userRating = null,
    )

    private val a = track("a")
    private val b = track("b")
    private val c = track("c")
    private val queue = listOf(a, b, c)

    private fun playing(current: Track) = PlaybackState(
        currentTrack = current,
        isPlaying = true,
        duration = 240_000L,
        queue = queue,
        currentIndex = queue.indexOf(current),
        nextTrack = queue.getOrNull(queue.indexOf(current) + 1),
        connectionPhase = ConnectionPhase.Ready,
    )

    private class Fixture(
        val playback: MutableStateFlow<PlaybackState>,
        val manager: PlaybackManager,
        val viewModel: NowPlayingViewModel,
    )

    private fun TestScope.fixture(initial: PlaybackState): Fixture {
        val playbackState = MutableStateFlow(initial)
        val playback = mockk<PlaybackManager>(relaxed = true)
        every { playback.playbackState } returns playbackState
        every { playback.currentActivityContext } returns MutableStateFlow<ActivityContext>(ActivityContext.None)
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProfileIdFlow } returns MutableStateFlow<String?>("profile")
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.getRating(any()) } returns flowOf(null)
        every { repository.observeSpotifyFavorite(any()) } returns flowOf(null)
        every { repository.observeLibraryMembership(any()) } returns flowOf(LibraryMembership.Unknown)
        every { repository.resolveCoverUrl(any()) } returns null
        val viewModel = NowPlayingViewModel(playback, repository, mockk<CastManager>(relaxed = true))
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        return Fixture(playbackState, playback, viewModel)
    }

    @Test
    fun should_rideBackOnceThenForward_when_previousChangesTheSong() = runTest {
        val f = fixture(playing(b))

        f.viewModel.skipPrevious()
        runCurrent()
        assertEquals(-1, f.viewModel.skipDirection.value)
        verify(exactly = 1) { f.manager.skipPrevious() }

        // The previous song lands: the new cover composes with −1.
        f.playback.value = playing(a)
        runCurrent()
        advanceTimeBy(SKIP_DIRECTION_HOLD_MS - 1)
        runCurrent()
        assertEquals(-1, f.viewModel.skipDirection.value)

        // Taken: back to forward…
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, f.viewModel.skipDirection.value)

        // …so the auto-advance later on rides in forward.
        advanceTimeBy(10_000L)
        f.playback.value = playing(b)
        runCurrent()
        assertEquals(1, f.viewModel.skipDirection.value)
    }

    @Test
    fun should_returnForward_when_previousOnlyRestartsTheSong() = runTest {
        val f = fixture(playing(b))

        // The player restarts b: same song, so no change ever comes.
        f.viewModel.skipPrevious()
        advanceTimeBy(TransportTapValidityMs - 1)
        runCurrent()
        assertEquals(-1, f.viewModel.skipDirection.value)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, f.viewModel.skipDirection.value)

        // The song plays out and auto-advances: forward.
        f.playback.value = playing(c)
        runCurrent()
        assertEquals(1, f.viewModel.skipDirection.value)
    }

    @Test
    fun should_rideForwardAtOnce_when_nextFollowsPrevious() = runTest {
        val f = fixture(playing(b))

        f.viewModel.skipPrevious()
        f.viewModel.skipNext()
        runCurrent()
        assertEquals(1, f.viewModel.skipDirection.value)

        // The cancelled reset never flips anything later.
        f.playback.value = playing(c)
        advanceTimeBy(TransportTapValidityMs + SKIP_DIRECTION_HOLD_MS + 1)
        runCurrent()
        assertEquals(1, f.viewModel.skipDirection.value)
    }

    @Test
    fun should_restartTheHold_when_previousIsTappedAgain() = runTest {
        val f = fixture(playing(c))

        f.viewModel.skipPrevious()
        f.playback.value = playing(b)
        runCurrent()
        advanceTimeBy(SKIP_DIRECTION_HOLD_MS / 2)
        // Second PREVIOUS from b, before the first hold ends.
        f.viewModel.skipPrevious()
        f.playback.value = playing(a)
        runCurrent()
        advanceTimeBy(SKIP_DIRECTION_HOLD_MS - 1)
        runCurrent()
        assertEquals(-1, f.viewModel.skipDirection.value)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, f.viewModel.skipDirection.value)
    }
}
