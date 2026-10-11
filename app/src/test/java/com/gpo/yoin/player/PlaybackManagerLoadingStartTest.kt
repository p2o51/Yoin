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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * [PlaybackManager.playWhenLoaded]: an artist's Play reads every release
 * before it can start. The read belongs to the player, not to the page that
 * asked (a rotation rebuilds that page mid-read), and the newest start wins:
 * a read that lands after the user started something else starts nothing.
 */
class PlaybackManagerLoadingStartTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<YoinRepository>(relaxed = true).also {
        every { it.activeProviderId } returns MutableStateFlow(MediaId.PROVIDER_SUBSONIC)
        every { it.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
    }

    /** Every track the manager began a start with, in order. Resolving fails, so nothing else runs. */
    private val started = mutableListOf<Track>()

    private val playback = object : MusicPlayback {
        override suspend fun handleFor(track: Track): PlaybackHandle {
            started += track
            error("not streamed in a unit test")
        }
    }

    private val source = mockk<MusicSource>(relaxed = true).also {
        every { it.id } returns MediaId.PROVIDER_SUBSONIC
        every { it.playback() } returns playback
    }

    private val artist = ActivityContext.Artist("subsonic:ar-010", artistName = "Halcyon Drive", coverArtId = null)

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
    fun should_startTheQueueWithItsContext_when_theReadLandsAfterThePageThatAskedIsGone() {
        val reads = CompletableDeferred<List<Track>>()
        // The page that asked: a rotation disposes it, and its scope, while its releases still load.
        val page = CoroutineScope(mainDispatcherRule.dispatcher)
        page.launch {
            manager.playWhenLoaded(source = source, activityContext = artist, explicitStart = false) { reads.await() }
        }
        page.cancel()
        assertEquals(emptyList<Track>(), started)

        reads.complete(listOf(track("fires-1"), track("fires-2")))

        assertEquals(listOf(track("fires-1")), started)
        assertEquals(artist, manager.currentActivityContext.value)
    }

    @Test
    fun should_dropTheReadQueue_when_anotherPlayStartsWhileItLoads() {
        val reads = CompletableDeferred<List<Track>>()
        manager.playWhenLoaded(source = source, activityContext = artist) { reads.await() }

        manager.play(listOf(track("song")), source = source)
        reads.complete(listOf(track("fires-1")))

        assertEquals(listOf(track("song")), started)
        assertEquals(ActivityContext.None, manager.currentActivityContext.value)
    }

    @Test
    fun should_startOnlyTheLastTap_when_twoReadsOverlap() {
        val first = CompletableDeferred<List<Track>>()
        val second = CompletableDeferred<List<Track>>()
        manager.playWhenLoaded(source = source, activityContext = artist) { first.await() }
        manager.playWhenLoaded(source = source, activityContext = artist) { second.await() }

        second.complete(listOf(track("velvet-2")))
        first.complete(listOf(track("velvet-1")))

        assertEquals(listOf(track("velvet-2")), started)
    }

    @Test
    fun should_startNothing_when_theAccountSwitchesWhileItLoads() {
        val reads = CompletableDeferred<List<Track>>()
        manager.playWhenLoaded(source = source, activityContext = artist) { reads.await() }

        manager.disconnect()
        reads.complete(listOf(track("fires-1")))

        assertEquals(emptyList<Track>(), started)
        assertEquals(ActivityContext.None, manager.currentActivityContext.value)
    }

    @Test
    fun should_keepWhatIsPlaying_when_theReadComesBackEmptyOrFails() {
        manager.play(listOf(track("song")), source = source)

        manager.playWhenLoaded(source = source, activityContext = artist) { emptyList() }
        manager.playWhenLoaded(source = source, activityContext = artist) { error("offline") }

        assertEquals(listOf(track("song")), started)
        assertEquals(ActivityContext.None, manager.currentActivityContext.value)
    }

    private fun track(rawId: String) = Track(
        id = MediaId.subsonic(rawId),
        title = "Track $rawId",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = null,
        coverArt = null,
        durationSec = 180,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )
}
