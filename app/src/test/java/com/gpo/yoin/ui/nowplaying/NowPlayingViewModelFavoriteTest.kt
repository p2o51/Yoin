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
 * and only Spotify's answer coming in late and flipping it is a quiet flip;
 * a tap, a failed write rolling back, a library sync and the next track
 * animate the heart as ever (a like beats). It never asks Spotify itself:
 * PlaybackManager does, once for every host.
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

        hearts.getValue(first.id).value = answer(true, atMs = 1L)
        runCurrent()

        assertEquals(true to 1, heart(viewModel))
    }

    @Test
    fun should_flipQuietly_when_spotifySaysItWasUnlikedAfterTheUsersLike() = runTest {
        val viewModel = viewModel()
        hearts.getValue(first.id).value = FavoriteState(isStarred = true, fromUser = true)
        runCurrent()

        // Past the write's grace, a newer answer: unliked in Spotify.
        hearts.getValue(first.id).value = answer(false, atMs = 2L)
        runCurrent()

        assertEquals(false to 1, heart(viewModel))
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
    fun should_animateTheRollback_when_aLikeWriteFails() = runTest {
        // Spotify answered "not liked" before; the user likes it; the write is refused.
        hearts.getValue(first.id).value = answer(false, atMs = 1L)
        val viewModel = viewModel()
        hearts.getValue(first.id).value = FavoriteState(isStarred = true, fromUser = true, answeredAtMs = 1L)
        runCurrent()
        assertEquals(true to 0, heart(viewModel))

        // Back to the answer it had: the rollback, not a late answer.
        hearts.getValue(first.id).value = answer(false, atMs = 1L)
        runCurrent()

        assertEquals(false to 0, heart(viewModel))
    }

    @Test
    fun should_animate_when_aLibrarySyncFlipsTheHeart() = runTest {
        val viewModel = viewModel()

        // The saved-tracks mirror, not an answer to a check.
        hearts.getValue(first.id).value = FavoriteState(isStarred = true)
        runCurrent()

        assertEquals(true to 0, heart(viewModel))
    }

    @Test
    fun should_beatIn_when_theNextTrackIsLiked() = runTest {
        val viewModel = viewModel()

        playback.value = playing(second)
        runCurrent()

        assertEquals(true to 0, heart(viewModel))
    }

    @Test
    fun should_beatIn_when_theNextTracksLikeIsAnEarlierAnswer() = runTest {
        // Spotify said so on an earlier visit: the track changing is what flips the heart.
        hearts.getValue(second.id).value = answer(true, atMs = 5L)
        val viewModel = viewModel()

        playback.value = playing(second)
        runCurrent()
        assertEquals(true to 0, heart(viewModel))

        // A newer answer about it, coming in late: quiet.
        hearts.getValue(second.id).value = answer(false, atMs = 6L)
        runCurrent()
        assertEquals(false to 1, heart(viewModel))
    }

    @Test
    fun should_beatIn_when_skippingBackToATrackJustLiked() = runTest {
        val viewModel = viewModel()
        hearts.getValue(first.id).value = FavoriteState(isStarred = true, fromUser = true)
        runCurrent()
        assertEquals(true to 0, heart(viewModel))
        hearts.getValue(second.id).value = FavoriteState(isStarred = false)

        // On to an unliked track and back, inside the like's grace.
        playback.value = playing(second)
        runCurrent()
        assertEquals(false to 0, heart(viewModel))
        playback.value = playing(first)
        runCurrent()

        assertEquals(true to 0, heart(viewModel))
    }

    @Test
    fun should_neverAskSpotify_when_theTrackChanges() = runTest {
        viewModel()

        playback.value = playing(second)
        runCurrent()

        coVerify(exactly = 0) { repository.refreshFavoriteStates(any(), any()) }
    }

    /** Spotify's answer to a check, which came in at [atMs]. */
    private fun answer(isStarred: Boolean, atMs: Long) =
        FavoriteState(isStarred = isStarred, fromAnswer = true, answeredAtMs = atMs)

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
