package com.gpo.yoin.ui.detail

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The playlist page's noted songs are in the rows' own id form, and follow note writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelNotesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun should_matchTheRowIds_when_songsInThePlaylistCarryNotes() = runTest {
        val noted = MutableStateFlow(setOf(MediaId.subsonic("t2")))
        val viewModel = viewModel(noted)
        backgroundScope.launch { viewModel.notedSongIds.collect {} }
        advanceUntilIdle()

        val rows = (viewModel.uiState.value as PlaylistDetailUiState.Content).songs.map(PlaylistSong::id)
        assertEquals(listOf("subsonic:t1", "subsonic:t2", "subsonic:t3"), rows)
        assertEquals(setOf("subsonic:t2"), viewModel.notedSongIds.value)

        // A note written elsewhere (Now Playing) lands on its row.
        noted.value = setOf(MediaId.subsonic("t2"), MediaId.subsonic("t3"))
        advanceUntilIdle()

        assertEquals(setOf("subsonic:t2", "subsonic:t3"), viewModel.notedSongIds.value)
        assertEquals(rows.drop(1).toSet(), viewModel.notedSongIds.value)
    }

    private fun viewModel(noted: MutableStateFlow<Set<MediaId>>): PlaylistDetailViewModel {
        val repository = mockk<YoinRepository>(relaxed = true)
        coEvery { repository.awaitActiveSource(any()) } returns mockk<MusicSource>()
        coEvery { repository.getPlaylist(any()) } returns Playlist(
            id = MediaId.subsonic("pl-1"),
            name = "Playlist",
            owner = null,
            coverArt = null,
            songCount = 3,
            durationSec = 600,
            tracks = listOf(track("t1"), track("t2"), track("t3"))
        )
        // Only the playlist's own tracks are asked about.
        every { repository.observeTracksWithNotes(any()) } answers {
            val asked = firstArg<Collection<MediaId>>().toSet()
            noted.map { ids -> ids.filterTo(LinkedHashSet()) { it in asked } }
        }
        return PlaylistDetailViewModel(playlistId = "subsonic:pl-1", repository = repository)
    }

    private fun track(rawId: String): Track = Track(
        id = MediaId.subsonic(rawId),
        title = "Song $rawId",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = MediaId.subsonic("al-1"),
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )
}
