package com.gpo.yoin.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.player.SpotifyAppRemotePlayer.ProbeConnection
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The debug library-state probe's App Remote connection (Q17): it never
 * leaves the player wanting a connection it didn't want before — so a later
 * host start doesn't reconnect on a Subsonic or Apple Music account — and
 * without a client id it doesn't try at all. A connect attempt shows as a
 * read of the client id once a host is there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SpotifyAppRemotePlayerProbeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var clientId = "client-id"
    private var clientIdReads = 0

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
        onSnapshot = {}
    )
}
