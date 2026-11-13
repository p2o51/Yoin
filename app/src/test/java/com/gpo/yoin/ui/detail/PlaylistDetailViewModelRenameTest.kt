package com.gpo.yoin.ui.detail

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Rename on the playlist page: the new name is on the page before the service
 * answers, and a refusal puts back the name the service still holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelRenameTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun should_showTheNewNameAtOnce_when_theServiceHasNotAnsweredYet() = runTest {
        val page = page()
        val answer = page.holdRename("Evening")

        page.viewModel.messages.test {
            page.viewModel.rename("  Evening  ")
            runCurrent()

            assertEquals("Evening", page.shownName())
            coVerify(exactly = 1) { page.repository.renamePlaylist(PLAYLIST_ID, "Evening", any()) }
            assertEquals(0, page.mutations)
            expectNoEvents()

            // The service takes it: the page says so and reads the playlist again.
            page.serverName = "Evening"
            answer.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(UiText.Res(R.string.detail_playlist_renamed), awaitItem())
            assertEquals("Evening", page.shownName())
            assertEquals(1, page.mutations)
            coVerify(exactly = 2) { page.repository.getPlaylist(PLAYLIST_ID) }
        }
    }

    @Test
    fun should_putTheOldNameBack_when_theServiceRefuses() = runTest {
        val page = page()
        val answer = page.holdRename("Evening")

        page.viewModel.messages.test {
            page.viewModel.rename("Evening")
            runCurrent()
            assertEquals("Evening", page.shownName())

            answer.complete(Result.failure(IllegalStateException("Not the owner")))
            advanceUntilIdle()

            assertEquals("Morning", page.shownName())
            // The service's own reason when it gives one, as before.
            assertEquals(UiText.Raw("Not the owner"), awaitItem())
            assertEquals(0, page.mutations)
            // Nothing was read again: the page is the one that was there.
            coVerify(exactly = 1) { page.repository.getPlaylist(PLAYLIST_ID) }
        }
    }

    @Test
    fun should_sayItCouldNotRename_when_theRefusalGivesNoReason() = runTest {
        val page = page()
        coEvery { page.repository.renamePlaylist(any(), any(), any()) } returns
            Result.failure(IllegalStateException())

        page.viewModel.messages.test {
            page.viewModel.rename("Evening")
            advanceUntilIdle()

            assertEquals("Morning", page.shownName())
            assertEquals(UiText.Res(R.string.detail_playlist_error_rename), awaitItem())
        }
    }

    @Test
    fun should_putTheOldNameBack_when_theWriteThrows() = runTest {
        val page = page()
        // No account in use: the repository throws instead of answering.
        coEvery { page.repository.renamePlaylist(any(), any(), any()) } throws
            IllegalStateException("No profile configured")

        page.viewModel.messages.test {
            page.viewModel.rename("Evening")
            advanceUntilIdle()

            assertEquals("Morning", page.shownName())
            assertEquals(UiText.Raw("No profile configured"), awaitItem())
        }
    }

    @Test
    fun should_sendNothing_when_theNameIsBlankOrUnchanged() = runTest {
        val page = page()

        page.viewModel.messages.test {
            page.viewModel.rename("   ")
            page.viewModel.rename(" Morning ")
            advanceUntilIdle()

            assertEquals("Morning", page.shownName())
            coVerify(exactly = 0) { page.repository.renamePlaylist(any(), any(), any()) }
            expectNoEvents()
        }
    }

    @Test
    fun should_keepTheLaterName_when_anEarlierRenameIsRefused() = runTest {
        val page = page()
        val first = page.holdRename("Evening")
        val second = page.holdRename("Night")

        page.viewModel.messages.test {
            page.viewModel.rename("Evening")
            page.viewModel.rename("Night")
            runCurrent()

            // One at a time, in the order they were made.
            assertEquals("Night", page.shownName())
            coVerify(exactly = 1) { page.repository.renamePlaylist(PLAYLIST_ID, "Evening", any()) }
            coVerify(exactly = 0) { page.repository.renamePlaylist(PLAYLIST_ID, "Night", any()) }

            first.complete(Result.failure(IllegalStateException()))
            runCurrent()

            assertEquals(UiText.Res(R.string.detail_playlist_error_rename), awaitItem())
            assertEquals("Night", page.shownName())
            coVerify(exactly = 1) { page.repository.renamePlaylist(PLAYLIST_ID, "Night", any()) }

            page.serverName = "Night"
            second.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(UiText.Res(R.string.detail_playlist_renamed), awaitItem())
            assertEquals("Night", page.shownName())
        }
    }

    @Test
    fun should_goBackToTheNameTheServiceHolds_when_twoRenamesInARowAreRefused() = runTest {
        val page = page()
        val first = page.holdRename("Evening")
        val second = page.holdRename("Night")

        page.viewModel.messages.test {
            page.viewModel.rename("Evening")
            page.viewModel.rename("Night")
            runCurrent()

            first.complete(Result.failure(IllegalStateException()))
            second.complete(Result.failure(IllegalStateException()))
            advanceUntilIdle()

            // Not "Evening": the service never took it.
            assertEquals("Morning", page.shownName())
            assertEquals(UiText.Res(R.string.detail_playlist_error_rename), awaitItem())
            assertEquals(UiText.Res(R.string.detail_playlist_error_rename), awaitItem())
        }
    }

    @Test
    fun should_goBackToTheEarlierRename_when_onlyTheLaterOneIsRefused() = runTest {
        val page = page()
        val first = page.holdRename("Evening")
        val second = page.holdRename("Night")

        page.viewModel.messages.test {
            page.viewModel.rename("Evening")
            page.viewModel.rename("Night")
            runCurrent()

            page.serverName = "Evening"
            first.complete(Result.success(Unit))
            second.complete(Result.failure(IllegalStateException()))
            advanceUntilIdle()

            assertEquals("Evening", page.shownName())
            assertEquals(UiText.Res(R.string.detail_playlist_renamed), awaitItem())
            assertEquals(UiText.Res(R.string.detail_playlist_error_rename), awaitItem())
        }
    }

    @Test
    fun should_finishTheWrite_when_thePageClosesUnderIt() = runTest {
        val page = page()
        val answer = page.holdRename("Evening")
        page.viewModel.rename("Evening")
        runCurrent()

        // The page is closed (its ViewModel cleared) before the service answers.
        page.viewModel.viewModelScope.cancel()
        answer.complete(Result.success(Unit))
        advanceUntilIdle()

        // The write was not dropped, and the Library hears of it.
        assertEquals(1, page.mutations)
    }

    @Test
    fun should_keepTheLaterName_when_theEarlierRenamesReadComesBackFirst() = runTest {
        val page = page()
        val first = page.holdRename("Evening")
        val second = page.holdRename("Night")

        page.viewModel.messages.test {
            page.viewModel.rename("Evening")
            page.viewModel.rename("Night")
            runCurrent()

            // The service takes "Evening" and the page reads it again while
            // "Night" is still out: the page keeps "Night".
            page.serverName = "Evening"
            first.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(UiText.Res(R.string.detail_playlist_renamed), awaitItem())
            assertEquals("Night", page.shownName())

            page.serverName = "Night"
            second.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(UiText.Res(R.string.detail_playlist_renamed), awaitItem())
            assertEquals("Night", page.shownName())
        }
    }

    @Test
    fun should_notSendTheQueuedRename_when_theAccountChangedBeforeItsTurn() = runTest {
        val page = page()
        val first = page.holdRename("Evening")
        page.viewModel.rename("Evening")
        page.viewModel.rename("Night")
        runCurrent()

        // The page is closed and another account is switched to before the
        // first rename is answered.
        page.viewModel.viewModelScope.cancel()
        page.profileId = "account-b"
        first.complete(Result.success(Unit))
        advanceUntilIdle()

        coVerify(exactly = 1) { page.repository.renamePlaylist(PLAYLIST_ID, "Evening", any()) }
        coVerify(exactly = 0) { page.repository.renamePlaylist(PLAYLIST_ID, "Night", any()) }
        assertEquals(1, page.mutations)
    }

    /** The playlist "Morning" on its page. */
    private class Page {
        /** The name the service gives the next time the playlist is read. */
        var serverName = "Morning"

        /** How often the Library was told a playlist changed. */
        var mutations = 0

        /** The account in use. */
        var profileId = "account-a"

        val repository: YoinRepository = mockk<YoinRepository>(relaxed = true).also { repository ->
            coEvery { repository.awaitActiveSource(any()) } returns mockk<MusicSource>()
            coEvery { repository.getPlaylist(any()) } answers {
                Playlist(
                    id = PLAYLIST_ID,
                    name = serverName,
                    owner = null,
                    coverArt = null,
                    songCount = 0,
                    durationSec = 0,
                    canWrite = true,
                )
            }
            every { repository.observeTracksWithNotes(any()) } returns flowOf(emptySet())
            every { repository.currentProfileId() } answers { profileId }
            coEvery { repository.renamePlaylist(any(), any(), any()) } returns Result.success(Unit)
        }

        val viewModel = PlaylistDetailViewModel(
            playlistId = PLAYLIST_ID.toString(),
            repository = repository,
            onPlaylistMutated = { mutations++ },
        )

        fun shownName(): String = (viewModel.uiState.value as PlaylistDetailUiState.Content).playlistName

        /** The rename to [name] waits for the returned answer. */
        fun holdRename(name: String): CompletableDeferred<Result<Unit>> {
            val answer = CompletableDeferred<Result<Unit>>()
            coEvery { repository.renamePlaylist(PLAYLIST_ID, name, any()) } coAnswers { answer.await() }
            return answer
        }
    }

    /** The page, loaded. */
    private fun TestScope.page(): Page = Page().also { advanceUntilIdle() }

    private companion object {
        val PLAYLIST_ID = MediaId.subsonic("pl-1")
    }
}
