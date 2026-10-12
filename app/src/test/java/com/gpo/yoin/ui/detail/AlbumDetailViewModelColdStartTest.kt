package com.gpo.yoin.ui.detail

import app.cash.turbine.test
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookSource
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A widget tap on a cold process opens the album page before ProfileManager has built the active
 * source: the first load waits for it rather than failing with "No profile configured".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDetailViewModelColdStartTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<YoinRepository>(relaxed = true)
    private val activeSource = MutableStateFlow<MusicSource?>(null)
    private val scrapbookSource = AlbumScrapbookSource { flowOf(AlbumScrapbookData.Empty) }

    @Before
    fun setUp() {
        // YoinRepository's own contract, over this test's source.
        coEvery { repository.awaitActiveSource(any()) } coAnswers {
            withTimeoutOrNull(firstArg<Long>()) { activeSource.filterNotNull().first() }
        }
        // requireSource: a fetch with no source throws.
        coEvery { repository.getAlbum(any()) } answers {
            if (activeSource.value == null) {
                throw SubsonicException(code = -1, message = "No profile configured. Open Settings to add one.")
            }
            album()
        }
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.trackLibraryStates } returns flowOf(emptyMap())
        every { repository.observeAlbumRating(any()) } returns flowOf(null)
        every { repository.observeTracksWithNotes(any()) } returns flowOf(emptySet())
        every { repository.resolveCoverUrl(any(), any()) } returns null
    }

    @Test
    fun should_loadAlbum_when_sourceArrivesAfterOpen() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertEquals(AlbumDetailUiState.Loading, awaitItem())
            advanceTimeBy(1_000L)
            expectNoEvents()

            activeSource.value = mockk<MusicSource>()
            val content = awaitItem() as AlbumDetailUiState.Content
            assertEquals("Album", content.albumName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_failAsBefore_when_noSourceArrivesInTime() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertEquals(AlbumDetailUiState.Loading, awaitItem())
            advanceTimeBy(DETAIL_SOURCE_WAIT_MS + 1)
            assertTrue(awaitItem() is AlbumDetailUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun viewModel() = AlbumDetailViewModel(
        albumId = "subsonic:al-1",
        repository = repository,
        scrapbookSource = scrapbookSource,
        playback = null,
        clock = { 1_791_000_000_000L }
    )

    private fun album() = Album(
        id = MediaId(MediaId.PROVIDER_SUBSONIC, "al-1"),
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
                id = MediaId(MediaId.PROVIDER_SUBSONIC, "t1"),
                title = "Song 1",
                artist = "Artist",
                artistId = null,
                album = "Album",
                albumId = MediaId(MediaId.PROVIDER_SUBSONIC, "al-1"),
                coverArt = null,
                durationSec = 200,
                trackNumber = 1,
                year = 2020,
                genre = null,
                userRating = null
            )
        )
    )
}
