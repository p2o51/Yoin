package com.gpo.yoin.player

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
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
    private var elsewhere: String? = null
    private var elsewhereReads = 0
    private var webStarts = 0
    private val players = mutableListOf<SpotifyAppRemotePlayer>()

    @After
    fun tearDown() {
        players.forEach { it.disconnect(resetState = true) }
    }

    @Test
    fun should_sayItIsAsleep_when_spotifyDoesntAnswerTheStartInTime() = runTest {
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
        assertEquals(1, elsewhereReads)
        assertEquals(0, webStarts)
    }

    @Test
    fun should_nameTheOtherDevice_when_spotifyPlaysThere() = runTest {
        val player = hostedPlayer()
        elsewhere = "Living Room"

        tap(player)
        advanceTimeBy(SPOTIFY_UNREACHABLE_AFTER_MS + 100)
        runCurrent()

        assertEquals(SpotifyConnectFailure.PlayingElsewhere("Living Room"), snapshots.last().connectionFailure)
        assertEquals(0, webStarts)
    }

    @Test
    fun should_hintAfterTheSilentRetry_when_theConnectFailsAtOnce() = runTest {
        appRemote.failWith = SpotifyDisconnectedException()
        val player = hostedPlayer()

        tap(player)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, appRemote.connects)
        assertEquals(SpotifyConnectFailure.SpotifyAsleep, snapshots.last().connectionFailure)
    }

    @Test
    fun should_playTheHeldSong_when_theUserOpenedSpotifyAndItIsPaused() = runTest {
        val player = hintShown()

        assertTrue(player.openSpotify(playHere = false))
        comeBack(player, spotifyState = state(paused = true))

        assertEquals(1, webStarts)
        assertEquals(song, snapshots.last().currentTrack)
    }

    @Test
    fun should_notCutIn_when_spotifyIsAlreadyPlayingOnTheWayBack() = runTest {
        val player = hintShown()

        player.openSpotify(playHere = false)
        comeBack(player, spotifyState = state(uri = "spotify:track:theirs", paused = false))

        assertEquals(0, webStarts)
        assertNull(snapshots.last().pendingTrack)
        assertEquals(MediaId.spotify("theirs"), snapshots.last().currentTrack?.id)
    }

    @Test
    fun should_dropTheHeldSong_when_theHintWasDismissed() = runTest {
        val player = hintShown()

        comeBack(player, spotifyState = state(paused = true))

        assertEquals(0, webStarts)
        assertNull(snapshots.last().pendingTrack)
    }

    @Test
    fun should_playHere_when_theUserAskedToMovePlaybackFromAnotherDevice() = runTest {
        elsewhere = "Living Room"
        val player = hintShown()

        player.openSpotify(playHere = true)
        comeBack(player, spotifyState = state(uri = "spotify:track:theirs", paused = false))

        assertEquals(1, webStarts)
    }

    @Test
    fun should_tryAgainFromScratch_when_theUserTapsAnotherSongAfterTheHint() = runTest {
        val player = hintShown()
        appRemote.answers = true

        tap(player)
        runCurrent()

        assertEquals(1, webStarts)
    }

    @Test
    fun should_keepTheTypedFailure_when_theConnectSaysWhatIsWrong() = runTest {
        appRemote.failWith = NotLoggedInException("not logged in", null)
        val player = hostedPlayer()

        tap(player)
        advanceTimeBy(SPOTIFY_UNREACHABLE_AFTER_MS * 2)
        runCurrent()

        assertTrue(snapshots.last().connectionFailure is SpotifyConnectFailure.AuthFailure)
        assertEquals(0, elsewhereReads)
    }

    @Test
    fun should_playTheNewTap_when_itComesInWhileTheHeldStartIsLookedAt() = runTest {
        val player = hintShown()
        player.openSpotify(playHere = false)
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
    fun should_lookAgain_when_yoinLeavesDuringTheLook() = runTest {
        val player = hintShown()
        player.openSpotify(playHere = false)
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
            startContextPlayback = {
                webStarts++
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
        playingElsewhere = {
            elsewhereReads++
            elsewhere
        },
        spotifyLaunchIntent = { Intent(Intent.ACTION_MAIN).setPackage("com.spotify.music") },
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

        fun report(state: PlayerState) = playerStates.last().onEvent(state)

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
