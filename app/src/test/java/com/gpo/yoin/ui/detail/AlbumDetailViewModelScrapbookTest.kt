package com.gpo.yoin.ui.detail

import app.cash.turbine.test
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookNote
import com.gpo.yoin.data.album.AlbumScrapbookPlays
import com.gpo.yoin.data.album.AlbumScrapbookQuery
import com.gpo.yoin.data.album.AlbumScrapbookSource
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDetailViewModelScrapbookTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<YoinRepository>(relaxed = true)
    private val data = MutableStateFlow(AlbumScrapbookData.Empty)
    private val queries = mutableListOf<AlbumScrapbookQuery>()
    private val libraryStates = MutableStateFlow<Map<MediaId, LibraryMembership>>(emptyMap())
    private val source = AlbumScrapbookSource { query ->
        queries += query
        data
    }

    @Before
    fun setUp() {
        coEvery { repository.getAlbum(any()) } returns album()
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.trackLibraryStates } returns libraryStates
        every { repository.observeAlbumRating(any()) } returns flowOf(null)
        every { repository.observeTracksWithNotes(any()) } returns flowOf(emptySet())
        every { repository.resolveCoverUrl(any(), any()) } returns null
    }

    @Test
    fun should_emitReadyScrapbook_when_albumAndDataLoad() = runTest {
        val viewModel = viewModel()

        viewModel.scrapbook.test {
            assertEquals(AlbumScrapbookUiState.Loading, awaitItem())
            val ready = awaitItem() as AlbumScrapbookUiState.Ready
            val notYet = ready.book.pieces.filterIsInstance<ScrapPiece.NotYet>().single()
            assertTrue(notYet.all)
            assertEquals(3, notYet.tracks.size)
            cancelAndIgnoreRemainingEvents()
        }
        // the source is asked about this album, its tracks in album order
        val query = queries.single()
        assertEquals(MediaId(MediaId.PROVIDER_SUBSONIC, "al-1"), query.albumId)
        assertEquals(listOf("Song 1", "Song 2", "Song 3"), query.tracks.map { it.title })
    }

    @Test
    fun should_rebuildScrapbook_when_aNoteArrives() = runTest {
        val viewModel = viewModel()

        viewModel.scrapbook.test {
            assertEquals(AlbumScrapbookUiState.Loading, awaitItem())
            awaitItem() // the empty book
            data.value = AlbumScrapbookData(
                notes = mapOf(id(2) to listOf(AlbumScrapbookNote("n1", "here", 61_000L, 1L))),
            )
            val ready = awaitItem() as AlbumScrapbookUiState.Ready
            val cluster = ready.book.pieces.filterIsInstance<ScrapPiece.TrackCluster>().single()
            assertEquals("subsonic:t2", cluster.track.songId)
            assertEquals(61_000L, cluster.notes.single().positionMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_updatePageOneSignals_when_scoresAndPlaysEmit() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        data.value = AlbumScrapbookData(
            ratings = mapOf(id(1) to 8f, id(3) to 6f),
            plays = AlbumScrapbookPlays(count = 2, firstPlayedAt = 10L, lastPlayedAt = 99L),
        )
        advanceUntilIdle()

        val content = viewModel.uiState.value as AlbumDetailUiState.Content
        assertEquals(7f, content.averageTrackRating)
        assertEquals(2, content.ratedTrackCount)
        assertEquals(setOf("subsonic:t1", "subsonic:t3"), content.ratedSongIds)
        assertEquals(99L, content.lastPlayedAt)
        assertEquals(listOf(true, false, true), content.emblemSpec().trackRated)
    }

    @Test
    fun should_notRebuildScrapbook_when_unrelatedContentChanges() = runTest {
        val viewModel = viewModel()

        viewModel.scrapbook.test {
            assertEquals(AlbumScrapbookUiState.Loading, awaitItem())
            awaitItem()
            // a review draft keystroke changes the book (the opening clipping) …
            viewModel.onReviewDraftChange("New words")
            val withReview = awaitItem() as AlbumScrapbookUiState.Ready
            assertEquals("New words", withReview.book.pieces.filterIsInstance<ScrapPiece.Opening>().single().review)
            // … a library check on a track (Content changes, the book doesn't) does not
            libraryStates.value = mapOf(id(1) to LibraryMembership.Added)
            runCurrent()
            assertEquals(
                LibraryMembership.Added,
                (viewModel.uiState.value as AlbumDetailUiState.Content).songs.first().libraryMembership,
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_showTheEmptyScrapbook_when_theScrapbookReadFails() = runTest {
        val failing = AlbumScrapbookSource { flow { throw IllegalStateException("database closed") } }
        val viewModel = viewModel(scrapbookSource = failing)

        viewModel.scrapbook.test {
            assertEquals(AlbumScrapbookUiState.Loading, awaitItem())
            // not stuck in Loading: the empty book, every track still to come
            val ready = awaitItem() as AlbumScrapbookUiState.Ready
            assertTrue(ready.book.pieces.filterIsInstance<ScrapPiece.NotYet>().single().all)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_rebuildFromTheRetriedRead_when_aFailedReadRecovers() = runTest {
        var subscriptions = 0
        val recovering = AlbumScrapbookSource {
            flow {
                if (subscriptions++ == 0) throw IllegalStateException("database closed")
                emit(AlbumScrapbookData(notes = mapOf(id(2) to listOf(AlbumScrapbookNote("n1", "here", 61_000L, 1L)))))
            }
        }
        val viewModel = viewModel(scrapbookSource = recovering)

        viewModel.scrapbook.test {
            assertEquals(AlbumScrapbookUiState.Loading, awaitItem())
            awaitItem() // the empty book while the read waits to retry
            advanceTimeBy(AlbumDetailViewModel.scrapbookRetryDelayMs(0) + 1)
            val ready = awaitItem() as AlbumScrapbookUiState.Ready
            assertEquals("subsonic:t2", ready.book.pieces.filterIsInstance<ScrapPiece.TrackCluster>().single().track.songId)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, subscriptions)
    }

    @Test
    fun should_keepTheLastRead_when_aLaterReadFails() = runTest {
        val noted = AlbumScrapbookData(notes = mapOf(id(2) to listOf(AlbumScrapbookNote("n1", "here", 61_000L, 1L))))
        var subscriptions = 0
        val flaky = AlbumScrapbookSource {
            flow {
                if (subscriptions++ == 0) {
                    emit(noted)
                    throw IllegalStateException("database closed")
                }
                awaitCancellation()
            }
        }
        val viewModel = viewModel(scrapbookSource = flaky)

        viewModel.scrapbook.test {
            assertEquals(AlbumScrapbookUiState.Loading, awaitItem())
            val ready = awaitItem() as AlbumScrapbookUiState.Ready
            assertEquals(1, ready.book.pieces.filterIsInstance<ScrapPiece.TrackCluster>().size)
            // the failure neither empties the page nor rebuilds it
            advanceTimeBy(AlbumDetailViewModel.SCRAPBOOK_RETRY_MAX_MS)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, subscriptions)
    }

    @Test
    fun should_giveUpQuietly_when_theScrapbookReadKeepsFailing() = runTest {
        var subscriptions = 0
        val failing = AlbumScrapbookSource { flow { subscriptions++; throw IllegalStateException("database closed") } }
        val viewModel = viewModel(scrapbookSource = failing)

        viewModel.scrapbook.test {
            assertEquals(AlbumScrapbookUiState.Loading, awaitItem())
            assertTrue(awaitItem() is AlbumScrapbookUiState.Ready)
            advanceUntilIdle() // every retry runs out; nothing escapes to crash the page
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(AlbumDetailViewModel.SCRAPBOOK_MAX_RETRIES.toInt() + 1, subscriptions)
        assertTrue(viewModel.uiState.value is AlbumDetailUiState.Content)
    }

    @Test
    fun should_backOffUpToTheCap_when_scrapbookReadsKeepFailing() {
        assertEquals(1_000L, AlbumDetailViewModel.scrapbookRetryDelayMs(0))
        assertEquals(2_000L, AlbumDetailViewModel.scrapbookRetryDelayMs(1))
        assertEquals(16_000L, AlbumDetailViewModel.scrapbookRetryDelayMs(4))
        assertEquals(AlbumDetailViewModel.SCRAPBOOK_RETRY_MAX_MS, AlbumDetailViewModel.scrapbookRetryDelayMs(5))
        assertEquals(AlbumDetailViewModel.SCRAPBOOK_RETRY_MAX_MS, AlbumDetailViewModel.scrapbookRetryDelayMs(400))
    }

    @Test
    fun should_seekInPlace_when_noteSongIsAlreadyCurrentAndPrepared() = runTest {
        val player = FakePlayback(playing("t2", positionMs = 30_000L, isPlaying = false))
        val viewModel = viewModel(player)

        val inPlace = viewModel.requestNoteSeek("subsonic:t2", 61_000L)

        assertTrue(inPlace)
        assertEquals(listOf(61_000L), player.seeks)
        assertEquals(1, player.resumes)
    }

    @Test
    fun should_seekOnce_when_noteSongBecomesCurrentAndPrepared() = runTest {
        val player = FakePlayback(playing("t1", positionMs = 5_000L))
        val viewModel = viewModel(player)
        advanceUntilIdle()

        val inPlace = viewModel.requestNoteSeek("subsonic:t2", 61_000L)
        runCurrent()
        assertFalse(inPlace)
        assertTrue(player.seeks.isEmpty())

        // current but not prepared yet: still waiting
        player.state.value = playing("t2", positionMs = 0L, duration = 0L)
        runCurrent()
        assertTrue(player.seeks.isEmpty())

        player.state.value = playing("t2", positionMs = 0L)
        runCurrent()
        player.state.value = playing("t2", positionMs = 250L)
        advanceUntilIdle()
        assertEquals(listOf(61_000L), player.seeks)
    }

    @Test
    fun should_giveUpSeek_when_noteSongIsNotReadyInTime() = runTest {
        val player = FakePlayback(playing("t1", positionMs = 5_000L))
        val viewModel = viewModel(player)
        advanceUntilIdle()

        viewModel.requestNoteSeek("subsonic:t3", 10_000L)
        advanceTimeBy(AlbumDetailViewModel.SEEK_TIMEOUT_MS + 1)
        player.state.value = playing("t3", positionMs = 0L)
        advanceUntilIdle()

        assertTrue(player.seeks.isEmpty())
    }

    private fun viewModel(
        player: AlbumScrapbookPlayback? = null,
        scrapbookSource: AlbumScrapbookSource = source,
    ) = AlbumDetailViewModel(
        albumId = "subsonic:al-1",
        repository = repository,
        scrapbookSource = scrapbookSource,
        playback = player,
        clock = { 1_791_000_000_000L },
    )

    private fun id(n: Int) = MediaId(MediaId.PROVIDER_SUBSONIC, "t$n")

    private fun track(n: Int) = Track(
        id = id(n),
        title = "Song $n",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = MediaId(MediaId.PROVIDER_SUBSONIC, "al-1"),
        coverArt = null,
        durationSec = 200,
        trackNumber = n,
        year = 2020,
        genre = null,
        userRating = null,
    )

    private fun album() = Album(
        id = MediaId(MediaId.PROVIDER_SUBSONIC, "al-1"),
        name = "Album",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 3,
        durationSec = 600,
        year = 2020,
        genre = null,
        tracks = (1..3).map(::track),
    )

    private fun playing(raw: String, positionMs: Long, duration: Long = 200_000L, isPlaying: Boolean = true) =
        PlaybackState(
            currentTrack = track(raw.removePrefix("t").toInt()),
            isPlaying = isPlaying,
            position = positionMs,
            duration = duration,
        )

    private class FakePlayback(initial: PlaybackState) : AlbumScrapbookPlayback {
        override val state = MutableStateFlow(initial)
        val seeks = mutableListOf<Long>()
        var resumes = 0

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
        }

        override fun resume() {
            resumes++
        }
    }
}
