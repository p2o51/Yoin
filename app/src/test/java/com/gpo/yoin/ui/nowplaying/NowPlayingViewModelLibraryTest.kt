package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.FavoriteState
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.player.CastManager
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelLibraryTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun should_keepNewAccountsAdditionBusy_when_oldAccountsAdditionCompletes() = runTest {
        val track = Track(
            id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "123"),
            title = "Song", artist = "Artist", artistId = null,
            album = null, albumId = null, coverArt = null,
            durationSec = 180, trackNumber = null, year = null,
            genre = null, userRating = null,
        )
        val playback = mockk<PlaybackManager>(relaxed = true)
        every { playback.playbackState } returns MutableStateFlow(PlaybackState(currentTrack = track))
        every { playback.currentActivityContext } returns MutableStateFlow<ActivityContext>(ActivityContext.None)
        val profiles = MutableStateFlow<String?>("first")
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProfileId() } answers { profiles.value }
        every { repository.currentProfileIdFlow } returns profiles
        every { repository.currentCapabilities() } returns setOf(Capability.LIBRARY_ADD)
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.getRating(track.id) } returns flowOf(null)
        every { repository.observeFavoriteState(any()) } answers { flowOf(FavoriteState(firstArg<Track>().isStarred)) }
        every { repository.observeLibraryMembership(track.id) } returns flowOf(LibraryMembership.NotAdded)
        coEvery { repository.refreshLibraryMembership(track) } returns Result.success(LibraryMembership.NotAdded)
        coEvery { repository.getLoadedLyrics(track.id, track.title, track.artist) } returns null
        val first = CompletableDeferred<LibraryMembership>()
        val second = CompletableDeferred<LibraryMembership>()
        coEvery { repository.addToLibrary(track) } coAnswers {
            Result.success(if (profiles.value == "first") first.await() else second.await())
        }
        val viewModel = NowPlayingViewModel(playback, repository, mockk<CastManager>(relaxed = true))
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()

        viewModel.addCurrentToLibrary()
        runCurrent()
        assertTrue((viewModel.uiState.value as NowPlayingUiState.Playing).libraryActionInFlight)

        profiles.value = "second"
        runCurrent()
        assertFalse((viewModel.uiState.value as NowPlayingUiState.Playing).libraryActionInFlight)
        viewModel.addCurrentToLibrary()
        viewModel.addCurrentToLibrary()
        runCurrent()
        assertTrue((viewModel.uiState.value as NowPlayingUiState.Playing).libraryActionInFlight)
        coVerify(exactly = 2) { repository.addToLibrary(track) }

        first.complete(LibraryMembership.Pending)
        runCurrent()
        assertTrue((viewModel.uiState.value as NowPlayingUiState.Playing).libraryActionInFlight)

        second.complete(LibraryMembership.Pending)
        runCurrent()
        assertFalse((viewModel.uiState.value as NowPlayingUiState.Playing).libraryActionInFlight)
    }
}
