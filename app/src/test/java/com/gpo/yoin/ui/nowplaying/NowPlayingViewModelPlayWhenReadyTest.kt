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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 2026-10-05 device QA (问题 5): a seek (lyric line, note row, wave bar) makes
 * Media3 buffer — isPlaying dips false for a few hundred ms and the PLAY/PAUSE
 * label flashed PLAY. Now Playing reads playWhenReady: the player still means
 * to play through the dip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelPlayWhenReadyTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val song = Track(
        id = MediaId(MediaId.PROVIDER_SUBSONIC, "nq-t1"),
        title = "Harbour Lights", artist = "Night QA Band", artistId = null,
        album = null, albumId = null, coverArt = null,
        durationSec = 60, trackNumber = null, year = null,
        genre = null, userRating = null,
    )

    private val playing = PlaybackState(
        currentTrack = song,
        isPlaying = true,
        duration = 60_000L,
        queue = listOf(song),
        currentIndex = 0,
        connectionPhase = ConnectionPhase.Ready,
    )

    /** What Media3 reports right after a seek: buffering, not playing, still meaning to. */
    private val bufferingSeek = playing.copy(
        isPlaying = false,
        playWhenReady = true,
        connectionPhase = ConnectionPhase.Connecting,
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
    fun should_keepShowingPause_when_aSeekBuffers() = runTest {
        val f = fixture(playing)

        f.playback.value = bufferingSeek
        runCurrent()

        val state = f.viewModel.uiState.value as NowPlayingUiState.Playing
        assertTrue("no PLAY flash through the dip", state.isPlaying)
        assertTrue(f.viewModel.isPlayingLive.value)
    }

    @Test
    fun should_pause_when_pauseIsTappedDuringABufferingDip() = runTest {
        val f = fixture(bufferingSeek)

        f.viewModel.togglePlayPause()

        verify(exactly = 1) { f.manager.pause() }
        verify(exactly = 0) { f.manager.resume() }
    }

    @Test
    fun should_showPlay_when_thePlayerReallyPaused() = runTest {
        val f = fixture(playing)

        f.playback.value = playing.copy(isPlaying = false, playWhenReady = false)
        runCurrent()

        val state = f.viewModel.uiState.value as NowPlayingUiState.Playing
        assertFalse(state.isPlaying)
        assertEquals(false, f.viewModel.isPlayingLive.value)
        f.viewModel.togglePlayPause()
        verify(exactly = 1) { f.manager.resume() }
    }
}
