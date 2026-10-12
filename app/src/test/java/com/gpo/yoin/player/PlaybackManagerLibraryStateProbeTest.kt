package com.gpo.yoin.player

import android.content.Context
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.SpotifyAppRemotePlayer.ProbeConnection
import com.gpo.yoin.player.SpotifyAppRemotePlayer.ProbeLeft
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Which account the debug library-state probe says it ran on (Q17): an
 * account whose source is still being built — the usual cold start, where
 * a Spotify account's warm-up then keeps the probe's connection — is not
 * "none", and neither is one whose source never came. No Activity is
 * started here, so App Remote doesn't connect: nothing to close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackManagerLibraryStateProbeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val provider = MutableStateFlow<String?>(null)
    private var profileId: String? = "spotify-a"
    private val settled = MutableStateFlow(false)
    private val repository = mockk<YoinRepository>(relaxed = true).also {
        every { it.activeProviderId } returns provider
        every { it.currentProviderId() } answers { provider.value }
        every { it.currentProfileId() } answers { profileId }
        every { it.activeSourceSettled } returns settled
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

    @After
    fun tearDown() {
        val field = PlaybackManager::class.java.getDeclaredField("scope")
        field.isAccessible = true
        (field.get(manager) as CoroutineScope).cancel()
    }

    @Test
    fun should_sayTheSourceIsBeingBuilt_when_theProbeRunsBeforeTheAccountsSourceIsUp() = runTest {
        val probe = probe()

        assertEquals(SpotifyLibraryStateProbe.ACCOUNT_BUILDING, probe.account)
        assertEquals(ProbeConnection.NoHost, probe.connection)
        assertEquals(ProbeLeft.NotConnected, probe.left)
    }

    @Test
    fun should_sayTheAccountHasNoSource_when_profileManagerSettledWithoutOne() = runTest {
        settled.value = true

        assertEquals(SpotifyLibraryStateProbe.ACCOUNT_UNAVAILABLE, probe().account)
    }

    @Test
    fun should_sayNone_when_noAccountIsChosen() = runTest {
        profileId = null
        settled.value = true

        assertEquals(SpotifyLibraryStateProbe.ACCOUNT_NONE, probe().account)
    }

    @Test
    fun should_nameTheService_when_theAccountsSourceIsUp() = runTest {
        provider.value = MediaId.PROVIDER_SUBSONIC

        assertEquals(MediaId.PROVIDER_SUBSONIC, probe().account)
    }

    private suspend fun probe() =
        manager.probeSpotifyLibraryStates(listOf("spotify:track:a"), connectTimeoutMs = 1_000L)
}
