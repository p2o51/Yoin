package com.gpo.yoin.player

import android.content.Context
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.scrobble.ScrobbleReporter
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * [PlaybackManager.disconnect] (an account switch or removal) resets the player's state itself:
 * the listener is gone before the player's own stop arrives. The scrobble rule only hears what
 * the manager tells it, so without a word here its timer went on counting the cut-short song and
 * submitted a play once the threshold passed.
 */
class PlaybackManagerScrobbleSessionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<YoinRepository>(relaxed = true).also {
        every { it.activeProviderId } returns MutableStateFlow(MediaId.PROVIDER_SUBSONIC)
        every { it.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
    }

    private val reporter = mockk<ScrobbleReporter>(relaxed = true)

    // Lazy: the manager's scope runs on Main, which the rule installs only once a test starts.
    private val manager by lazy {
        PlaybackManager(
            context = mockk<Context>(relaxed = true),
            repository = repository,
            castManager = null,
            spotifyClientIdProvider = { "test-client" },
            scrobbleReporter = reporter,
        )
    }

    @After
    fun tearDown() {
        val field = PlaybackManager::class.java.getDeclaredField("scope")
        field.isAccessible = true
        (field.get(manager) as CoroutineScope).cancel()
    }

    @Test
    fun should_tellTheScrobbleRuleTheSongStopped_when_disconnected() {
        manager.disconnect()

        verify(exactly = 1) { reporter.onState(null) }
    }
}
