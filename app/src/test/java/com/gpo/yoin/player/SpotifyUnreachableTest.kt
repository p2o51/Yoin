package com.gpo.yoin.player

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.spotify.SpotifyConnectCommand
import com.gpo.yoin.data.source.spotify.SpotifyConnectState
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A start the Spotify app doesn't answer: a hint within a few seconds (asleep here, or
 * playing on another device), and a held start that never cuts into what Spotify plays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SpotifyUnreachableTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appRemote = WakeableAppRemote()
    private val snapshots = mutableListOf<SpotifyRemoteSnapshot>()
    private val song = spotifyTrack("t1")
    private val web = FakeConnectWeb()
    private var webStarts = 0
    private val startedOn = mutableListOf<String?>()
    private val players = mutableListOf<SpotifyAppRemotePlayer>()
    private var spotifyOpenable = true
    private var spotifyLaunches = 0

    @After
    fun tearDown() {
        players.forEach { it.disconnect(resetState = true) }
    }

    /**
     * [runTest] that lets the players go before it drains the scheduler: following another
     * device polls for as long as a Yoin screen is up, which a drain would never see the end of.
     */
    private fun playerTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            players.forEach { it.disconnect(resetState = true) }
        }
    }

    @Test
    fun should_sayItIsAsleep_when_spotifyDoesntAnswerTheStartInTime() = playerTest {
        val player = hostedPlayer()

        tap(player)
        advanceTimeBy(SPOTIFY_UNREACHABLE_AFTER_MS - 100)
        runCurrent()
        assertEquals(ConnectionPhase.Connecting, snapshots.last().connectionPhase)

        advanceTimeBy(200)
        runCurrent()

        assertEquals(SpotifyConnectFailure.SpotifyAsleep, snapshots.last().connectionFailure)
        assertEquals(ConnectionPhase.Error, snapshots.last().connectionPhase)
        assertEquals(song, snapshots.last().pendingTrack)
        assertTrue(web.reads > 0)
        assertEquals(0, webStarts)
    }

    @Test
    fun should_startOnTheOtherDevice_when_spotifyPlaysThereAndTheAppHereIsAsleep() = playerTest {
        val player = hostedPlayer()
        web.state = speaker(uri = "spotify:track:theirs")

        tap(player)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf<String?>("speaker"), startedOn)
        assertEquals(song, snapshots.last().currentTrack)
        assertEquals("Living Room", snapshots.last().playingOn)
        assertNull(snapshots.last().connectionFailure)
    }

    @Test
    fun should_driveTheOtherDevice_when_theUserPausesThere() = playerTest {
        val player = hostedPlayer()
        web.state = speaker(uri = "spotify:track:theirs")
        tap(player)
        advanceTimeBy(2_000)
        runCurrent()

        player.pause()
        player.skipNext()
        runCurrent()

        assertEquals(
            listOf(
                SpotifyConnectCommand.Pause to "speaker",
                SpotifyConnectCommand.Next to "speaker",
            ),
            web.commands,
        )
        assertEquals(false, snapshots.any { it.connectionPhase == ConnectionPhase.Error })
    }

    @Test
    fun should_showTheOtherDevicesSong_when_yoinOpensWithoutTheAppHere() = playerTest {
        val player = hostedPlayer()
        web.state = speaker(uri = "spotify:track:theirs")

        player.warmConnection()
        runCurrent()

        assertEquals(MediaId.spotify("theirs"), snapshots.last().currentTrack?.id)
        assertEquals("Living Room", snapshots.last().playingOn)
        assertTrue(snapshots.last().isPlaying)
        assertEquals(0, webStarts)
        assertTrue(web.commands.isEmpty())
    }

    @Test
    fun should_startOnTheOtherDevice_when_appRemoteIsConnectedAndASpeakerPlays() = playerTest {
        appRemote.answers = true
        val player = hostedPlayer()
        player.warmConnection()
        runCurrent()
        web.state = speaker(uri = "spotify:track:theirs")

        tap(player)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf<String?>("speaker"), startedOn)
        assertEquals("Living Room", snapshots.last().playingOn)
    }

    @Test
    fun should_startOnThisPhone_when_nothingPlaysElsewhere() = playerTest {
        appRemote.answers = true
        val player = hostedPlayer()
        player.warmConnection()
        runCurrent()
        web.state = speaker(uri = "spotify:track:theirs").copy(isPlaying = false)

        tap(player)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf<String?>(null), startedOn)
        assertNull(snapshots.last().playingOn)
    }

    @Test
    fun should_stopNamingTheOtherDevice_when_spotifyMovedHereWhileYoinWasAway() = playerTest {
        appRemote.answers = true
        val player = hostedPlayer()
        player.warmConnection()
        runCurrent()
        web.state = speaker(uri = "spotify:track:t1")
        tap(player)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("Living Room", snapshots.last().playingOn)

        // Away from Yoin, Spotify moves to this phone and carries on with the same song.
        player.onHostStop()
        runCurrent()
        web.state = speaker(uri = "spotify:track:t1").copy(deviceId = "phone", deviceName = "Z Fold8", isThisDevice = true)
        appRemote.initialState = state(paused = false)
        player.onHostStart(context)
        advanceTimeBy(1_000)
        runCurrent()

        // 51, 2026-11-07: the chip still said the computer.
        assertNull(snapshots.last().playingOn)
    }

    @Test
    fun should_notKeepAStaleDevice_when_spotifyConnectCantBeReadOnReturn() = playerTest {
        appRemote.answers = true
        val player = hostedPlayer()
        player.warmConnection()
        runCurrent()
        web.state = speaker(uri = "spotify:track:t1")
        tap(player)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("Living Room", snapshots.last().playingOn)

        player.onHostStop()
        runCurrent()
        web.state = null
        appRemote.initialState = state(paused = false)
        player.onHostStart(context)
        advanceTimeBy(5_000)
        runCurrent()

        assertNull(snapshots.last().playingOn)
    }

    @Test
    fun should_hintAfterTheSilentRetry_when_theConnectFailsAtOnce() = playerTest {
        appRemote.failWith = SpotifyDisconnectedException()
        val player = hostedPlayer()

        tap(player)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, appRemote.connects)
        assertEquals(SpotifyConnectFailure.SpotifyAsleep, snapshots.last().connectionFailure)
    }

    @Test
    fun should_playTheHeldSong_when_theUserOpenedSpotifyAndItIsPaused() = playerTest {
        val player = hintShown()

        assertTrue(player.openSpotify())
        comeBack(player, spotifyState = state(paused = true))

        assertEquals(1, webStarts)
        assertEquals(song, snapshots.last().currentTrack)
    }

    @Test
    fun should_notCutIn_when_spotifyIsAlreadyPlayingOnTheWayBack() = playerTest {
        val player = hintShown()

        player.openSpotify()
        comeBack(player, spotifyState = state(uri = "spotify:track:theirs", paused = false))

        assertEquals(0, webStarts)
        assertNull(snapshots.last().pendingTrack)
        assertEquals(MediaId.spotify("theirs"), snapshots.last().currentTrack?.id)
    }

    @Test
    fun should_openSpotifyItself_when_nothingPlaysAnywhere() = playerTest {
        val player = hintShown()

        assertEquals(1, spotifyLaunches)
        comeBack(player, spotifyState = state(paused = true))

        assertEquals(1, webStarts)
        assertEquals(song, snapshots.last().currentTrack)
    }

    @Test
    fun should_openSpotifyOnlyOncePerTap_when_itStillDoesntAnswerOnTheWayBack() = playerTest {
        val player = hintShown()

        player.onHostStop()
        runCurrent()
        player.onHostStart(context)
        advanceTimeBy(SPOTIFY_UNREACHABLE_AFTER_MS + 100)
        runCurrent()

        assertEquals(1, spotifyLaunches)
        assertEquals(SpotifyConnectFailure.SpotifyAsleep, snapshots.last().connectionFailure)
    }

    @Test
    fun should_dropTheHeldSong_when_spotifyCouldntBeOpenedAndTheHintWasDismissed() = playerTest {
        spotifyOpenable = false
        val player = hintShown()

        comeBack(player, spotifyState = state(paused = true))

        assertEquals(0, webStarts)
        assertNull(snapshots.last().pendingTrack)
    }

    @Test
    fun should_tryAgainFromScratch_when_theUserTapsAnotherSongAfterTheHint() = playerTest {
        val player = hintShown()
        appRemote.answers = true

        tap(player)
        runCurrent()

        assertEquals(1, webStarts)
    }

    @Test
    fun should_keepTheTypedFailure_when_theConnectSaysWhatIsWrong() = playerTest {
        appRemote.failWith = NotLoggedInException("not logged in", null)
        val player = hostedPlayer()

        tap(player)
        advanceTimeBy(SPOTIFY_UNREACHABLE_AFTER_MS * 2)
        runCurrent()

        assertTrue(snapshots.last().connectionFailure is SpotifyConnectFailure.AuthFailure)
    }

    @Test
    fun should_playTheNewTap_when_itComesInWhileTheHeldStartIsLookedAt() = playerTest {
        val player = hintShown()
        player.openSpotify()
        player.onHostStop()
        runCurrent()
        appRemote.answers = true
        appRemote.failWith = null
        appRemote.holdStateRead = true
        appRemote.initialState = state(uri = "spotify:track:theirs", paused = false)
        player.onHostStart(context)
        runCurrent()

        // The look at Spotify's state is still out when the user taps again.
        tap(player)
        appRemote.answerStateRead()
        advanceTimeBy(SPOTIFY_START_HEARD_TIMEOUT_MS + 2_000)
        runCurrent()

        assertEquals(1, webStarts)
    }

    @Test
    fun should_lookAgain_when_yoinLeavesDuringTheLook() = playerTest {
        val player = hintShown()
        player.openSpotify()
        player.onHostStop()
        runCurrent()
        appRemote.answers = true
        appRemote.failWith = null
        appRemote.holdStateRead = true
        appRemote.initialState = state(uri = "spotify:track:theirs", paused = false)
        player.onHostStart(context)
        runCurrent()

        // Gone again before Spotify answers the look; back with Spotify still playing its own song.
        appRemote.holdStateRead = false
        comeBack(player, spotifyState = state(uri = "spotify:track:theirs", paused = false))

        assertEquals(0, webStarts)
    }

    /** Taps [song] with the Spotify app asleep, and lets the hint come up. */
    private fun TestScope.hintShown(): SpotifyAppRemotePlayer {
        val player = hostedPlayer()
        tap(player)
        advanceTimeBy(SPOTIFY_UNREACHABLE_AFTER_MS + 100)
        runCurrent()
        assertTrue(snapshots.last().connectionPhase == ConnectionPhase.Error)
        return player
    }

    /** Yoin goes to the background and back, with the Spotify app now awake in [spotifyState]. */
    private fun TestScope.comeBack(player: SpotifyAppRemotePlayer, spotifyState: PlayerState) {
        player.onHostStop()
        runCurrent()
        appRemote.answers = true
        appRemote.failWith = null
        appRemote.initialState = spotifyState
        player.onHostStart(context)
        runCurrent()
        advanceTimeBy(SPOTIFY_START_HEARD_TIMEOUT_MS + 2_000)
        runCurrent()
    }

    private fun TestScope.tap(player: SpotifyAppRemotePlayer) {
        player.playQueue(
            listOf(song),
            startIndex = 0,
            startContextPlayback = { deviceId ->
                webStarts++
                startedOn += deviceId
                if (deviceId != null) web.state = speaker(uri = "spotify:track:t1")
                appRemote.report(state(paused = false))
            },
        )
        runCurrent()
    }

    private fun hostedPlayer(): SpotifyAppRemotePlayer = SpotifyAppRemotePlayer(
        applicationContext = context,
        clientIdProvider = { "client-id" },
        onSnapshot = { snapshot -> snapshots += snapshot },
        appRemote = appRemote,
        connectWeb = web,
        spotifyLaunchIntent = {
            if (spotifyOpenable) {
                spotifyLaunches++
                Intent(Intent.ACTION_MAIN).setPackage("com.spotify.music")
            } else {
                null
            }
        },
    ).also {
        players += it
        it.onHostStart(context)
    }

    private fun state(uri: String = "spotify:track:t1", paused: Boolean) = PlayerState(
        com.spotify.protocol.types.Track(
            Artist("Remi Wolf", "spotify:artist:a"),
            listOf(Artist("Remi Wolf", "spotify:artist:a")),
            null,
            200_000L,
            uri.substringAfterLast(':'),
            uri,
            null,
            false,
            false,
        ),
        paused,
        1f,
        0L,
        null,
        null,
    )

    private fun spotifyTrack(rawId: String) = Track(
        id = MediaId.spotify(rawId),
        title = rawId,
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )

    /** Spotify playing [uri] on the living-room speaker, as the Web API reports it. */
    private fun speaker(uri: String) = SpotifyConnectState(
        deviceId = "speaker",
        deviceName = "Living Room",
        isThisDevice = false,
        isPlaying = true,
        track = spotifyTrack(uri.substringAfterLast(':')),
        progressMs = 1_000L,
        shuffle = false,
        repeatState = "context",
    )

    /** Spotify Connect through the Web API: [state] is what it reports; commands are recorded. */
    private class FakeConnectWeb : SpotifyConnectWeb {
        var state: SpotifyConnectState? = null
        var reads = 0
        val commands = mutableListOf<Pair<SpotifyConnectCommand, String?>>()

        override suspend fun state(): SpotifyConnectState? {
            reads++
            return state
        }

        override suspend fun command(command: SpotifyConnectCommand, deviceId: String?) {
            commands += command to deviceId
        }
    }

    /**
     * App Remote with a Spotify app that may be asleep: while [answers] is false a connect
     * never hears back (or fails with [failWith]); once true it connects, and Spotify's state
     * is [initialState], both on subscribing and on a direct read.
     */
    private class WakeableAppRemote : AppRemoteConnector {
        var answers = false
        var failWith: Throwable? = null
        var connects = 0
        var initialState: PlayerState? = null

        /** A direct state read waits for [answerStateRead] instead of answering at once. */
        var holdStateRead = false
        private var heldRead: (() -> Unit)? = null
        private val playerStates = mutableListOf<Subscription.EventCallback<PlayerState>>()
        private val remote = mockk<SpotifyAppRemote>(relaxed = true)
        private val playerApi = mockk<PlayerApi>(relaxed = true)

        init {
            every { remote.isConnected } returns true
            every { remote.playerApi } returns playerApi
            every { playerApi.subscribeToPlayerState() } answers { subscription(playerStates, initialState) }
            every { playerApi.subscribeToPlayerContext() } answers { subscription(mutableListOf<Subscription.EventCallback<PlayerContext>>()) }
            every { playerApi.playerState } answers { stateRead() }
            every { playerApi.play(any<String>()) } answers { answered(Empty()) }
        }

        override fun connect(context: Context, params: ConnectionParams, listener: Connector.ConnectionListener) {
            connects++
            when {
                answers -> listener.onConnected(remote)
                failWith != null -> listener.onFailure(failWith)
                else -> Unit
            }
        }

        override fun disconnect(remote: SpotifyAppRemote) = Unit

        /** Spotify reports [state] on the newest PlayerState subscription, when there is one. */
        fun report(state: PlayerState) {
            playerStates.lastOrNull()?.onEvent(state)
        }

        fun answerStateRead() {
            heldRead?.invoke()
            heldRead = null
        }

        private fun stateRead(): CallResult<PlayerState> {
            if (!holdStateRead) return answered(initialState)
            val result = mockk<CallResult<PlayerState>>(relaxed = true)
            every { result.setResultCallback(any()) } answers {
                val callback = firstArg<CallResult.ResultCallback<PlayerState>>()
                heldRead = { callback.onResult(initialState) }
                result
            }
            every { result.setErrorCallback(any<ErrorCallback>()) } returns result
            return result
        }

        private fun <T> answered(data: T?): CallResult<T> {
            val result = mockk<CallResult<T>>(relaxed = true)
            every { result.setResultCallback(any()) } answers {
                firstArg<CallResult.ResultCallback<T>>().onResult(data)
                result
            }
            every { result.setErrorCallback(any<ErrorCallback>()) } returns result
            return result
        }

        private fun <T> subscription(
            callbacks: MutableList<Subscription.EventCallback<T>>,
            initial: T? = null,
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
