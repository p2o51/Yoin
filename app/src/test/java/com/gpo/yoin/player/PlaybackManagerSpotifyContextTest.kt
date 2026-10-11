package com.gpo.yoin.player

import android.content.Context
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The "playing from" context a Yoin play sets survives what App Remote's PlayerContext
 * subscription reports afterwards, unless Spotify names a different album or playlist. The
 * start itself never runs here (the dispatcher isn't advanced): only play()'s synchronous
 * context write and the subscription's callback are exercised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackManagerSpotifyContextTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<YoinRepository>(relaxed = true).also {
        every { it.activeProviderId } returns MutableStateFlow(MediaId.PROVIDER_SPOTIFY)
        every { it.currentProviderId() } returns MediaId.PROVIDER_SPOTIFY
    }

    private val source = mockk<MusicSource>(relaxed = true).also {
        every { it.id } returns MediaId.PROVIDER_SPOTIFY
    }

    // Lazy: the manager's scope runs on Main, which the rule installs only once a test starts.
    private val manager by lazy {
        PlaybackManager(
            context = mockk<Context>(relaxed = true),
            repository = repository,
            castManager = null,
            spotifyClientIdProvider = { "test-client" }
        )
    }

    private val tracks = (1..5).map { track("t$it") }
    private val album = ActivityContext.Album(
        albumId = "spotify:al1",
        albumName = "TUNE & PLAY",
        artistName = "TUIDE",
        artistId = "spotify:ar1"
    )
    private val artist = ActivityContext.Artist(artistId = "spotify:ar1", artistName = "TUIDE")

    @After
    fun tearDown() {
        val field = PlaybackManager::class.java.getDeclaredField("scope")
        field.isAccessible = true
        (field.get(manager) as CoroutineScope).cancel()
    }

    @Test
    fun should_keepPlayingFromTheAlbum_when_spotifyReportsAnEmptyContextAfterTheStart() {
        manager.play(tracks, startIndex = 1, source = source, activityContext = album)

        reportContext(null)
        reportContext(SpotifyPlaybackContext(uri = "", title = ""))
        reportContext(SpotifyPlaybackContext(uri = " ", title = null))

        assertEquals(album, manager.currentActivityContext.value)
    }

    @Test
    fun should_keepPlayingFromTheAlbum_when_spotifyReportsThatAlbum() {
        manager.play(tracks, startIndex = 1, source = source, activityContext = album)

        reportContext(SpotifyPlaybackContext(uri = "spotify:album:al1", title = "TUNE & PLAY"))

        assertEquals(album, manager.currentActivityContext.value)
    }

    @Test
    fun should_keepPlayingFromTheArtist_when_spotifyReportsNothingItCanNameForTheArtistsList() {
        manager.play(tracks, startIndex = 0, source = source, activityContext = artist, explicitStart = false)

        reportContext(null)
        reportContext(SpotifyPlaybackContext(uri = "", title = ""))
        reportContext(SpotifyPlaybackContext(uri = "spotify:artist:ar1", title = "TUIDE"))

        assertEquals(artist, manager.currentActivityContext.value)
    }

    @Test
    fun should_followSpotify_when_itMovesToAnotherAlbumAfterAYoinStart() {
        manager.play(tracks, startIndex = 1, source = source, activityContext = album)

        reportContext(SpotifyPlaybackContext(uri = "spotify:album:al2", title = "Other"))
        val moved = ActivityContext.Album(albumId = "spotify:al2", albumName = "Other")
        assertEquals(moved, manager.currentActivityContext.value)

        // From here the context is Spotify's: an unnamed one clears it, as for adopted playback.
        reportContext(SpotifyPlaybackContext(uri = "", title = ""))
        assertEquals(ActivityContext.None, manager.currentActivityContext.value)
    }

    @Test
    fun should_labelAndThenClearSpotifysContext_when_noYoinPlayStartedIt() {
        reportContext(SpotifyPlaybackContext(uri = "spotify:playlist:pl1", title = "Mix"))
        val named = ActivityContext.Playlist(playlistId = "spotify:pl1", playlistName = "Mix")
        assertEquals(named, manager.currentActivityContext.value)

        reportContext(null)
        assertEquals(ActivityContext.None, manager.currentActivityContext.value)
    }

    /** What App Remote's PlayerContext subscription hands the manager. */
    private fun reportContext(context: SpotifyPlaybackContext?) {
        val method = PlaybackManager::class.java
            .getDeclaredMethod("onSpotifyPlayerContext", SpotifyPlaybackContext::class.java)
        method.isAccessible = true
        method.invoke(manager, context)
    }

    private fun track(rawId: String) = Track(
        id = MediaId.spotify(rawId),
        title = rawId,
        artist = "TUIDE",
        artistId = MediaId.spotify("ar1"),
        album = "TUNE & PLAY",
        albumId = MediaId.spotify("al1"),
        coverArt = null,
        durationSec = 180,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )
}
