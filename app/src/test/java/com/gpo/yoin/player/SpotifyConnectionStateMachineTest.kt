package com.gpo.yoin.player

import android.content.Context
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SpotifyConnectionStateMachineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val activeProviderId = MutableStateFlow<String?>(null)
    private val currentProviderId = MutableStateFlow<String?>(null)
    private val repository = mockk<YoinRepository>(relaxed = true).also {
        every { it.activeProviderId } returns activeProviderId
        every { it.currentProviderId() } answers { currentProviderId.value }
    }
    private fun buildManager(): PlaybackManager =
        PlaybackManager(
            context = mockk<Context>(relaxed = true),
            repository = repository,
            castManager = null,
            spotifyClientIdProvider = { "test-client" },
        )

    @Test
    fun connecting_snapshot_keeps_pending_track_until_real_player_state_arrives() {
        val manager = buildManager()
        try {
            setPrivateBoolean(manager, "pendingSpotifyHandoff", true)

            val pending = sampleTrack("pending")
            publishRemoteState(
                manager,
                SpotifyRemoteSnapshot(
                    pendingTrack = pending,
                    queue = listOf(pending),
                    currentIndex = 0,
                    connectionPhase = ConnectionPhase.Connecting,
                    observedPlayerState = false,
                ),
            )

            val state = manager.playbackState.value
            assertEquals(ConnectionPhase.Connecting, state.connectionPhase)
            assertEquals(pending, state.pendingTrack)
            assertNull(state.currentTrack)
        } finally {
            cancelManagerScope(manager)
            activeProviderId.value = null
            currentProviderId.value = null
        }
    }

    @Test
    fun ready_snapshot_adopts_spotify_when_warm_connection_observes_real_track() {
        val manager = buildManager()
        try {
            currentProviderId.value = MediaId.PROVIDER_SPOTIFY

            val current = sampleTrack("current")
            publishRemoteState(
                manager,
                SpotifyRemoteSnapshot(
                    currentTrack = current,
                    queue = listOf(current),
                    currentIndex = 0,
                    positionMs = 42_000L,
                    durationMs = 180_000L,
                    isPlaying = false,
                    connectionPhase = ConnectionPhase.Ready,
                    observedPlayerState = true,
                ),
            )

            val state = manager.playbackState.value
            assertEquals(ConnectionPhase.Ready, state.connectionPhase)
            assertEquals(current, state.currentTrack)
            assertEquals(42_000L, state.position)
            assertEquals(180_000L, state.duration)
        } finally {
            cancelManagerScope(manager)
            activeProviderId.value = null
            currentProviderId.value = null
        }
    }

    private fun publishRemoteState(manager: PlaybackManager, snapshot: SpotifyRemoteSnapshot) {
        val method = PlaybackManager::class.java.getDeclaredMethod(
            "publishRemoteState",
            SpotifyRemoteSnapshot::class.java,
        )
        method.isAccessible = true
        method.invoke(manager, snapshot)
    }

    private fun setPrivateBoolean(manager: PlaybackManager, fieldName: String, value: Boolean) {
        val field = PlaybackManager::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.setBoolean(manager, value)
    }

    private fun cancelManagerScope(manager: PlaybackManager) {
        val field = PlaybackManager::class.java.getDeclaredField("scope")
        field.isAccessible = true
        val scope = field.get(manager) as CoroutineScope
        scope.cancel()
    }

    private fun sampleTrack(id: String): Track = Track(
        id = MediaId.spotify(id),
        title = "Track $id",
        artist = "Artist",
        artistId = MediaId.spotify("artist-$id"),
        album = "Album",
        albumId = MediaId.spotify("album-$id"),
        coverArt = null,
        durationSec = 180,
        trackNumber = 1,
        year = 2024,
        genre = null,
        userRating = null,
        isStarred = false,
    )
}
