package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The hero's sleeves are the rows' own cover urls, picked around the hero cover. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelSleevesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun should_useTheRowsCoverUrls_when_playlistHasItsOwnCover() = runTest {
        val content = load(coverArt = CoverRef.SourceRelative("pl-1"), albums = listOf("al-1", "al-1", "al-2", "al-3"))

        assertEquals(listOf("al-1@320", "al-2@320"), content.sleeveCoverUrls)
        // The very urls the rows load.
        assertEquals(content.songs[0].coverArtUrl, content.sleeveCoverUrls[0])
        assertEquals("pl-1@full", content.coverArtUrl)
    }

    @Test
    fun should_skipTheFirstSongsAlbum_when_heroFallsBackToTheFirstTrack() = runTest {
        val content = load(coverArt = null, albums = listOf("al-1", "al-2", "al-1", "al-3"))

        assertEquals("al-1@full", content.coverArtUrl)
        assertEquals(listOf("al-2@320", "al-3@320"), content.sleeveCoverUrls)
    }

    @Test
    fun should_haveNoSleeves_when_everySongIsOnTheHerosAlbum() = runTest {
        val content = load(coverArt = null, albums = listOf("al-1", "al-1"))

        assertEquals(emptyList<String>(), content.sleeveCoverUrls)
    }

    private fun TestScope.load(
        coverArt: CoverRef?,
        albums: List<String>,
    ): PlaylistDetailUiState.Content {
        val repository = mockk<YoinRepository>(relaxed = true)
        coEvery { repository.awaitActiveSource(any()) } returns mockk<MusicSource>()
        coEvery { repository.getPlaylist(any()) } returns Playlist(
            id = MediaId.subsonic("pl-1"),
            name = "Playlist",
            owner = null,
            coverArt = coverArt,
            songCount = albums.size,
            durationSec = 600,
            tracks = albums.mapIndexed { index, album -> track("t$index", album) },
        )
        every { repository.observeTracksWithNotes(any()) } returns flowOf(emptySet())
        every { repository.resolveCoverUrl(any(), any()) } answers {
            val ref = firstArg<CoverRef?>() as CoverRef.SourceRelative
            val size = secondArg<Int?>()
            "${ref.coverArtId}@${size ?: "full"}"
        }
        val viewModel = PlaylistDetailViewModel(playlistId = "subsonic:pl-1", repository = repository)
        advanceUntilIdle()
        return viewModel.uiState.value as PlaylistDetailUiState.Content
    }

    private fun track(rawId: String, album: String): Track = Track(
        id = MediaId.subsonic(rawId),
        title = "Song $rawId",
        artist = "Artist",
        artistId = null,
        album = "Album $album",
        albumId = MediaId.subsonic(album),
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}
