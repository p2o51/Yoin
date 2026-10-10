package com.gpo.yoin.ui.navigation

import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.player.PlaybackManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/** The shell's Songs-row wiring: LibraryScreen's onSongsListClick is [playSongsList]. */
class SongsListPlaybackTest {

    private val manager = mockk<PlaybackManager>(relaxed = true)
    private val activeSource = MutableStateFlow<MusicSource?>(source(MediaId.PROVIDER_SUBSONIC))
    private val container = mockk<AppContainer>().also {
        every { it.playbackManager } returns manager
        every { it.profileManager.activeSource } returns activeSource
    }
    private val songs = listOf(song("a"), song("b"), song("c"))

    @Test
    fun should_playWholeListFromTappedRowWithoutContext_when_songsRowTapped() {
        container.playSongsList(songs, startIndex = 1)

        // The tapped row is a pick (an import there reports why it can't play), not a shuffle,
        // and no Liked Songs context: Songs isn't Spotify's Liked order.
        verify(exactly = 1) {
            manager.play(
                tracks = songs,
                startIndex = 1,
                source = activeSource.value!!,
                activityContext = ActivityContext.None,
                shuffled = false,
                explicitStart = true
            )
        }
        verify(exactly = 0) { manager.playSingle(any(), any(), any()) }
    }

    @Test
    fun should_playOnlyTheWindowAroundTheTappedRow_when_songsListIsLong() {
        // Apple's Songs list runs to 500: MusicKit would ask for every id in one request.
        val library = (0 until 500).map { song("s$it") }

        container.playSongsList(library, startIndex = 300)

        verify(exactly = 1) {
            manager.play(
                tracks = library.subList(280, 380),
                startIndex = 20,
                source = activeSource.value!!,
                activityContext = ActivityContext.None,
                shuffled = false,
                explicitStart = true
            )
        }
    }

    @Test
    fun should_playTheWholeList_when_aShortListIsTappedNearItsEnd() {
        // Subsonic's Songs tab is a random 50: none of it is cut away, whichever row is tapped.
        val random = (0 until 50).map { song("s$it") }

        container.playSongsList(random, startIndex = 40)

        verify(exactly = 1) {
            manager.play(
                tracks = random,
                startIndex = 40,
                source = activeSource.value!!,
                activityContext = ActivityContext.None,
                shuffled = false,
                explicitStart = true
            )
        }
    }

    @Test
    fun should_startAsLikedSongs_when_spotifySongsRowTapped() {
        // Spotify's Songs is Liked Songs, newest like first: the row starts that collection at itself.
        activeSource.value = source(MediaId.PROVIDER_SPOTIFY)
        val liked = (0 until 300).map { spotifySong("s$it") }

        container.playSongsList(liked, startIndex = 150)

        verify(exactly = 1) {
            manager.play(
                tracks = liked.subList(130, 230),
                startIndex = 20,
                source = activeSource.value!!,
                activityContext = ActivityContext.LikedSongs(coverArtId = "https://i.scdn.co/image/s0"),
                shuffled = false,
                explicitStart = true
            )
        }
    }

    @Test
    fun should_startWithoutContext_when_appleMusicSongsRowTapped() {
        // Apple's Songs is the saved library, A to Z: no collection to start.
        activeSource.value = source(MediaId.PROVIDER_APPLE_MUSIC)

        container.playSongsList(songs, startIndex = 2)

        verify(exactly = 1) {
            manager.play(
                tracks = songs,
                startIndex = 2,
                source = activeSource.value!!,
                activityContext = ActivityContext.None,
                shuffled = false,
                explicitStart = true
            )
        }
    }

    @Test
    fun should_playNothing_when_noProfileIsActive() {
        activeSource.value = null

        container.playSongsList(songs, startIndex = 1)

        verify(exactly = 0) { manager.play(any(), any(), any(), any(), any(), any()) }
    }

    private fun source(providerId: String) = mockk<MusicSource> { every { id } returns providerId }

    private fun spotifySong(id: String) = song(id).copy(
        id = MediaId.spotify(id),
        coverArt = CoverRef.Url("https://i.scdn.co/image/$id")
    )

    private fun song(id: String) = Track(
        id = MediaId.subsonic(id),
        title = "Song $id",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = null,
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )
}
