package com.gpo.yoin.ui.detail

import com.gpo.yoin.R
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookSource
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.AlbumSavedState
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitException
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The album page's Save to library / Remove from library row (Q11): it
 * follows the repository's saved state (which shows a write at once and
 * rolls a failed one back), a tap asks for the opposite, a failure says why
 * on the window's snackbar; a service that can't save albums gets no row, and
 * neither does an album whose saved state isn't known yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDetailViewModelLibrarySaveTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<YoinRepository>(relaxed = true)
    private val album = Album(
        id = MediaId.spotify("al1"),
        name = "Album",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 1,
        durationSec = 200,
        year = 2020,
        genre = null,
        tracks = listOf(
            Track(
                id = MediaId.spotify("t1"),
                title = "t1",
                artist = "Artist",
                artistId = null,
                album = "Album",
                albumId = MediaId.spotify("al1"),
                coverArt = null,
                durationSec = 200,
                trackNumber = 1,
                year = 2020,
                genre = null,
                userRating = null
            )
        )
    )

    /** The repository's resolved saved state: what it shows, a write in flight included. */
    private val saved = MutableStateFlow(AlbumSavedState.NotSaved)

    @Before
    fun setUp() {
        coEvery { repository.awaitActiveSource(any()) } returns mockk<MusicSource>()
        coEvery { repository.getAlbum(any()) } returns album
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.trackLibraryStates } returns flowOf(emptyMap())
        every { repository.observeAlbumRating(any()) } returns flowOf(null)
        every { repository.observeTracksWithNotes(any()) } returns flowOf(emptySet())
        every { repository.resolveCoverUrl(any(), any()) } returns null
        every { repository.observeFavoriteStates(any()) } returns flowOf(emptyMap())
        every { repository.observeAlbumSaved(album.id) } returns saved
    }

    @Test
    fun should_askAboutTheAlbumWithItsTracks_when_thePageLoads() = runTest {
        viewModel()
        runCurrent()

        coVerify(exactly = 1) { repository.refreshFavoriteStates(album.tracks, any(), album) }
    }

    @Test
    fun should_followTheRepositorysSavedState_when_itChanges() = runTest {
        val viewModel = viewModel()
        runCurrent()
        assertEquals(false, librarySaved(viewModel))

        // Spotify's late answer (or the mirror) says it is saved.
        saved.value = AlbumSavedState.Saved
        runCurrent()

        assertEquals(true, librarySaved(viewModel))
    }

    @Test
    fun should_saveAndShowItAtOnce_when_theRowIsTapped() = runTest {
        val write = CompletableDeferred<Result<Unit>>()
        coEvery { repository.setAlbumSaved(album, true) } coAnswers {
            saved.value = AlbumSavedState.Saved
            write.await()
        }
        val viewModel = viewModel()
        runCurrent()

        viewModel.toggleLibrarySaved()
        runCurrent()
        assertEquals(true, librarySaved(viewModel))

        write.complete(Result.success(Unit))
        runCurrent()
        assertEquals(true, librarySaved(viewModel))
        coVerify(exactly = 1) { repository.setAlbumSaved(album, true) }
    }

    @Test
    fun should_rollBackAndSayWhy_when_theRemovalIsRateLimited() = runTest {
        saved.value = AlbumSavedState.Saved
        val write = CompletableDeferred<Result<Unit>>()
        coEvery { repository.setAlbumSaved(album, false) } coAnswers {
            saved.value = AlbumSavedState.NotSaved
            write.await().also { saved.value = AlbumSavedState.Saved }
        }
        val viewModel = viewModel()
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { viewModel.messages.collect { messages += it } }
        runCurrent()

        viewModel.toggleLibrarySaved()
        runCurrent()
        assertEquals(false, librarySaved(viewModel))

        write.complete(Result.failure(SpotifyRateLimitException(retryAfterSeconds = 30, endpoint = "me/library")))
        runCurrent()

        assertEquals(true, librarySaved(viewModel))
        assertEquals(listOf<UiText>(UiText.Res(R.string.cmp_error_spotify_busy)), messages)
    }

    @Test
    fun should_sayItCouldNotSave_when_theSaveFailsForAnotherReason() = runTest {
        // Answered after a suspension, as a real write is: a Result answered
        // without one never reached onFailure under MockK here.
        val write = CompletableDeferred<Result<Unit>>()
        coEvery { repository.setAlbumSaved(album, true) } coAnswers { write.await() }
        val viewModel = viewModel()
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { viewModel.messages.collect { messages += it } }
        runCurrent()

        viewModel.toggleLibrarySaved()
        runCurrent()
        write.complete(Result.failure(IllegalStateException("boom")))
        runCurrent()

        assertEquals(listOf<UiText>(UiText.Res(R.string.detail_album_save_failed)), messages)
    }

    @Test
    fun should_offerNoRowAndWriteNothing_when_theServiceCannotSaveAlbums() = runTest {
        saved.value = AlbumSavedState.Unsupported
        val viewModel = viewModel()
        runCurrent()

        assertNull(librarySaved(viewModel))
        viewModel.toggleLibrarySaved()
        runCurrent()

        coVerify(exactly = 0) { repository.setAlbumSaved(any(), any()) }
    }

    @Test
    fun should_offerNoRowAndWriteNothing_when_theSavedStateIsUnknown() = runTest {
        // Not in the saved-albums mirror, and the rate-limit gate held the check back.
        saved.value = AlbumSavedState.Unknown
        val viewModel = viewModel()
        runCurrent()

        assertNull(librarySaved(viewModel))
        viewModel.toggleLibrarySaved()
        runCurrent()
        coVerify(exactly = 0) { repository.setAlbumSaved(any(), any()) }

        // The row comes in with the answer.
        saved.value = AlbumSavedState.Saved
        runCurrent()
        assertEquals(true, librarySaved(viewModel))
    }

    private fun viewModel() = AlbumDetailViewModel(
        albumId = album.id.toString(),
        repository = repository,
        scrapbookSource = AlbumScrapbookSource { flowOf(AlbumScrapbookData.Empty) },
        playback = null,
        clock = { 1_791_000_000_000L }
    )

    private fun librarySaved(viewModel: AlbumDetailViewModel): Boolean? =
        (viewModel.uiState.value as AlbumDetailUiState.Content).librarySaved
}
