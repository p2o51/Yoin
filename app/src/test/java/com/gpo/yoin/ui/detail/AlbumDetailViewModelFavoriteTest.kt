package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookSource
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.FavoriteState
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.testutil.MainDispatcherRule
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The album rows' hearts (P4, D4): the page is out first, then one batched
 * check, whose late answer flips a heart quietly; a tap shows at once and
 * animates as the user's; a failed write falls back quietly; coming back on
 * screen asks again (the repository throttles it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDetailViewModelFavoriteTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<YoinRepository>(relaxed = true)
    private val first = track("t1")
    private val second = track("t2")
    private val album = Album(
        id = MediaId.spotify("al1"),
        name = "Album",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 2,
        durationSec = 400,
        year = 2020,
        genre = null,
        tracks = listOf(first, second)
    )
    private val states = MutableStateFlow(
        mapOf(first.id to FavoriteState(false), second.id to FavoriteState(false))
    )

    @Before
    fun setUp() {
        coEvery { repository.awaitActiveSource(any()) } returns mockk<MusicSource>()
        coEvery { repository.getAlbum(any()) } returns album
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.trackLibraryStates } returns flowOf(emptyMap())
        every { repository.observeAlbumRating(any()) } returns flowOf(null)
        every { repository.observeTracksWithNotes(any()) } returns flowOf(emptySet())
        every { repository.resolveCoverUrl(any(), any()) } returns null
        every { repository.observeFavoriteStates(any()) } returns states
        every { repository.observeAlbumSaved(any()) } returns flowOf(null)
    }

    @Test
    fun should_askSpotifyOnceAndFlipQuietly_when_theAnswerComesAfterThePage() = runTest {
        val viewModel = viewModel()
        runCurrent()
        coVerify(exactly = 1) { repository.refreshFavoriteStates(listOf(first, second), any(), album) }
        assertEquals(false to 0, row(viewModel, first))

        states.value = states.value + (first.id to FavoriteState(isStarred = true, fromUser = false))
        runCurrent()

        assertEquals(true to 1, row(viewModel, first))
        assertEquals(false to 0, row(viewModel, second))
        // The play queue's copies follow the hearts.
        assertEquals(listOf(true, false), viewModel.getAlbumSongs().map(Track::isStarred))
    }

    @Test
    fun should_showTheTapAtOnceAndFallBackQuietly_when_theWriteFails() = runTest {
        val write = CompletableDeferred<Result<Unit>>()
        coEvery { repository.setFavorite(second, true) } coAnswers { write.await() }
        val viewModel = viewModel()
        runCurrent()

        viewModel.toggleStar(second.id.toString())
        runCurrent()
        assertEquals(true to 0, row(viewModel, second))

        write.complete(Result.failure(IllegalStateException("offline")))
        runCurrent()
        assertEquals(false to 1, row(viewModel, second))
    }

    @Test
    fun should_askAgain_when_thePageResumes() = runTest {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onResumed()
        runCurrent()

        coVerify(exactly = 2) { repository.refreshFavoriteStates(listOf(first, second), any(), album) }
    }

    private fun viewModel() = AlbumDetailViewModel(
        albumId = album.id.toString(),
        repository = repository,
        scrapbookSource = AlbumScrapbookSource { flowOf(AlbumScrapbookData.Empty) },
        playback = null,
        clock = { 1_791_000_000_000L }
    )

    private fun row(viewModel: AlbumDetailViewModel, track: Track): Pair<Boolean, Int> {
        val content = viewModel.uiState.value as AlbumDetailUiState.Content
        val song = content.songs.single { it.id == track.id.toString() }
        return song.isStarred to song.favoriteQuietFlips
    }

    private fun track(rawId: String) = Track(
        id = MediaId.spotify(rawId),
        title = rawId,
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
}
