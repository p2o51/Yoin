package com.gpo.yoin.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaybackHandle
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [PlaybackManager.play] with a start position and a paused start: what a
 * Continue On hand-off (docs/continue-on.md) asks of the local player.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackManagerStartPositionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<YoinRepository>(relaxed = true).also {
        every { it.activeProviderId } returns MutableStateFlow(MediaId.PROVIDER_SUBSONIC)
        every { it.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
    }

    private val playback = object : MusicPlayback {
        override suspend fun handleFor(track: Track): PlaybackHandle =
            PlaybackHandle.DirectStream("https://music.example/stream/${track.id.rawId}")
    }

    private val source = mockk<MusicSource>(relaxed = true).also {
        every { it.id } returns MediaId.PROVIDER_SUBSONIC
        every { it.playback() } returns playback
    }

    /** The local player, already connected (and, as far as it knows, playing something else). */
    private val player = mockk<MediaController>(relaxed = true)

    // Lazy: the manager's scope runs on Main, which the rule installs only once a test starts.
    private val manager by lazy {
        PlaybackManager(
            context = mockk<Context>(relaxed = true),
            repository = repository,
            castManager = null,
            spotifyClientIdProvider = { "test-client" },
        ).also { manager ->
            PlaybackManager::class.java.getDeclaredField("controller").apply {
                isAccessible = true
                set(manager, player)
            }
        }
    }

    @After
    fun tearDown() {
        val field = PlaybackManager::class.java.getDeclaredField("scope")
        field.isAccessible = true
        (field.get(manager) as CoroutineScope).cancel()
    }

    @Test
    fun should_startTheTrackAtThePosition_when_aStartCarriesOne() {
        manager.play(listOf(track("a"), track("b")), startIndex = 1, source = source, startPositionMs = 83_000L)

        verifyOrder {
            player.setMediaItems(any<List<MediaItem>>(), 1, 83_000L)
            player.prepare()
            player.play()
        }
        verify(exactly = 0) { player.pause() }
    }

    @Test
    fun should_loadWithoutPlaying_when_aPausedStartLandsOnAPlayerThatWasPlaying() {
        manager.play(
            listOf(track("a"), track("b")),
            startIndex = 1,
            source = source,
            startPositionMs = 83_000L,
            startPaused = true,
        )

        // Paused before it is prepared: setMediaItems alone keeps the old queue's playWhenReady.
        verifyOrder {
            player.setMediaItems(any<List<MediaItem>>(), 1, 83_000L)
            player.pause()
            player.prepare()
        }
        verify(exactly = 0) { player.play() }
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
        userRating = null,
    )
}
