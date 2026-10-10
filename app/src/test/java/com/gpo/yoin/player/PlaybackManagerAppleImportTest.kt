package com.gpo.yoin.player

import android.content.Context
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaybackHandle
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.applemusic.AppleMusicSource
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [PlaybackManager.play] with Apple Music imports in the list: the imports
 * never reach MusicKit, a tapped import still says why it can't play, and a
 * Play / Shuffle start whose first song is an import starts at the next one.
 */
class PlaybackManagerAppleImportTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<YoinRepository>(relaxed = true).also {
        every { it.activeProviderId } returns MutableStateFlow(MediaId.PROVIDER_APPLE_MUSIC)
        every { it.currentProviderId() } returns MediaId.PROVIDER_APPLE_MUSIC
    }

    /** The real source's judgment and message; resolving a track makes no request. */
    private val apple = AppleMusicSource(
        ProfileCredentials.AppleMusic("https://token.test", "user"),
        api = AppleMusicApiClient({ "developer" }, { "user" })
    )

    /** What the real source says when handed an import. */
    private val importError: String by lazy {
        runBlocking { runCatching { apple.handleFor(import("any")) } }.exceptionOrNull()?.message.orEmpty()
    }

    /** Every track the manager asked the source to resolve, in order. */
    private val resolved = mutableListOf<Track>()

    private val playback = object : MusicPlayback {
        override suspend fun handleFor(track: Track): PlaybackHandle {
            resolved += track
            return apple.handleFor(track)
        }
    }

    private val source = mockk<MusicSource>(relaxed = true).also {
        every { it.id } returns MediaId.PROVIDER_APPLE_MUSIC
        every { it.playback() } returns playback
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
    fun should_reportError_when_clickedTrackIsUnplayableImport() {
        val tapped = import("a")
        assertTrue(importError.isNotEmpty())

        manager.play(listOf(catalog("1"), tapped, catalog("2")), startIndex = 1, source = source)

        val state = manager.playbackState.value
        assertEquals(ConnectionPhase.Error, state.connectionPhase)
        assertEquals(importError, state.connectionErrorMessage)
        // Nothing else was started in its place.
        assertEquals(listOf(tapped), resolved)
    }

    @Test
    fun should_neverResolveImports_when_tappedTrackIsPlayable() {
        val tapped = catalog("2")

        manager.play(
            listOf(import("a"), catalog("1"), import("b"), tapped, import("c")),
            startIndex = 3,
            source = source
        )

        // The start first, then the queue MusicKit is handed: imports gone.
        assertEquals(listOf(tapped, catalog("1"), tapped), resolved)
        assertNotEquals(importError, manager.playbackState.value.connectionErrorMessage)
    }

    @Test
    fun should_startAtFirstPlayableSong_when_shuffledListStartsWithImport() {
        // A Shuffle button's list: its first place is the shuffle's, never a pick.
        manager.play(
            listOf(import("a"), catalog("1"), import("b"), catalog("2")),
            startIndex = 0,
            source = source,
            shuffled = true
        )

        assertEquals(listOf(catalog("1"), catalog("1"), catalog("2")), resolved)
        assertNotEquals(importError, manager.playbackState.value.connectionErrorMessage)
    }

    @Test
    fun should_startAtFirstPlayableSong_when_playAllListStartsWithImport() {
        manager.play(
            listOf(import("a"), catalog("1"), catalog("2")),
            startIndex = 0,
            source = source,
            explicitStart = false
        )

        assertEquals(listOf(catalog("1"), catalog("1"), catalog("2")), resolved)
        assertNotEquals(importError, manager.playbackState.value.connectionErrorMessage)
    }

    private fun catalog(id: String) = track(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "14408577$id"))

    private fun import(id: String) = track(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:i.$id"))

    private fun track(id: MediaId) = Track(
        id = id,
        title = "Track $id",
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
