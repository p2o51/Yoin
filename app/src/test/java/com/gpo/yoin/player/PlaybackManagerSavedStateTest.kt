package com.gpo.yoin.player

import android.content.Context
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * The app-wide PlaybackManager makes the Spotify heart check (P4) — not each
 * Activity's Now Playing model — once per track, warm-connect adoption
 * included. Here App Remote isn't connected, so each check is the Web API
 * fallback the repository gates and throttles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackManagerSavedStateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<YoinRepository>(relaxed = true).also {
        every { it.activeProviderId } returns MutableStateFlow(MediaId.PROVIDER_SPOTIFY)
        every { it.currentProviderId() } returns MediaId.PROVIDER_SPOTIFY
        every { it.currentProfileId() } returns "spotify-a"
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
    fun should_checkOnce_when_spotifyReportsTheAdoptedTrackAgainAndAgain() = runTest {
        val track = track("a")

        repeat(4) { publish(track) }
        advanceTimeBy(5_000L)
        runCurrent()

        coVerify(exactly = 1) { repository.refreshFavoriteStates(listOf(track), any()) }
    }

    @Test
    fun should_checkEachTrack_when_spotifyMovesToTheNext() = runTest {
        val first = track("a")
        val second = track("b")

        publish(first)
        advanceTimeBy(5_000L)
        publish(second)
        publish(second)
        advanceTimeBy(5_000L)
        runCurrent()

        coVerify(exactly = 1) { repository.refreshFavoriteStates(listOf(first), any()) }
        coVerify(exactly = 1) { repository.refreshFavoriteStates(listOf(second), any()) }
    }

    /** What App Remote's PlayerState subscription hands the manager. */
    private fun publish(track: Track) {
        val method = PlaybackManager::class.java
            .getDeclaredMethod("publishRemoteState", SpotifyRemoteSnapshot::class.java)
        method.isAccessible = true
        method.invoke(
            manager,
            SpotifyRemoteSnapshot(
                currentTrack = track,
                isPlaying = false,
                queue = listOf(track),
                currentIndex = 0,
                connectionPhase = ConnectionPhase.Ready,
                observedPlayerState = true
            )
        )
    }

    private fun track(rawId: String) = Track(
        id = MediaId.spotify(rawId),
        title = rawId,
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )
}
