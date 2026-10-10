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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Rule
import org.junit.Test

/**
 * Now Playing's heart (P4, D4): it reads the repository's one favorite state,
 * and tells the user's own taps (the heart beats) from every other change —
 * Spotify confirming a like late, the next track — which flip quietly. It
 * never asks Spotify itself: PlaybackManager does, once for every host.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelFavoriteTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val first = track("a")
    private val second = track("b")
    private val playback = MutableStateFlow(playing(first))
    private val hearts = mapOf(
        first.id to MutableStateFlow(FavoriteState(false)),
        second.id to MutableStateFlow(FavoriteState(true))
    )
    private val repository = mockk<YoinRepository>(relaxed = true)

    @Test
    fun should_flipQuietly_when_spotifyConfirmsALikeLate() = runTest {
        val viewModel = viewModel()
        assertEquals(false to 0, heart(viewModel))

        hearts.getValue(first.id).value = FavoriteState(isStarred = true, fromUser = false)
        runCurrent()

        assertEquals(true to 1, heart(viewModel))
    }

    @Test
    fun should_keepTheSameGlyph_when_theUserTaps() = runTest {
        val viewModel = viewModel()

        hearts.getValue(first.id).value = FavoriteState(isStarred = true, fromUser = true)
        runCurrent()
        assertEquals(true to 0, heart(viewModel))

        // The write landed: the same heart, still the user's.
        hearts.getValue(first.id).value = FavoriteState(isStarred = true, fromUser = true)
        runCurrent()
        hearts.getValue(first.id).value = FavoriteState(isStarred = false, fromUser = true)
        runCurrent()
        assertEquals(false to 0, heart(viewModel))
    }

    @Test
    fun should_flipQuietly_when_theNextTrackIsLiked() = runTest {
        val viewModel = viewModel()

        playback.value = playing(second)
        runCurrent()

        assertEquals(true to 1, heart(viewModel))
    }

    @Test
    fun should_neverAskSpotify_when_theTrackChanges() = runTest {
        viewModel()

        playback.value = playing(second)
        runCurrent()

        coVerify(exactly = 0) { repository.refreshFavoriteStates(any(), any()) }
    }

    private fun TestScope.viewModel(): NowPlayingViewModel {
        val manager = mockk<PlaybackManager>(relaxed = true)
        every { manager.playbackState } returns playback
        every { manager.currentActivityContext } returns MutableStateFlow<ActivityContext>(ActivityContext.None)
        every { repository.currentProfileIdFlow } returns MutableStateFlow<String?>("profile")
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.getRating(any()) } returns flowOf(null)
        every { repository.observeFavoriteState(any()) } answers { hearts.getValue(firstArg<Track>().id) }
        every { repository.observeLibraryMembership(any()) } returns flowOf(LibraryMembership.Unknown)
        every { repository.resolveCoverUrl(any()) } returns null
        val viewModel = NowPlayingViewModel(manager, repository, mockk<CastManager>(relaxed = true))
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        return viewModel
    }

    private fun heart(viewModel: NowPlayingViewModel): Pair<Boolean, Int> {
        val state = viewModel.uiState.value as NowPlayingUiState.Playing
        return state.isStarred to state.favoriteQuietFlips
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
        id = MediaId.spotify(rawId),
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
