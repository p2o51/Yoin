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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
 * press, handed to the repository as it is made. Nothing in this window's
 * scope stands between a press and its store, so a provider call still out
 * never holds a newer rating back and closing the window drops none
 * (ordering and the provider push: YoinRepositoryTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelRatingTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val song = track("tr-1116")
    private val repository = mockk<YoinRepository>(relaxed = true)

    /** Each rating handed to the repository, in order. */
    private val writes = mutableListOf<Float>()

    @Test
    fun should_handEachPressToTheRepositoryAtOnce_when_theFirstWriteIsStillOut() = runTest {
        // The first write's job never completes: its provider call is still out.
        every { repository.setRating(song.id, any()) } answers {
            writes += secondArg<Float>()
            Job()
        }
        val viewModel = viewModel()

        viewModel.setRating(3.0f)
        viewModel.setRating(7.7f)

        // No runCurrent: neither press waited on this window's scope or on the first write.
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
