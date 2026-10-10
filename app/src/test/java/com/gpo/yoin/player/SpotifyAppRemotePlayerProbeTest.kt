package com.gpo.yoin.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.player.SpotifyAppRemotePlayer.ProbeConnection
import com.gpo.yoin.testutil.MainDispatcherRule
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.PlayerApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The debug library-state probe's App Remote connection (Q17): it never
 * leaves the player wanting a connection it didn't want before — so a later
 * host start doesn't reconnect on a Subsonic or Apple Music account — and
 * without a client id it doesn't try at all. A connect attempt shows as a
 * read of the client id once a host is there. A connection only the probe
 * opened reports nothing to the player and closes after it; on a Spotify
 * account's own connection the player hears Spotify as ever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SpotifyAppRemotePlayerProbeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var clientId = "client-id"
    private var clientIdReads = 0
    private val appRemote = FakeAppRemote()
    private val snapshots = mutableListOf<SpotifyRemoteSnapshot>()
    private val contexts = mutableListOf<SpotifyPlaybackContext?>()

    @Test
    fun should_reportPlayerStateAndContext_when_aSpotifyAccountsConnectionIsUp() = runTest {
        val player = player()
        player.onHostStart(context)
        player.warmConnection()
        runCurrent()

        appRemote.playerStates.last().onEvent(playerState())
        appRemote.playerContexts.last().onEvent(PlayerContext("spotify:album:a", "Album", "", "album"))
        runCurrent()

        assertTrue(snapshots.any { snapshot -> snapshot.observedPlayerState })
        assertEquals(listOf(SpotifyPlaybackContext(uri = "spotify:album:a", title = "Album")), contexts)
        assertTrue(appRemote.closed.isEmpty())
    }

    @Test
    fun should_reportPlayerState_when_aPlayOpensTheConnection() = runTest {
        val player = player()
        player.onHostStart(context)

        player.playQueue(listOf(spotifyTrack("t1")), startIndex = 0)
        runCurrent()
        appRemote.playerStates.last().onEvent(playerState())
        runCurrent()

        assertEquals(1, appRemote.connects)
        assertTrue(snapshots.last().observedPlayerState)
    }

    @Test
    fun should_reportNothingAndClose_when_onlyTheProbeOpenedTheConnection() = runTest {
        val player = player()
        // Yoin is open on a Subsonic account: nothing connects App Remote.
        player.onHostStart(context)
        assertEquals(0, appRemote.connects)

        val connection = player.withProbeConnection(timeoutMs = 1_000L) { connection ->
            appRemote.playerStates.last().onEvent(playerState())
            appRemote.playerContexts.last().onEvent(PlayerContext("spotify:album:a", "Album", "", "album"))
            delay(1L)
            connection
        }

        assertEquals(ProbeConnection.Connected, connection)
        assertTrue(snapshots.none { snapshot -> snapshot.observedPlayerState })
        assertTrue(contexts.isEmpty())
        assertEquals(listOf(appRemote.remote), appRemote.closed)
    }

    @Test
    fun should_keepTheConnection_when_aSpotifyAccountWarmsAppRemoteWhileTheProbeConnects() = runTest {
        val player = player()
        player.onHostStart(context)
        appRemote.holdConnects = true
        val probe = async { player.withProbeConnection(timeoutMs = 5_000L) { it } }
        runCurrent()
        assertEquals(1, appRemote.connects)

        // A cold start: the Spotify account's source comes in while the probe connects.
        player.warmConnection()
        appRemote.completeConnect()
        assertEquals(ProbeConnection.Connected, probe.await())
        appRemote.playerStates.last().onEvent(playerState())
        runCurrent()

        // The account's connection stays, and Spotify's state reaches the player.
        assertTrue(appRemote.closed.isEmpty())
        assertTrue(snapshots.last().observedPlayerState)
        assertEquals(1, appRemote.connects)
    }

    @Test
    fun should_keepTheConnectionAndReportSpotify_when_aSpotifyAccountWarmsAppRemoteDuringTheProbe() = runTest {
        val player = player()
        player.onHostStart(context)

        player.withProbeConnection(timeoutMs = 1_000L) { connection ->
            // The probe connected on its own; then the account becomes Spotify's.
            player.warmConnection()
            delay(1L)
            connection
        }
        appRemote.playerStates.last().onEvent(playerState())
        runCurrent()

        assertTrue(appRemote.closed.isEmpty())
        // Subscribed again, so the state Spotify had when the probe connected comes in too.
        assertEquals(2, appRemote.playerStates.size)
        assertEquals(2, appRemote.playerContexts.size)
        assertTrue(snapshots.last().observedPlayerState)
        assertEquals(1, appRemote.connects)
    }

    @Test
    fun should_notReconnectOnTheNextHostStart_when_probedOnAnAccountThatWantsNoConnection() = runTest {
        val player = player()

        val connection = player.withProbeConnection(timeoutMs = 1_000L) { it }

        assertEquals(ProbeConnection.NoHost, connection)
        val readsAfterProbe = clientIdReads
        // Yoin comes to the foreground on a Subsonic account: nothing connects for the probe.
        player.onHostStart(context)
        assertEquals(readsAfterProbe, clientIdReads)
    }

    @Test
    fun should_notTryToConnect_when_noClientIdIsSet() = runTest {
        clientId = ""
        val player = player()

        val connection = player.withProbeConnection(timeoutMs = 8_000L) { it }

        assertEquals(ProbeConnection.NoClientId, connection)
        assertEquals(0L, currentTime)
        val readsAfterProbe = clientIdReads
        player.onHostStart(context)
        assertEquals(readsAfterProbe, clientIdReads)
    }

    @Test
    fun should_keepTheConnectionWanted_when_probedOnASpotifyAccount() = runTest {
        val player = player()
        // A Spotify account warms App Remote; no Activity yet.
        player.warmConnection()

        player.withProbeConnection(timeoutMs = 1_000L) { it }

        // Blank from here, so the attempt stops at the client id instead of the SDK.
        clientId = ""
        val readsAfterProbe = clientIdReads
        player.onHostStart(context)
        assertEquals(readsAfterProbe + 1, clientIdReads)
    }

    private fun player() = SpotifyAppRemotePlayer(
        applicationContext = context,
        clientIdProvider = {
            clientIdReads++
            clientId
        },
        onSnapshot = { snapshot -> snapshots += snapshot },
        onContext = { playbackContext -> contexts += playbackContext },
        appRemote = appRemote
    )

    private fun playerState() = PlayerState(null, false, 1f, 0L, null, null)

    private fun spotifyTrack(rawId: String) = Track(
        id = MediaId.spotify(rawId),
        title = rawId,
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = 60,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )

    /**
     * App Remote as the player reaches it: connects at once (or, with
     * [holdConnects], when [completeConnect] says), records what it closes and
     * keeps each subscription's callback, newest last.
     */
    private class FakeAppRemote : AppRemoteConnector {
        var connects = 0
        var holdConnects = false
        val closed = mutableListOf<SpotifyAppRemote>()
        val playerStates = mutableListOf<Subscription.EventCallback<PlayerState>>()
        val playerContexts = mutableListOf<Subscription.EventCallback<PlayerContext>>()
        val remote = mockk<SpotifyAppRemote>(relaxed = true)
        private val playerApi = mockk<PlayerApi>(relaxed = true)
        private var held: Connector.ConnectionListener? = null

        init {
            every { remote.isConnected } returns true
            every { remote.playerApi } returns playerApi
            every { playerApi.subscribeToPlayerState() } answers { subscription(playerStates) }
            every { playerApi.subscribeToPlayerContext() } answers { subscription(playerContexts) }
        }

        override fun connect(context: Context, params: ConnectionParams, listener: Connector.ConnectionListener) {
            connects++
            if (holdConnects) held = listener else listener.onConnected(remote)
        }

        fun completeConnect() {
            held?.onConnected(remote)
            held = null
        }

        override fun disconnect(remote: SpotifyAppRemote) {
            closed += remote
        }

        private fun <T> subscription(callbacks: MutableList<Subscription.EventCallback<T>>): Subscription<T> {
            val subscription = mockk<Subscription<T>>(relaxed = true)
            every { subscription.setEventCallback(any()) } answers {
                callbacks += firstArg<Subscription.EventCallback<T>>()
                subscription
            }
            every { subscription.setLifecycleCallback(any()) } returns subscription
            return subscription
        }
    }
}
