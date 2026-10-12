package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The visit row a detail page records is not on the way to its Content. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailVisitRecordingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<YoinRepository>(relaxed = true)
    private val album = Album(
        id = MediaId(MediaId.PROVIDER_SUBSONIC, "al-1"),
        name = "Album",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 0,
        durationSec = 0,
        year = 2020,
        genre = null
    )
    private val artist = ArtistDetail(
        id = MediaId(MediaId.PROVIDER_SUBSONIC, "ar-1"),
        name = "Artist",
        albumCount = 1,
        coverArt = null,
        albums = listOf(album)
    )

    @Before
    fun setUp() {
        coEvery { repository.getAlbum(any()) } returns album
        coEvery { repository.getArtist(any()) } returns artist
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.trackLibraryStates } returns MutableStateFlow<Map<MediaId, LibraryMembership>>(emptyMap())
        every { repository.observeAlbumRating(any()) } returns flowOf(null)
        every { repository.observeTracksWithNotes(any()) } returns flowOf(emptySet())
        every { repository.resolveCoverUrl(any(), any()) } returns null
        coEvery { repository.getArtistListening(any(), any(), any(), any()) } returns null
        // The Room insert never finishes (a long sync transaction holding the database, say).
        coEvery { repository.recordAlbumVisit(any()) } coAnswers { awaitCancellation() }
        coEvery { repository.recordArtistVisit(any()) } coAnswers { awaitCancellation() }
    }

    @Test
    fun should_notAwaitVisitInsert_when_emittingContent() = runTest {
        val albumPage = AlbumDetailViewModel(albumId = "subsonic:al-1", repository = repository)
        val artistPage = ArtistDetailViewModel(artistId = "subsonic:ar-1", repository = repository)
        advanceUntilIdle()

        assertTrue(albumPage.uiState.value is AlbumDetailUiState.Content)
        assertTrue(artistPage.uiState.value is ArtistDetailUiState.Content)
        // Still recorded — just not waited for.
        coVerify { repository.recordAlbumVisit(album) }
        coVerify { repository.recordArtistVisit(artist) }
    }
}
