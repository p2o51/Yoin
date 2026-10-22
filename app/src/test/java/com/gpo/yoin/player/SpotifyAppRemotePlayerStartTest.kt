package com.gpo.yoin.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.spotify.SpotifyPlayerQueue
import com.gpo.yoin.testutil.MainDispatcherRule
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.PlayerApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.SpotifyDisconnectedException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.ErrorCallback
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Artist
import com.spotify.protocol.types.Empty
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Requested-track confirmation, bounded fallback and cancellation across Spotify starts. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SpotifyAppRemotePlayerStartTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appRemote = FakeAppRemote()
    private val snapshots = mutableListOf<SpotifyRemoteSnapshot>()
    private val song = spotifyTrack("t1", title = "Burn Me Blue", artist = "Remi Wolf")
    private val list = listOf(song) + (2..4).map { spotifyTrack("t$it") }
    private var webStarts = 0
    private val players = mutableListOf<SpotifyAppRemotePlayer>()

    @After
    fun tearDown() {
        players.forEach { it.disconnect(resetState = true) }
    }

    @Test
    fun should_playTheSongOverAppRemote_when_spotifyAnswersTheWebApiStartButNeverPlaysIt() = runTest {
        val player = connectedPlayer()

        player.playQueue(listOf(song), startIndex = 0, startContextPlayback = { webStarts++ })
        runCurrent()
        // Answered, and then nothing: no word from Spotify yet, so App Remote waits.
        assertEquals(emptyList<String>(), appRemote.played)

        advanceTimeBy(SPOTIFY_START_HEARD_TIMEOUT_MS + 1)
        runCurrent()

        assertEquals(listOf("spotify:track:t1"), appRemote.played)
        assertEquals(1, webStarts)
    }

    @Test
    fun should_leaveAppRemoteAlone_when_spotifyIsHeardPlayingTheWebApiStart() = runTest {
        val player = connectedPlayer()

        player.playQueue(
            listOf(song),
            startIndex = 0,
            startContextPlayback = {
                webStarts++
                appRemote.report(playing(uri = "spotify:track:t1", name = "Burn Me Blue"))
            }
        )
        settle()

        assertEquals(emptyList<String>(), appRemote.played)
        assertTrue(snapshots.last().observedPlayerState)
    }

    @Test
    fun should_takeARelinkedCopyAsTheSong_when_spotifyPlaysItUnderAnotherId() = runTest {
        val player = connectedPlayer()

        player.playQueue(
            listOf(song),
            startIndex = 0,
            startContextPlayback = {
                appRemote.report(playing(uri = "spotify:track:other-market", name = "burn me blue"))
            }
        )
        settle()

        assertEquals(emptyList<String>(), appRemote.played)
    }

    @Test
    fun should_notTakeThePausedSongAsHeard_when_spotifyOnlyLoadsTheWebApiStart() = runTest {
        val player = connectedPlayer()

        player.playQueue(
            listOf(song),
            startIndex = 0,
            startContextPlayback = {
                appRemote.report(playing(uri = "spotify:track:t1", name = "Burn Me Blue", paused = true))
            }
        )
        settle()

        assertEquals(listOf("spotify:track:t1"), appRemote.played)
    }

    @Test
    fun should_queueNothing_when_anUnheardWebApiStartOfAListIsPlayedOverAppRemote() = runTest {
        val player = connectedPlayer()

        player.playQueue(
            list,
            startIndex = 0,
            startContextPlayback = {
                // Spotify goes on with what it played before the start.
                appRemote.report(playing(uri = "spotify:track:before", name = "Before"))
            }
        )
        settle()

        assertEquals(listOf("spotify:track:t1"), appRemote.played)
        assertEquals(emptyList<String>(), appRemote.queued)
    }

    @Test
    fun should_playOnlyTheRequestedTrack_when_spotifyRefusesTheWebApiStart() = runTest {
        val player = connectedPlayer()

        player.playQueue(list, startIndex = 0, startContextPlayback = { error("404 NO_ACTIVE_DEVICE") })
        runCurrent()

        assertEquals(listOf("spotify:track:t1"), appRemote.played)
        assertEquals(emptyList<String>(), appRemote.queued)
    }

    @Test
    fun should_showTheFailureWithoutDroppingTheTap_when_appRemotePlayFails() = runTest {
        val player = connectedPlayer()
        appRemote.playFailures += SpotifyDisconnectedException()

        player.playQueue(listOf(song), startIndex = 0, startContextPlayback = { webStarts++ })
        settle()

        assertEquals(listOf("spotify:track:t1"), appRemote.played)
        assertEquals(1, appRemote.connects)
        assertEquals(1, webStarts)
        assertEquals(ConnectionPhase.Error, snapshots.last().connectionPhase)
        assertEquals(song, snapshots.last().pendingTrack)
    }

    @Test
    fun should_showAnError_when_neitherStartProducesTheRequestedTrack() = runTest {
        val player = connectedPlayer()
        player.playQueue(listOf(song), startIndex = 0, startContextPlayback = { webStarts++ })
        runCurrent()
        advanceTimeBy(2 * SPOTIFY_START_HEARD_TIMEOUT_MS + 1)
        runCurrent()

        assertEquals(1, webStarts)
        assertEquals(listOf("spotify:track:t1"), appRemote.played)
        assertEquals(ConnectionPhase.Error, snapshots.last().connectionPhase)
        assertEquals(song, snapshots.last().pendingTrack)
        assertNull(snapshots.last().currentTrack)
    }

    @Test
    fun should_holdTheTappedSong_when_spotifyReportsThePreviousSongInTheAlbum() = runTest {
        val player = connectedPlayer()
        player.playQueue(list, startIndex = 2, startContextPlayback = {})
        runCurrent()
        appRemote.report(playing("spotify:track:t2", "t2"))
        runCurrent()

        assertEquals(list[2], snapshots.last().pendingTrack)
        assertNull(snapshots.last().currentTrack)
        assertFalse(snapshots.last().observedPlayerState)
        appRemote.report(playing("spotify:track:t3", "t3"))
        runCurrent()
        assertEquals(list[2], snapshots.last().currentTrack)
        assertEquals(2, snapshots.last().currentIndex)
        assertNull(snapshots.last().pendingTrack)
    }

    @Test
    fun should_keepTheRelinkedSongsIndex_when_theRequestedTrackHasAnotherMarketId() = runTest {
        val player = connectedPlayer()
        val queue = listOf(list[1], song)
        player.playQueue(queue, startIndex = 1, startContextPlayback = {})
        runCurrent()
        appRemote.report(playing("spotify:track:relinked", "Burn Me Blue"))
        runCurrent()

        assertEquals(MediaId.spotify("relinked"), snapshots.last().currentTrack?.id)
        assertEquals(1, snapshots.last().currentIndex)
        assertNull(snapshots.last().pendingTrack)
    }

    @Test
    fun should_ignoreTheEarlierTap_when_itsSongArrivesDuringTheNewStart() = runTest {
        val player = connectedPlayer()
        player.playQueue(list, startIndex = 1, startContextPlayback = {})
        runCurrent()
        player.playQueue(list, startIndex = 2, startContextPlayback = {})
        runCurrent()
        appRemote.report(playing("spotify:track:t2", "t2"))
        runCurrent()

        assertEquals(list[2], snapshots.last().pendingTrack)
        assertNull(snapshots.last().currentTrack)
        appRemote.report(playing("spotify:track:t3", "t3"))
        runCurrent()
        assertEquals(list[2], snapshots.last().currentTrack)
        assertEquals(emptyList<String>(), appRemote.played)
    }

    @Test
    fun should_sendTheNewStartLast_when_aCancelledHttpCallIsStillFinishing() = runTest {
        val player = connectedPlayer()
        val sent = mutableListOf<String>()
        player.playQueue(list, startIndex = 1, startContextPlayback = {
            withContext(NonCancellable) {
                delay(1_000L)
                sent += "old"
            }
        })
        runCurrent()
        player.playQueue(list, startIndex = 2, startContextPlayback = { sent += "new" })
        runCurrent()
        assertTrue(sent.isEmpty())
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(listOf("old", "new"), sent)
        assertEquals(emptyList<String>(), appRemote.played)
    }

    @Test
    fun should_allowAQuietRequestToWait_when_theRateLimitGateDelaysTheCommand() = runTest {
        val player = connectedPlayer()
        player.playQueue(list, startIndex = 1, startContextPlayback = { delay(20_000L) })
        runCurrent()
        advanceTimeBy(19_999L)
        runCurrent()
        assertEquals(list[1], snapshots.last().pendingTrack)
        assertEquals(emptyList<String>(), appRemote.played)
        assertFalse(snapshots.any { it.connectionPhase == ConnectionPhase.Error })
    }

    @Test
    fun should_waitForANewState_when_aColdConnectionReportsTheTargetBeforeThePlay() = runTest {
        appRemote.initialState = playing("spotify:track:t1", "Burn Me Blue")
        val player = player()
        player.onHostStart(context)
        player.playQueue(listOf(song), 0)
        runCurrent()

        assertEquals(listOf("spotify:track:t1"), appRemote.played)
        assertEquals(song, snapshots.last().pendingTrack)
        assertFalse(snapshots.last().observedPlayerState)
        appRemote.report(playing("spotify:track:t1", "Burn Me Blue"))
        runCurrent()
        assertEquals(song, snapshots.last().currentTrack)
    }

    @Test
    fun should_sendANewPlay_when_theUserTapsTheAlreadyPlayingSongAgain() = runTest {
        val player = connectedPlayer()
        player.playQueue(listOf(song), 0)
        runCurrent()
        appRemote.report(playing("spotify:track:t1", "Burn Me Blue"))
        runCurrent()
        player.playQueue(listOf(song), 0)
        runCurrent()

        assertEquals(listOf("spotify:track:t1", "spotify:track:t1"), appRemote.played)
        assertEquals(song, snapshots.last().pendingTrack)
        appRemote.report(playing("spotify:track:t1", "Burn Me Blue"))
        runCurrent()
        assertEquals(song, snapshots.last().currentTrack)
    }

    @Test
    fun should_keepPending_when_aPausedTargetArrivesBeforeItStartsPlaying() = runTest {
        val player = connectedPlayer()
        player.playQueue(listOf(song), 0)
        runCurrent()
        appRemote.report(playing("spotify:track:t1", "Burn Me Blue", paused = true))
        runCurrent()

        assertEquals(song, snapshots.last().pendingTrack)
        assertNull(snapshots.last().currentTrack)
        assertFalse(snapshots.last().observedPlayerState)
        appRemote.report(playing("spotify:track:t1", "Burn Me Blue"))
        runCurrent()
        assertEquals(song, snapshots.last().currentTrack)
    }

    @Test
    fun should_observeWithoutReplaying_when_theHostStopsBeforeConfirmation() = runTest {
        val player = connectedPlayer()
        player.playQueue(listOf(song), startIndex = 0, startContextPlayback = {})
        runCurrent()
        player.onHostStop()
        advanceTimeBy(SPOTIFY_START_HEARD_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(emptyList<String>(), appRemote.played)
        player.onHostStart(context)
        runCurrent()
        appRemote.report(playing("spotify:track:t1", "Burn Me Blue"))
        runCurrent()
        assertEquals(song, snapshots.last().currentTrack)
        assertEquals(emptyList<String>(), appRemote.played)
    }

    @Test
    fun should_endThePendingStart_when_repeatedHostStopsInterruptConfirmation() = runTest {
        val player = connectedPlayer()
        player.playQueue(listOf(song), 0, startContextPlayback = {})
        runCurrent()
        repeat(2) {
            player.onHostStop()
            advanceTimeBy(SPOTIFY_START_HEARD_TIMEOUT_MS + 1)
            runCurrent()
            player.onHostStart(context)
            runCurrent()
        }
        advanceTimeBy(SPOTIFY_START_HEARD_TIMEOUT_MS + 1)
        runCurrent()

        assertEquals(ConnectionPhase.Error, snapshots.last().connectionPhase)
        assertEquals(emptyList<String>(), appRemote.played)
    }

    @Test
    fun should_showTheFailure_when_appRemotesPlayFailsBeforeSpotifyWasEverHeard() = runTest {
        val player = connectedPlayer()
        appRemote.playFailures += NotLoggedInException("not logged in", null)

        player.playQueue(listOf(song), startIndex = 0, startContextPlayback = { error("404 NO_ACTIVE_DEVICE") })
        settle()

        assertEquals(1, appRemote.played.size)
        val last = snapshots.last()
        assertEquals(ConnectionPhase.Error, last.connectionPhase)
        assertEquals(song, last.pendingTrack)
    }

    @Test
    fun should_hearOnlyAPlayingSongOfTheStart_when_matchingSpotifysState() {
        assertTrue(spotifyPlays(playing(uri = "spotify:track:t1", name = "x"), song))
        assertTrue(spotifyPlays(playing(uri = "spotify:track:relinked", name = "BURN ME BLUE"), song))
        assertFalse(spotifyPlays(playing(uri = "spotify:track:t1", name = "x", paused = true), song))
        val namesake = playing(uri = "spotify:track:relinked", name = "Burn Me Blue", artist = "Someone")
        assertFalse(spotifyPlays(namesake, song))
        assertFalse(spotifyPlays(PlayerState(null, false, 1f, 0L, null, null), song))
    }

    @Test
    fun should_showTheStartsPlayMode_when_spotifysFirstStateStillHasTheOldOne() = runTest {
        val player = connectedPlayer()

        player.playQueue(
            list,
            startIndex = 0,
            startContextPlayback = { appRemote.report(playing("spotify:track:t1", "Burn Me Blue")) },
            playMode = PlayMode.Shuffle
        )
        runCurrent()

        // Spotify's state says shuffle off: the write is still on its way.
        assertTrue(snapshots.last().observedPlayerState)
        assertTrue(snapshots.last().shuffleEnabled)
    }

    @Test
    fun should_keepTheRestOfTheList_when_spotifyPlaysASongAddedToTheQueue() = runTest {
        val player = connectedPlayer()
        val queued = spotifyTrack("q", title = "Queued")
        player.playQueue(list, startIndex = 0, startContextPlayback = {})
        runCurrent()
        appRemote.report(playing("spotify:track:t1", "Burn Me Blue"))
        runCurrent()

        player.addToQueue(queued)
        runCurrent()
        assertEquals(listOf("spotify:track:q"), appRemote.queued)
        assertEquals(setOf(1), snapshots.last().userQueued)
        appRemote.report(playing("spotify:track:q", "Queued"))
        runCurrent()

        assertEquals(1, snapshots.last().currentIndex)
        assertEquals(listOf(2, 3, 4), snapshots.last().upcoming)
    }

    @Test
    fun should_showTheSongPlaying_when_spotifyPlaysOneOutsideTheQueue() = runTest {
        val player = connectedPlayer()
        player.playQueue(list, startIndex = 1, startContextPlayback = {})
        runCurrent()
        appRemote.report(playing("spotify:track:t2", "t2"))
        runCurrent()

        appRemote.report(playing("spotify:track:elsewhere", "Elsewhere"))
        runCurrent()

        val shown = snapshots.last()
        assertEquals(MediaId.spotify("elsewhere"), shown.queue[shown.currentIndex].id)
        assertEquals(listOf(3, 4), shown.upcoming)
        assertEquals(list, shown.yoinQueue)
    }

    @Test
    fun should_startTheAlbumAtTheSongOverAppRemote_when_theWebApiCannotStartIt() = runTest {
        val player = connectedPlayer()

        player.playQueue(
            list,
            startIndex = 2,
            startContextPlayback = { error("This device is not in Spotify Connect") },
            remoteContextStart = { SpotifyRemoteContextStart("spotify:album:al1", 2) },
        )
        settle()

        // It used to play the song alone, and Spotify went on with something else after it.
        assertEquals(listOf("spotify:album:al1@2"), appRemote.startedAt)
        assertEquals(emptyList<String>(), appRemote.played)
    }

    @Test
    fun should_playTheSongAlone_when_itsPlaceInTheAlbumIsUnknown() = runTest {
        val player = connectedPlayer()

        player.playQueue(
            list,
            startIndex = 0,
            startContextPlayback = { error("This device is not in Spotify Connect") },
            remoteContextStart = { null },
        )
        settle()

        assertEquals(emptyList<String>(), appRemote.startedAt)
        assertEquals(listOf("spotify:track:t1"), appRemote.played)
    }

    @Test
    fun should_showWhatSpotifyHadQueuedBeforeTheSongAdded_when_itsQueueIsRead() = runTest {
        val before = spotifyTrack("x1")
        val queued = spotifyTrack("q")
        val player = connectedPlayer(
            queueReader = { SpotifyPlayerQueue(playing = song, next = listOf(before, queued) + list.drop(1)) }
        )
        player.playQueue(list, startIndex = 0, startContextPlayback = {
            appRemote.report(playing("spotify:track:t1", "Burn Me Blue"))
        })
        runCurrent()

        player.addToQueue(queued)
        advanceTimeBy(SPOTIFY_QUEUE_READ_DELAY_MS + 1)
        runCurrent()

        val shown = snapshots.last()
        assertEquals(listOf(song, before, queued) + list.drop(1), shown.queue)
        assertEquals(setOf(1, 2), shown.userQueued)
        assertEquals(listOf(1, 2, 3, 4, 5), shown.upcoming)
    }

    @Test
    fun should_keepTheMirror_when_spotifyHasMovedOnBeforeItsQueueIsRead() = runTest {
        val elsewhere = spotifyTrack("elsewhere")
        val player = connectedPlayer(
            queueReader = { SpotifyPlayerQueue(playing = elsewhere, next = listOf(spotifyTrack("x1"))) }
        )
        player.playQueue(list, startIndex = 0, startContextPlayback = {
            appRemote.report(playing("spotify:track:t1", "Burn Me Blue"))
        })
        advanceTimeBy(SPOTIFY_QUEUE_READ_DELAY_MS + 1)
        runCurrent()

        assertEquals(list, snapshots.last().queue)
    }

    @Test
    fun should_listWhatSpotifyPlaysNext_when_theSongWasPickedInTheSpotifyApp() = runTest {
        val next = (2..4).map { spotifyTrack("t$it") }
        var reads = 0
        connectedPlayer(queueReader = {
            reads++
            if (reads == 1) {
                SpotifyPlayerQueue(playing = song, next = next)
            } else {
                SpotifyPlayerQueue(playing = next[0], next = next.drop(1))
            }
        })

        appRemote.report(playing("spotify:track:t1", "Burn Me Blue"))
        advanceTimeBy(SPOTIFY_QUEUE_READ_DELAY_MS + 1)
        runCurrent()

        // It used to be the song alone (owner device QA 2026-10-22).
        val shown = snapshots.last()
        assertEquals(listOf(MediaId.spotify("t1")) + next.map(Track::id), shown.queue.map(Track::id))
        assertEquals(0, shown.currentIndex)
        assertEquals(listOf(1, 2, 3), shown.upcoming)

        appRemote.report(playing("spotify:track:t2", "t2"))
        advanceTimeBy(SPOTIFY_QUEUE_READ_DELAY_MS + 1)
        runCurrent()

        assertEquals(2, reads)
        assertEquals(next.map(Track::id), snapshots.last().queue.map(Track::id))
        assertEquals(listOf(1, 2), snapshots.last().upcoming)
    }

    @Test
    fun should_readSpotifysQueueAgain_when_theFirstReadFailed() = runTest {
        val next = (2..4).map { spotifyTrack("t$it") }
        var reads = 0
        connectedPlayer(queueReader = {
            reads++
            if (reads == 1) error("Spotify answered 503") else SpotifyPlayerQueue(playing = song, next = next)
        })
        appRemote.report(playing("spotify:track:t1", "Burn Me Blue"))
        advanceTimeBy(SPOTIFY_QUEUE_READ_DELAY_MS + 1)
        runCurrent()

        appRemote.report(playing("spotify:track:t1", "Burn Me Blue", paused = true))
        advanceTimeBy(SPOTIFY_QUEUE_READ_DELAY_MS + 1)
        runCurrent()

        assertEquals(2, reads)
        assertEquals(listOf(MediaId.spotify("t1")) + next.map(Track::id), snapshots.last().queue.map(Track::id))
    }

    @Test
    fun should_listSpotifysQueue_when_theUserMovedOnInTheSpotifyApp() = runTest {
        val elsewhere = spotifyTrack("elsewhere")
        val after = spotifyTrack("after")
        val player = connectedPlayer(queueReader = { SpotifyPlayerQueue(playing = elsewhere, next = listOf(after)) })
        player.playQueue(list, startIndex = 0, startContextPlayback = {
            appRemote.report(playing("spotify:track:t1", "Burn Me Blue"))
        })
        advanceTimeBy(SPOTIFY_QUEUE_READ_DELAY_MS + 1)
        runCurrent()
        appRemote.report(playing("spotify:track:elsewhere", "elsewhere"))
        runCurrent()

        player.followSpotifyQueue(takeOver = true)
        advanceTimeBy(SPOTIFY_QUEUE_READ_DELAY_MS + 1)
        runCurrent()

        assertEquals(listOf(MediaId.spotify("elsewhere"), after.id), snapshots.last().queue.map(Track::id))
    }

    @Test
    fun should_keepYoinsQueue_when_spotifyStillPlaysIt() = runTest {
        var reads = 0
        val player = connectedPlayer(queueReader = {
            reads++
            null
        })
        player.playQueue(list, startIndex = 0, startContextPlayback = {
            appRemote.report(playing("spotify:track:t1", "Burn Me Blue"))
        })
        advanceTimeBy(SPOTIFY_QUEUE_READ_DELAY_MS + 1)
        runCurrent()
        val readsAfterStart = reads

        player.followSpotifyQueue(takeOver = true)
        appRemote.report(playing("spotify:track:t2", "t2"))
        advanceTimeBy(SPOTIFY_QUEUE_READ_DELAY_MS + 1)
        runCurrent()

        assertEquals(readsAfterStart, reads)
        assertEquals(list, snapshots.last().queue)
    }

    /** Runs the start through the heard window and a transport retry's reconnect. */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(SPOTIFY_START_HEARD_TIMEOUT_MS + 2_000L)
        runCurrent()
    }

    private fun TestScope.connectedPlayer(
        queueReader: suspend () -> SpotifyPlayerQueue? = { null }
    ): SpotifyAppRemotePlayer {
        val player = player(queueReader)
        player.onHostStart(context)
        player.warmConnection()
        runCurrent()
        return player
    }

    private fun player(queueReader: suspend () -> SpotifyPlayerQueue? = { null }) = SpotifyAppRemotePlayer(
        applicationContext = context,
        clientIdProvider = { "client-id" },
        onSnapshot = { snapshot -> snapshots += snapshot },
        appRemote = appRemote,
        queueReader = queueReader
    ).also(players::add)

    private fun playing(uri: String, name: String, artist: String = "Remi Wolf", paused: Boolean = false) = PlayerState(
        com.spotify.protocol.types.Track(
            Artist(artist, "spotify:artist:a"),
            listOf(Artist(artist, "spotify:artist:a")),
            null,
            200_000L,
            name,
            uri,
            null,
            false,
            false
        ),
        paused,
        1f,
        0L,
        null,
        null
    )

    private fun spotifyTrack(rawId: String, title: String = rawId, artist: String? = null) = Track(
        id = MediaId.spotify(rawId),
        title = title,
        artist = artist,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )

    /**
     * App Remote as the player reaches it: connects at once, keeps each
     * PlayerState subscription's callback (newest last, [report]), and answers `play`
     * (failing with [playFailures] first, in order) and `queue` at once,
     * recording their uris.
     */
    private class FakeAppRemote : AppRemoteConnector {
        var connects = 0
        var initialState: PlayerState? = null
        val playerStates = mutableListOf<Subscription.EventCallback<PlayerState>>()
        private val playerContexts = mutableListOf<Subscription.EventCallback<PlayerContext>>()
        val played = mutableListOf<String>()
        val startedAt = mutableListOf<String>()
        val queued = mutableListOf<String>()
        val playFailures = ArrayDeque<Throwable>()
        private val remote = mockk<SpotifyAppRemote>(relaxed = true)
        private val playerApi = mockk<PlayerApi>(relaxed = true)

        init {
            every { remote.isConnected } returns true
            every { remote.playerApi } returns playerApi
            every { playerApi.subscribeToPlayerState() } answers { subscription(playerStates, initialState) }
            every { playerApi.subscribeToPlayerContext() } answers { subscription(playerContexts) }
            every { playerApi.play(any<String>()) } answers {
                played += firstArg<String>()
                answered(playFailures.removeFirstOrNull())
            }
            every { playerApi.skipToIndex(any<String>(), any<Int>()) } answers {
                startedAt += "${firstArg<String>()}@${secondArg<Int>()}"
                answered(null)
            }
            every { playerApi.queue(any<String>()) } answers {
                queued += firstArg<String>()
                answered(null)
            }
        }

        override fun connect(context: Context, params: ConnectionParams, listener: Connector.ConnectionListener) {
            connects++
            listener.onConnected(remote)
        }

        override fun disconnect(remote: SpotifyAppRemote) = Unit

        /** Spotify reports [state] on the newest PlayerState subscription. */
        fun report(state: PlayerState) = playerStates.last().onEvent(state)

        /** A call Spotify answers at once: done, or [failure]. */
        private fun answered(failure: Throwable?): CallResult<Empty> {
            val result = mockk<CallResult<Empty>>(relaxed = true)
            every { result.setResultCallback(any()) } answers {
                if (failure == null) firstArg<CallResult.ResultCallback<Empty>>().onResult(Empty())
                result
            }
            every { result.setErrorCallback(any()) } answers {
                if (failure != null) firstArg<ErrorCallback>().onError(failure)
                result
            }
            return result
        }

        private fun <T> subscription(
            callbacks: MutableList<Subscription.EventCallback<T>>,
            initial: T? = null
        ): Subscription<T> {
            val subscription = mockk<Subscription<T>>(relaxed = true)
            every { subscription.setEventCallback(any()) } answers {
                val callback = firstArg<Subscription.EventCallback<T>>()
                callbacks += callback
                initial?.let(callback::onEvent)
                subscription
            }
            every { subscription.setLifecycleCallback(any()) } returns subscription
            return subscription
        }
    }
}
