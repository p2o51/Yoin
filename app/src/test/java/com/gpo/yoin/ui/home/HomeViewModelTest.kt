package com.gpo.yoin.ui.home

import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun should_render_cached_spotify_jump_back_in_before_network_refresh() = runTest {
        val repository = mockk<YoinRepository>(relaxed = true)
        val profileId = MutableStateFlow("spotify-profile")
        val gate = CompletableDeferred<Unit>()
        val cachedAlbum = album("cached-album", "Cached Album")
        val cachedArtist = artist("cached-artist", "Cached Artist")
        val freshAlbum = album("fresh-album", "Fresh Album")
        val freshArtist = artist("fresh-artist", "Fresh Artist")

        every { repository.currentProviderId() } returns MediaId.PROVIDER_SPOTIFY
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.getRecentActivities(limit = 20) } returns flowOf(emptyList<ActivityEvent>())
        coEvery {
            repository.getCachedSpotifyHomeJumpBackIn(
                profileId = "spotify-profile",
                maxAgeMs = any(),
            )
        } returns YoinRepository.SpotifyHomeJumpBackInCacheSnapshot(
            albums = listOf(cachedAlbum),
            artists = listOf(cachedArtist),
        )
        coEvery { repository.getAlbumList("random", 18, 0) } coAnswers {
            gate.await()
            listOf(freshAlbum)
        }
        coEvery { repository.getArtists() } coAnswers {
            gate.await()
            listOf(ArtistIndex(name = "F", artists = listOf(freshArtist)))
        }
        coEvery {
            repository.replaceSpotifyHomeJumpBackInCache(
                profileId = "spotify-profile",
                albums = listOf(freshAlbum),
                artists = listOf(freshArtist),
                cachedAt = any(),
            )
        } just runs

        val viewModel = HomeViewModel(
            repository = repository,
            activeProfileId = profileId,
        )

        advanceUntilIdle()

        val cachedState = viewModel.uiState.value as HomeUiState.Content
        assertTrue(
            cachedState.jumpBackInItems.any { item ->
                item is HomeJumpBackInItem.AlbumItem && item.album.id == cachedAlbum.id
            },
        )
        assertTrue(
            cachedState.jumpBackInItems.any { item ->
                item is HomeJumpBackInItem.ArtistItem && item.artist.id == cachedArtist.id
            },
        )

        gate.complete(Unit)
        advanceUntilIdle()

        val freshState = viewModel.uiState.value as HomeUiState.Content
        assertTrue(
            freshState.jumpBackInItems.any { item ->
                item is HomeJumpBackInItem.AlbumItem && item.album.id == freshAlbum.id
            },
        )
        assertTrue(
            freshState.jumpBackInItems.any { item ->
                item is HomeJumpBackInItem.ArtistItem && item.artist.id == freshArtist.id
            },
        )
        coVerify {
            repository.replaceSpotifyHomeJumpBackInCache(
                profileId = "spotify-profile",
                albums = listOf(freshAlbum),
                artists = listOf(freshArtist),
                cachedAt = any(),
            )
        }
    }

    private fun album(rawId: String, name: String): Album = Album(
        id = MediaId.spotify(rawId),
        name = name,
        artist = "Artist",
        artistId = MediaId.spotify("artist-$rawId"),
        coverArt = null,
        songCount = 10,
        durationSec = null,
        year = 2024,
        genre = null,
    )

    private fun artist(rawId: String, name: String): Artist = Artist(
        id = MediaId.spotify(rawId),
        name = name,
        albumCount = 4,
        coverArt = null,
    )
}
