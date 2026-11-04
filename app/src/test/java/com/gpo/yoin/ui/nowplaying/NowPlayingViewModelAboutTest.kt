package com.gpo.yoin.ui.nowplaying

import app.cash.turbine.test
import com.gpo.yoin.R
import com.gpo.yoin.data.album.AlbumScrapbookAboutRef
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.FavoriteState
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.CastManager
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Now Playing's About long-press: Gemini writes a row again, Undo puts the old text back. */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelAboutTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val song = Track(
        id = MediaId(MediaId.PROVIDER_SUBSONIC, "np-a1"),
        title = "Harbour Lights", artist = "Night QA Band", artistId = null,
        album = "Tide", albumId = null, coverArt = null,
        durationSec = 60, trackNumber = null, year = null,
        genre = null, userRating = null,
    )

    private fun entry(kind: String, key: String) = SongAboutEntry(
        titleKey = "harbour lights",
        artistKey = "night qa band",
        albumKey = "tide",
        titleDisplay = "Harbour Lights",
        artistDisplay = "Night QA Band",
        albumDisplay = "Tide",
        kind = kind,
        entryKey = key,
        promptText = if (kind == SongAboutEntry.KIND_CANONICAL) null else "Who sampled it?",
        titleText = "Samples",
        answerText = "Nobody yet.",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun ref(kind: String, key: String) =
        AlbumScrapbookAboutRef("harbour lights", "night qa band", "tide", kind, key)

    private fun viewModel(): Pair<NowPlayingViewModel, YoinRepository> {
        val playback = mockk<PlaybackManager>(relaxed = true)
        val state = PlaybackState(currentTrack = song, queue = listOf(song))
        every { playback.playbackState } returns MutableStateFlow(state)
        every { playback.currentActivityContext } returns MutableStateFlow<ActivityContext>(ActivityContext.None)
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProfileId() } returns "p"
        every { repository.currentProfileIdFlow } returns MutableStateFlow<String?>("p")
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.getRating(any()) } returns flowOf(null)
        every { repository.observeFavoriteState(any()) } answers { flowOf(FavoriteState(firstArg<Track>().isStarred)) }
        every { repository.observeLibraryMembership(any()) } returns flowOf(LibraryMembership.NotAdded)
        return NowPlayingViewModel(playback, repository, mockk<CastManager>(relaxed = true)) to repository
    }

    @Test
    fun should_showTheRowAsThinking_andOfferUndo_when_regenerated() = runTest {
        val (viewModel, repository) = viewModel()
        val askRef = ref(SongAboutEntry.KIND_ASK, "who sampled it")
        val answer = CompletableDeferred<YoinRepository.AboutRegenerateResult>()
        coEvery { repository.regenerateAboutEntry(askRef) } coAnswers { answer.await() }
        runCurrent()

        viewModel.aboutRegenerated.test {
            viewModel.regenerateAbout(entry(SongAboutEntry.KIND_ASK, "who sampled it"))
            runCurrent()
            assertEquals(setOf(askRef), viewModel.aboutRegenerating.value)

            answer.complete(YoinRepository.AboutRegenerateResult.Success("Samples", "Nobody yet."))
            runCurrent()

            assertEquals(AboutRegenerated(askRef, "Samples", "Nobody yet."), awaitItem())
            assertEquals(emptySet<AlbumScrapbookAboutRef>(), viewModel.aboutRegenerating.value)
        }
    }

    @Test
    fun should_putTheOldTextBack_when_undone() = runTest {
        val (viewModel, repository) = viewModel()
        val factRef = ref(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_PRODUCER)

        viewModel.undoAboutRegenerate(AboutRegenerated(factRef, null, "Someone"))
        runCurrent()

        coVerify(exactly = 1) { repository.editAboutEntry(factRef, null, "Someone") }
    }

    @Test
    fun should_leaveTheListenersOwnEntryAlone() = runTest {
        val (viewModel, repository) = viewModel()
        runCurrent()

        viewModel.regenerateAbout(entry(SongAboutEntry.KIND_OWN, "first listen"))
        runCurrent()

        coVerify(exactly = 0) { repository.regenerateAboutEntry(any()) }
    }

    @Test
    fun should_sayWhy_when_theKeyIsMissing() = runTest {
        val (viewModel, repository) = viewModel()
        coEvery { repository.regenerateAboutEntry(any()) } returns YoinRepository.AboutRegenerateResult.ApiKeyMissing
        runCurrent()

        viewModel.addToPlaylistMessages.test {
            viewModel.regenerateAbout(entry(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_PRODUCER))
            runCurrent()

            assertEquals(UiText.Res(R.string.np_ask_api_key_missing), awaitItem())
        }
    }
}
