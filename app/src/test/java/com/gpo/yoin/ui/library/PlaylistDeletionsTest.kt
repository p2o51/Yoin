package com.gpo.yoin.ui.library

import app.cash.turbine.test
import com.gpo.yoin.R
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.common.AppNotice
import com.gpo.yoin.ui.common.AppNotices
import com.gpo.yoin.ui.common.UiText
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Deleting a playlist past its page's life (PlaylistDeletions): it leaves the
 * Library at once, goes for good when the service takes it, comes back when
 * it refuses, is never sent to another account, and its notice is shown once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDeletionsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val morning = playlist("morning")
    private val evening = playlist("evening")

    @Test
    fun should_leaveTheLibraryAtOnceAndGoForGood_when_theServiceDeletesIt() = runTest {
        val world = world(listOf(morning, evening))
        val answer = world.holdDelete()
        val library = world.library()
        advanceUntilIdle()
        library.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()
        assertEquals(listOf("evening", "morning"), library.shownPlaylists())

        world.notices.notices.test {
            world.deletions.delete(morning.id)
            runCurrent()

            // Gone from the list before the service answers.
            assertEquals(listOf("evening"), library.shownPlaylists())
            assertEquals(listOf(morning.id), world.sent)
            expectNoEvents()

            // Taken: the server no longer lists it, and it stays gone.
            world.serverPlaylists = listOf(evening)
            answer.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(AppNotice(UiText.Res(R.string.detail_playlist_deleted)), awaitItem())
            assertEquals(listOf("evening"), library.shownPlaylists())
            assertEquals(1, world.mutations)
            assertTrue(world.deletions.state.value.pending.isEmpty())
        }
    }

    @Test
    fun should_comeBackWhereItWas_when_theServiceRefuses() = runTest {
        val world = world(listOf(morning, evening))
        val answer = world.holdDelete()
        val library = world.library()
        advanceUntilIdle()
        library.selectTab(LibraryTab.Playlists)
        advanceUntilIdle()

        world.notices.notices.test {
            world.deletions.delete(morning.id)
            runCurrent()
            assertEquals(listOf("evening"), library.shownPlaylists())

            answer.complete(Result.failure(IllegalStateException("Not the owner")))
            advanceUntilIdle()

            assertEquals(listOf("evening", "morning"), library.shownPlaylists())
            assertEquals(AppNotice(UiText.Res(R.string.detail_playlist_error_delete)), awaitItem())
            assertEquals(0, world.mutations)
        }
    }

    @Test
    fun should_comeBack_when_theRequestFailsOnTheNetwork() = runTest {
        val world = world(listOf(morning, evening))
        world.deleteAnswer = { throw java.io.IOException("Unreachable") }

        world.deletions.state.test {
            assertEquals(PlaylistDeletions.State(), awaitItem())
            world.deletions.delete(morning.id)
            assertEquals(setOf(PlaylistDeletions.Key(PROFILE, morning.id)), awaitItem().pending)

            advanceUntilIdle()
            assertEquals(PlaylistDeletions.State(), awaitItem())
        }
        assertEquals(AppNotice(UiText.Res(R.string.detail_playlist_error_delete)), world.notices.take())
    }

    @Test
    fun should_sendNothingToTheNewAccount_when_theAccountChangesBeforeTheRequest() = runTest {
        val world = world(listOf(morning, evening))

        world.deletions.delete(morning.id)
        // The switch lands before the deletion's turn comes.
        world.profileId = "another-account"
        advanceUntilIdle()

        assertEquals(emptyList<MediaId>(), world.sent)
        assertEquals(PlaylistDeletions.State(), world.deletions.state.value)
        assertEquals(AppNotice(UiText.Res(R.string.detail_playlist_error_delete)), world.notices.take())
        assertEquals(0, world.mutations)
    }

    @Test
    fun should_deliverTheNoticeOnce_when_twoWindowsTakeNotices() = runTest {
        val notices = AppNotices()
        val front = mutableListOf<AppNotice>()
        val beneath = mutableListOf<AppNotice>()
        backgroundScope.launch { notices.notices.collect { front += it } }
        backgroundScope.launch { notices.notices.collect { beneath += it } }
        runCurrent()

        val deleted = AppNotice(UiText.Res(R.string.detail_playlist_deleted))
        notices.post(deleted)
        runCurrent()

        assertEquals(listOf(deleted), front + beneath)
        // A window that starts taking notices later does not see it again.
        notices.notices.test { expectNoEvents() }
    }

    @Test
    fun should_leaveTheNoticeToTheRevealedWindow_when_thePageStopsTakingNoticesAsItArrives() = runTest {
        val notices = AppNotices()
        val page = MutableStateFlow(true)
        val shownOnPage = mutableListOf<AppNotice>()
        val shownOnShell = mutableListOf<AppNotice>()
        // The page in front is waiting for a notice.
        val pageTurn = backgroundScope.launch { notices.showWhile(page) { shownOnPage += it } }
        runCurrent()

        // Delete turns the page off; the answer comes before the page's turn ends.
        page.value = false
        val deleted = AppNotice(UiText.Res(R.string.detail_playlist_deleted))
        notices.post(deleted)
        runCurrent()
        pageTurn.cancel()

        // The shell, revealed, takes it.
        backgroundScope.launch { notices.showWhile(MutableStateFlow(true)) { shownOnShell += it } }
        runCurrent()

        assertEquals(emptyList<AppNotice>(), shownOnPage)
        assertEquals(listOf(deleted), shownOnShell)
    }

    @Test
    fun should_waitForTheNextWindow_when_noneTakesNoticesYet() = runTest {
        val notices = AppNotices()
        val deleted = AppNotice(UiText.Res(R.string.detail_playlist_deleted))

        // Posted while the page closes and the window beneath is not yet in front.
        notices.post(deleted)

        notices.notices.test {
            assertEquals(deleted, awaitItem())
            expectNoEvents()
        }
    }

    private suspend fun AppNotices.take(): AppNotice {
        var taken: AppNotice? = null
        notices.test { taken = awaitItem() }
        return taken!!
    }

    private fun LibraryViewModel.shownPlaylists(): List<String> =
        (uiState.value as LibraryUiState.Content).playlists.orEmpty().map { it.id.rawId }.sorted()

    private inner class World(initial: List<Playlist>) {
        var profileId: String? = PROFILE
        var serverPlaylists = initial
        var mutations = 0
        val sent = mutableListOf<MediaId>()
        var deleteAnswer: suspend () -> Result<Unit> = { Result.success(Unit) }
        val notices = AppNotices()
        var onMutation: () -> Unit = {}

        val deletions = PlaylistDeletions(
            scope = CoroutineScope(mainDispatcherRule.dispatcher),
            currentProfileId = { profileId },
            deletePlaylist = { id ->
                sent += id
                deleteAnswer()
            },
            notices = notices,
            onDeleted = {
                mutations++
                onMutation()
            },
        )

        fun holdDelete(): CompletableDeferred<Result<Unit>> {
            val answer = CompletableDeferred<Result<Unit>>()
            deleteAnswer = { answer.await() }
            return answer
        }

        val repository: YoinRepository = mockk<YoinRepository>(relaxed = true).also { repository ->
            val capabilities = setOf(Capability.PLAYLISTS_READ, Capability.PLAYLISTS_WRITE)
            every { repository.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
            every { repository.currentCapabilities() } returns capabilities
            every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SUBSONIC)
            every { repository.capabilities } returns flowOf(capabilities)
            every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
            every { repository.trackLibraryStates } returns flowOf(emptyMap())
            every { repository.currentProfileId() } answers { profileId }
            every { repository.currentProfileIdFlow } returns flowOf(PROFILE)
            every { repository.libraryRevision } returns flowOf(0L)
            coEvery { repository.getArtists() } returns emptyList()
            coEvery { repository.getAlbumList("newest", size = 500) } returns listOf(
                Album(
                    id = MediaId.subsonic("al"),
                    name = "Album",
                    artist = "Someone",
                    artistId = null,
                    coverArt = null,
                    songCount = null,
                    durationSec = null,
                    year = null,
                    genre = null,
                ),
            )
            coEvery { repository.getPlaylists() } answers { serverPlaylists }
        }

        /** Sorting on the test dispatcher, by a JVM name order (android.icu is a stub off device). */
        fun library() = LibraryViewModel(
            repository = repository,
            sortDispatcher = mainDispatcherRule.dispatcher,
            nameOrder = { String.CASE_INSENSITIVE_ORDER },
            scrollIndex = { JvmLibraryScrollIndex },
            playlistDeletions = deletions.state,
        ).also { viewModel ->
            // As AppContainer.notifyPlaylistMutation reaches it through YoinNavHost.
            onMutation = viewModel::invalidatePlaylists
        }
    }

    private fun world(initial: List<Playlist>) = World(initial)

    private fun playlist(id: String) = Playlist(
        id = MediaId.subsonic(id),
        name = id,
        owner = null,
        coverArt = null,
        songCount = null,
        durationSec = null,
        ownedByMe = true,
    )

    private companion object {
        const val PROFILE = "test-profile"
    }
}
