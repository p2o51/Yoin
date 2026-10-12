package com.gpo.yoin.ui.detail

import app.cash.turbine.test
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.MediaId
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The artist page twin of [AlbumDetailViewModelColdStartTest]: its first load waits for the cold-start source. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistDetailViewModelColdStartTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<YoinRepository>(relaxed = true)
    private val activeSource = MutableStateFlow<MusicSource?>(null)

    @Before
    fun setUp() {
        coEvery { repository.awaitActiveSource(any()) } coAnswers {
            withTimeoutOrNull(firstArg<Long>()) { activeSource.filterNotNull().first() }
        }
        coEvery { repository.getArtist(any()) } answers {
            if (activeSource.value == null) {
                throw SubsonicException(code = -1, message = "No profile configured. Open Settings to add one.")
            }
            ArtistDetail(
                id = MediaId(MediaId.PROVIDER_SUBSONIC, "ar-1"),
                name = "Artist",
                albumCount = 0,
                coverArt = null
            )
        }
        coEvery { repository.getArtistListening(any(), any(), any(), any()) } returns null
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
    }

    @Test
    fun should_loadArtist_when_sourceArrivesAfterOpen() = runTest {
        val viewModel = ArtistDetailViewModel(artistId = "subsonic:ar-1", repository = repository)

        viewModel.uiState.test {
            assertEquals(ArtistDetailUiState.Loading, awaitItem())
            advanceTimeBy(1_000L)
            expectNoEvents()

            activeSource.value = mockk<MusicSource>()
            val content = awaitItem() as ArtistDetailUiState.Content
            assertEquals("Artist", content.artistName)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
