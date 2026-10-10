package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.local.ArtistSongPlayAggregate
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.ArtistListening
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The personal layer (local listening) merged into the Artist page after it
 * paints. The hero no longer shows Last Play or an album average and the
 * Discography rows carry no score, so what is left is the play count and
 * Most Played.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<YoinRepository>(relaxed = true)

    @Before
    fun setUp() {
        coEvery { repository.getArtist(any()) } returns artist()
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.resolveCoverUrl(any(), any()) } answers {
            (firstArg<CoverRef?>() as? CoverRef.SourceRelative)?.let { "cover://${it.coverArtId}" }
        }
    }

    @Test
    fun should_mapPlayCountAndMostPlayed_when_listeningLoads() = runTest {
        coEvery { repository.getArtistListening(any(), any(), any(), any()) } returns ArtistListening(
            playCount = 12,
            topSongs = listOf(
                aggregate("s1", albumId = "al-1", coverArtId = "c1", durationMs = 185_400L, playCount = 7),
                aggregate("s2", albumId = "", coverArtId = null, durationMs = 0L, playCount = 5)
            )
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(
            ArtistListeningSummary(
                playCount = 12,
                mostPlayed = listOf(
                    ArtistPlayedSong(
                        id = "subsonic:s1",
                        title = "Song s1",
                        album = "Album",
                        coverArtUrl = "cover://c1",
                        durationSec = 185,
                        playCount = 7
                    ),
                    ArtistPlayedSong(
                        id = "subsonic:s2",
                        title = "Song s2",
                        album = "Album",
                        coverArtUrl = null,
                        durationSec = null,
                        playCount = 5
                    )
                )
            ),
            content(viewModel).listening
        )
        // The same rows play as a queue, in row order.
        val tracks = viewModel.getMostPlayedTracks()
        assertEquals(listOf(MediaId.subsonic("s1"), MediaId.subsonic("s2")), tracks.map { it.id })
        assertEquals(MediaId.subsonic("al-1"), tracks[0].albumId)
        assertNull(tracks[1].albumId)
        assertEquals(185, tracks[0].durationSec)
        assertNull(tracks[1].durationSec)
    }

    @Test
    fun should_fallBackToAnEmptySummary_when_theListeningReadFails() = runTest {
        coEvery { repository.getArtistListening(any(), any(), any(), any()) } throws IllegalStateException("locked")

        val viewModel = viewModel()
        advanceUntilIdle()

        val content = content(viewModel)
        assertEquals(ArtistListeningSummary(playCount = 0, mostPlayed = emptyList()), content.listening)
        assertTrue(viewModel.getMostPlayedTracks().isEmpty())
        // The provider's releases stay as they painted.
        assertEquals(listOf("subsonic:al-1", "subsonic:al-2"), content.albums.map { it.id })
    }

    @Test
    fun should_fallBackToAnEmptySummary_when_noProfileIsActive() = runTest {
        coEvery { repository.getArtistListening(any(), any(), any(), any()) } returns null

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(ArtistListeningSummary(playCount = 0, mostPlayed = emptyList()), content(viewModel).listening)
    }

    @Test
    fun should_growMostPlayed_when_refreshPersonalSeesAFirstPlay() = runTest {
        coEvery { repository.getArtistListening(any(), any(), any(), any()) } returnsMany listOf(
            ArtistListening(0, emptyList()),
            ArtistListening(1, listOf(aggregate("s1", playCount = 1)))
        )

        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(content(viewModel).listening!!.mostPlayed.isEmpty())

        viewModel.refreshPersonal()
        advanceUntilIdle()

        val listening = content(viewModel).listening!!
        assertEquals(1, listening.playCount)
        assertEquals(listOf("subsonic:s1"), listening.mostPlayed.map { it.id })
        assertEquals(listOf(MediaId.subsonic("s1")), viewModel.getMostPlayedTracks().map { it.id })
    }

    private fun viewModel() = ArtistDetailViewModel(ARTIST_ID.toString(), repository)

    private fun content(viewModel: ArtistDetailViewModel) = viewModel.uiState.value as ArtistDetailUiState.Content

    private fun artist() = ArtistDetail(
        id = ARTIST_ID,
        name = "Artist",
        albumCount = 2,
        coverArt = null,
        albums = listOf(album("al-1", year = 2024), album("al-2", year = 2019))
    )

    private fun album(rawId: String, year: Int) = Album(
        id = MediaId.subsonic(rawId),
        name = "Album $rawId",
        artist = "Artist",
        artistId = ARTIST_ID,
        coverArt = null,
        songCount = 10,
        durationSec = null,
        year = year,
        genre = null
    )

    private fun aggregate(
        songId: String,
        albumId: String = "al-1",
        coverArtId: String? = null,
        durationMs: Long = 0L,
        playCount: Int
    ) = ArtistSongPlayAggregate(
        songId = songId,
        provider = MediaId.PROVIDER_SUBSONIC,
        title = "Song $songId",
        album = "Album",
        albumId = albumId,
        coverArtId = coverArtId,
        durationMs = durationMs,
        playCount = playCount,
        lastPlayedAt = 0L
    )

    private companion object {
        val ARTIST_ID = MediaId.subsonic("ar-1")
    }
}
