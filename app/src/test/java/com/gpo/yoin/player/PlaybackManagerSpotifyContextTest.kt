package com.gpo.yoin.player

import android.content.Context
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaybackHandle
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The "playing from" context a Yoin play sets, against App Remote's PlayerState and PlayerContext
 * subscriptions in the order a cold connect delivers them: the start's coroutine runs (Spotify
 * hand-off pending, the mirrored queue's Connecting snapshot), then Spotify's state and context
 * for what it played BEFORE the start, then the start itself. App Remote never connects here (no
 * host), so every snapshot is handed to the manager as the subscription would.
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
        every { it.playback() } returns object : MusicPlayback {
            override suspend fun handleFor(track: Track): PlaybackHandle =
                PlaybackHandle.ExternalController(PlaybackHandle.ControllerType.SPOTIFY_APP_REMOTE, track.id)
        }
    }

    // Lazy: the manager's scope runs on Main, which the rule installs only once a test starts.
    private val manager by lazy {
        PlaybackManager(
            context = mockk<Context>(relaxed = true),
            repository = repository,
            castManager = null,
            spotifyClientIdProvider = { "test-client" },
            // runTest's clock: advanceTimeBy moves the unconfirmed start's wait along too.
            spotifyContextClock = { mainDispatcherRule.dispatcher.scheduler.currentTime }
        )
    }

    private val tracks = (1..5).map { track("t$it") }
    private val before = track("before")
    private val liked = track("liked")
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
    fun should_keepPlayingFromTheArtist_when_aColdConnectReportsAStaleAlbumBeforeTheUrisStart() = runTest {
        play(artist, startIndex = 0)

        // Spotify's first state and context are about what it played before the start…
        state(before)
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:old", title = "Old"))
        assertEquals(artist, manager.currentActivityContext.value)
        // …then the `uris` start lands, which names no context.
        state(tracks[0])
        reportContext(SpotifyPlaybackContext(uri = "", title = ""))

        assertEquals(artist, manager.currentActivityContext.value)
    }

    @Test
    fun should_keepPlayingFromTheAlbum_when_itsContextStartFellBackToUrisAfterAStaleContext() = runTest {
        play(album, startIndex = 1)

        state(before)
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:old", title = "Old"))
        state(tracks[1])
        reportContext(null)
        reportContext(SpotifyPlaybackContext(uri = " ", title = null))

        assertEquals(album, manager.currentActivityContext.value)
    }

    @Test
    fun should_keepTheRichAlbum_when_spotifyConfirmsTheAlbumContextStart() = runTest {
        play(album, startIndex = 1)

        state(tracks[1])
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:al1", title = ""))

        assertEquals(album, manager.currentActivityContext.value)
    }

    @Test
    fun should_bringTheArtistBack_when_theSongPlayingBeforeTheStartWasInItsQueue() = runTest {
        play(artist, startIndex = 0)

        // Spotify was already on one of the artist's songs, under one of the artist's albums.
        state(tracks[3])
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:al1", title = "TUNE & PLAY"))
        state(tracks[0])
        reportContext(SpotifyPlaybackContext(uri = "", title = ""))

        assertEquals(artist, manager.currentActivityContext.value)
    }

    @Test
    fun should_followSpotifyAndRecordThePlayAsNone_when_itMovesToLikedSongsAfterAYoinStart() = runTest {
        play(album, startIndex = 1)
        state(tracks[1])

        // Spotify plays Liked Songs, a context Yoin can't name.
        reportContext(SpotifyPlaybackContext(uri = "spotify:user:me:collection", title = "Liked Songs"))
        state(liked, isPlaying = true)
        runCurrent()

        assertEquals(ActivityContext.None, manager.currentActivityContext.value)
        coVerify(exactly = 1) { repository.recordPlay(liked, any(), any(), ActivityContext.None) }
        coVerify(exactly = 0) { repository.recordPlay(liked, any(), any(), album) }
    }

    @Test
    fun should_keepAndRecordLikedSongs_when_spotifyPlaysALikedSongOutsideYoinsWindow() = runTest {
        val likedSongs = ActivityContext.LikedSongs()
        play(likedSongs, startIndex = 1)
        state(tracks[1])
        reportContext(SpotifyPlaybackContext(uri = "spotify:user:me:collection", title = "Liked Songs"))

        // Shuffle: the next liked song is past the window of 100 Yoin handed Spotify.
        state(liked, isPlaying = true)
        runCurrent()

        assertEquals(likedSongs, manager.currentActivityContext.value)
        coVerify(exactly = 1) { repository.recordPlay(liked, any(), any(), likedSongs) }
    }

    @Test
    fun should_dropTheAlbumBeforeRecordingThePlay_when_spotifyMovedOnWhileYoinWasInTheBackground() = runTest {
        play(album, startIndex = 1)
        state(tracks[1])
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:al1", title = "TUNE & PLAY"))

        // Host stopped: App Remote disconnects. Back in the foreground, the first PlayerState
        // comes before the PlayerContext: the song waits for Spotify's fresh word.
        disconnected()
        state(liked, isPlaying = true)
        runCurrent()
        coVerify(exactly = 0) { repository.recordPlay(liked, any(), any(), any()) }

        val other = ActivityContext.Album(albumId = "spotify:al2", albumName = "Other")
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:al2", title = "Other"))
        runCurrent()
        assertEquals(other, manager.currentActivityContext.value)
        coVerify(exactly = 1) { repository.recordPlay(liked, any(), any(), other) }
        coVerify(exactly = 0) { repository.recordPlay(liked, any(), any(), album) }
    }

    @Test
    fun should_keepAndRecordTheRichPlaylist_when_spotifyStillPlaysItAfterAReconnect() = runTest {
        val playlist = ActivityContext.Playlist(
            playlistId = "spotify:pl1",
            playlistName = "Mix",
            owner = "me",
            coverArtId = "https://i.scdn.co/image/pl1"
        )
        play(playlist, startIndex = 1)
        state(tracks[1])
        reportContext(SpotifyPlaybackContext(uri = "spotify:playlist:pl1", title = "Mix"))

        // Shuffled on in the background past the 300 songs Yoin mirrors of the playlist.
        disconnected()
        state(liked, isPlaying = true)
        reportContext(SpotifyPlaybackContext(uri = "spotify:playlist:pl1", title = "Mix"))
        runCurrent()

        assertEquals(playlist, manager.currentActivityContext.value)
        coVerify(exactly = 1) { repository.recordPlay(liked, any(), any(), playlist) }
    }

    @Test
    fun should_handOverAndRecord_when_noContextComesWithinTheGraceAfterAReconnect() = runTest {
        play(album, startIndex = 1)
        state(tracks[1])

        disconnected()
        state(liked, isPlaying = true)
        advanceTimeBy(SPOTIFY_CONTEXT_REPORT_GRACE_MS - 1)
        runCurrent()
        assertEquals(album, manager.currentActivityContext.value)
        coVerify(exactly = 0) { repository.recordPlay(liked, any(), any(), any()) }

        advanceTimeBy(2)
        runCurrent()
        assertEquals(ActivityContext.None, manager.currentActivityContext.value)
        coVerify(exactly = 1) { repository.recordPlay(liked, any(), any(), ActivityContext.None) }
    }

    @Test
    fun should_keepAndRecordTheAlbum_when_spotifyPlaysARelinkedCopyInTheAlbumContext() = runTest {
        play(album, startIndex = 1)
        state(tracks[1])
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:al1", title = "TUNE & PLAY"))

        // Another market's copy of an album song: another id, Spotify still in the album.
        val relinked = track("t3-relinked").copy(title = "t3 (JP ver.)")
        state(relinked, isPlaying = true)
        runCurrent()

        assertEquals(album, manager.currentActivityContext.value)
        coVerify(exactly = 1) { repository.recordPlay(relinked, any(), any(), album) }
    }

    @Test
    fun should_followSpotify_when_spotifyPlaysAThirdSongOutsideYoinsQueueBeforeTheStartLands() = runTest {
        play(artist, startIndex = 0)
        state(before)
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:old", title = "Old"))
        state(track("before-2"))
        assertEquals(artist, manager.currentActivityContext.value)

        state(track("before-3"))

        assertEquals(
            ActivityContext.Album(albumId = "spotify:old", albumName = "Old"),
            manager.currentActivityContext.value
        )
    }

    @Test
    fun should_notRecordTheSongBeforeTheStart_when_aColdConnectReportsItPlaying() = runTest {
        play(album, startIndex = 1)

        // Spotify was playing a song of its own when the user tapped the album.
        state(before, isPlaying = true)
        runCurrent()
        coVerify(exactly = 0) { repository.recordPlay(before, any(), any(), any()) }

        state(tracks[1], isPlaying = true)
        runCurrent()
        coVerify(exactly = 1) { repository.recordPlay(tracks[1], any(), any(), album) }
        coVerify(exactly = 0) { repository.recordPlay(before, any(), any(), any()) }
    }

    @Test
    fun should_notRecordTheAlbumSongAboveTheTappedOne_when_spotifyStillPlaysItAsTheStartLands() = runTest {
        play(album, startIndex = 2)

        // Walking down the album: Spotify still plays the row above the one just tapped.
        state(tracks[1], isPlaying = true)
        runCurrent()
        assertEquals(album, manager.currentActivityContext.value)
        coVerify(exactly = 0) { repository.recordPlay(tracks[1], any(), any(), any()) }

        state(tracks[2], isPlaying = true)
        runCurrent()
        coVerify(exactly = 1) { repository.recordPlay(tracks[2], any(), any(), album) }
        coVerify(exactly = 0) { repository.recordPlay(tracks[1], any(), any(), any()) }
    }

    @Test
    fun should_recordUnderSpotifysContext_when_anUnconfirmedStartGivesWay() = runTest {
        play(artist, startIndex = 0)
        state(before, isPlaying = true)
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:old", title = "Old"))
        val next = track("before-2")
        state(next, isPlaying = true)
        val third = track("before-3")
        state(third, isPlaying = true)
        runCurrent()

        val old = ActivityContext.Album(albumId = "spotify:old", albumName = "Old")
        coVerify(exactly = 1) { repository.recordPlay(third, any(), any(), old) }
        coVerify(exactly = 0) { repository.recordPlay(before, any(), any(), any()) }
        coVerify(exactly = 0) { repository.recordPlay(next, any(), any(), any()) }
    }

    @Test
    fun should_followSpotifyAndRecordUnderItsContext_when_anUnconfirmedStartHasNotLandedByTheTimeout() = runTest {
        play(artist, startIndex = 0)
        // Spotify plays on, heard first now; the start never lands (or was overridden unheard).
        state(before, isPlaying = true)
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:old", title = "Old"))
        advanceTimeBy(SPOTIFY_START_CONFIRM_TIMEOUT_MS - 1)
        val next = track("before-2")
        state(next, isPlaying = true)
        runCurrent()
        assertEquals(artist, manager.currentActivityContext.value)
        coVerify(exactly = 0) { repository.recordPlay(next, any(), any(), any()) }

        // The same song reported again (a seek, a pause and resume): only the wait has run out.
        advanceTimeBy(1)
        state(next, isPlaying = true)
        runCurrent()

        val old = ActivityContext.Album(albumId = "spotify:old", albumName = "Old")
        assertEquals(old, manager.currentActivityContext.value)
        coVerify(exactly = 1) { repository.recordPlay(next, any(), any(), old) }
        coVerify(exactly = 0) { repository.recordPlay(next, any(), any(), artist) }
        coVerify(exactly = 0) { repository.recordPlay(before, any(), any(), any()) }
    }

    @Test
    fun should_waitForTheNextConnectionsWord_when_theConnectionDropsAgainWhileAHandOverWaits() = runTest {
        play(album, startIndex = 1)
        state(tracks[1])
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:al1", title = "TUNE & PLAY"))

        disconnected()
        state(liked, isPlaying = true)
        runCurrent()
        // Dropped again before Spotify said where it is: that connection's wait is off.
        disconnected()
        advanceTimeBy(2 * SPOTIFY_CONTEXT_REPORT_GRACE_MS)
        runCurrent()
        assertEquals(album, manager.currentActivityContext.value)
        coVerify(exactly = 0) { repository.recordPlay(liked, any(), any(), any()) }

        // The next connection asks again, and Spotify's word there decides.
        state(liked, isPlaying = true)
        runCurrent()
        assertEquals(album, manager.currentActivityContext.value)
        coVerify(exactly = 0) { repository.recordPlay(liked, any(), any(), any()) }
        val other = ActivityContext.Album(albumId = "spotify:al2", albumName = "Other")
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:al2", title = "Other"))
        runCurrent()

        assertEquals(other, manager.currentActivityContext.value)
        coVerify(exactly = 1) { repository.recordPlay(liked, any(), any(), other) }
        coVerify(exactly = 0) { repository.recordPlay(liked, any(), any(), album) }
    }

    @Test
    fun should_giveTheNextConnectionAFreshWait_when_theConnectionDropsAgainWhileAHandOverWaits() = runTest {
        play(album, startIndex = 1)
        state(tracks[1])

        disconnected()
        state(liked, isPlaying = true)
        advanceTimeBy(SPOTIFY_CONTEXT_REPORT_GRACE_MS - 1)
        runCurrent()
        disconnected()

        // Back on a new connection, still no word: the wait counts from this connection's song.
        state(liked, isPlaying = true)
        advanceTimeBy(SPOTIFY_CONTEXT_REPORT_GRACE_MS - 1)
        runCurrent()
        assertEquals(album, manager.currentActivityContext.value)
        coVerify(exactly = 0) { repository.recordPlay(liked, any(), any(), any()) }

        advanceTimeBy(2)
        runCurrent()
        assertEquals(ActivityContext.None, manager.currentActivityContext.value)
        coVerify(exactly = 1) { repository.recordPlay(liked, any(), any(), ActivityContext.None) }
    }

    @Test
    fun should_keepTheNewStartsContext_when_anEarlierHandOversWaitRunsOutAfterIt() = runTest {
        play(album, startIndex = 1)
        state(tracks[1])
        disconnected()
        state(liked, isPlaying = true)
        runCurrent()

        // A new Yoin play while the hand-over still waits for Spotify's word.
        play(artist, startIndex = 0)
        advanceTimeBy(2 * SPOTIFY_CONTEXT_REPORT_GRACE_MS)
        runCurrent()
        assertEquals(artist, manager.currentActivityContext.value)
        coVerify(exactly = 0) { repository.recordPlay(liked, any(), any(), any()) }

        state(tracks[0], isPlaying = true)
        runCurrent()
        assertEquals(artist, manager.currentActivityContext.value)
        coVerify(exactly = 1) { repository.recordPlay(tracks[0], any(), any(), artist) }
    }

    @Test
    fun should_followSpotify_when_aWarmConnectAdoptsPlaybackStartedOutsideYoin() = runTest {
        reportContext(SpotifyPlaybackContext(uri = "spotify:playlist:pl1", title = "Mix"))
        state(liked)
        assertEquals(
            ActivityContext.Playlist(playlistId = "spotify:pl1", playlistName = "Mix"),
            manager.currentActivityContext.value
        )

        reportContext(null)
        assertEquals(ActivityContext.None, manager.currentActivityContext.value)
    }

    @Test
    fun should_followSpotify_when_playbackIsAdoptedAfterAnEarlierYoinPlay() = runTest {
        // A Yoin play that never reached Spotify (its start never ran), then the account's
        // playback is adopted: the earlier play's context is no longer Yoin's to keep.
        manager.play(tracks, startIndex = 1, source = source, activityContext = album)
        reportContext(SpotifyPlaybackContext(uri = "spotify:album:al2", title = "Other"))
        state(liked)

        assertEquals(
            ActivityContext.Album(albumId = "spotify:al2", albumName = "Other"),
            manager.currentActivityContext.value
        )
        reportContext(SpotifyPlaybackContext(uri = "", title = ""))
        assertEquals(ActivityContext.None, manager.currentActivityContext.value)
    }

    /** A Yoin play, with its start coroutine run: Spotify hand-off pending, queue mirrored. */
    private fun TestScope.play(context: ActivityContext, startIndex: Int) {
        manager.play(tracks, startIndex = startIndex, source = source, activityContext = context)
        runCurrent()
    }

    /**
     * What App Remote's PlayerState subscription hands the manager while Yoin mirrors [tracks]:
     * a song outside them keeps the mirrored queue, at index 0 (SpotifyAppRemotePlayer.fromPlayerState).
     */
    private fun state(song: Track, isPlaying: Boolean = false) {
        val method = PlaybackManager::class.java
            .getDeclaredMethod("publishRemoteState", SpotifyRemoteSnapshot::class.java)
        method.isAccessible = true
        method.invoke(
            manager,
            SpotifyRemoteSnapshot(
                currentTrack = song,
                isPlaying = isPlaying,
                queue = tracks,
                currentIndex = tracks.indexOfFirst { it.id == song.id }.coerceAtLeast(0),
                connectionPhase = ConnectionPhase.Ready,
                observedPlayerState = true
            )
        )
    }

    /** App Remote disconnected (host stopped): the snapshot is kept, Idle. */
    private fun disconnected() {
        val method = PlaybackManager::class.java
            .getDeclaredMethod("publishRemoteState", SpotifyRemoteSnapshot::class.java)
        method.isAccessible = true
        val last = manager.playbackState.value
        method.invoke(
            manager,
            SpotifyRemoteSnapshot(
                currentTrack = last.currentTrack,
                queue = tracks,
                currentIndex = last.currentIndex,
                connectionPhase = ConnectionPhase.Idle,
                observedPlayerState = true
            )
        )
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
