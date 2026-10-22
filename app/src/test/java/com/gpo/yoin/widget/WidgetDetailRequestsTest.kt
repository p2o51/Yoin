package com.gpo.yoin.widget

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gpo.yoin.data.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Widget taps wait for a routable shell and cannot replay after that shell consumes them. */
@RunWith(RobolectricTestRunner::class)
class WidgetDetailRequestsTest {

    @get:Rule
    val rule = createComposeRule()

    private val owner = TestOwner()
    private val album = WidgetDetailRequest(WidgetEntity.ALBUM, MediaId.spotify("album-1"))
    private var pending by mutableStateOf<WidgetDetailRequest?>(album)
    private var enabled by mutableStateOf(true)
    private var shellGeneration by mutableIntStateOf(0)
    private val consumed = mutableListOf<WidgetDetailRequest>()
    private val opened = mutableListOf<WidgetDetailRequest>()
    private val events = mutableListOf<String>()

    @Test
    fun should_consumeBeforeOpeningOnce_when_shellResumes() {
        setShell()
        assertEquals(album, pending)
        assertEquals(emptyList<WidgetDetailRequest>(), consumed)
        assertEquals(emptyList<WidgetDetailRequest>(), opened)

        update { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertNull(pending)
        assertEquals(listOf(album), consumed)
        assertEquals(listOf(album), opened)
        assertEquals(listOf("consume", "open"), events)

        // Opening a Compact detail covers the shell; returning must not reopen it.
        update { owner.registry.currentState = Lifecycle.State.STARTED }
        update { owner.registry.currentState = Lifecycle.State.RESUMED }
        // A rebuilt shell reads the consumed (null) pending state, too.
        update { shellGeneration++ }
        assertEquals(listOf(album), opened)
        assertEquals(listOf("consume", "open"), events)
    }

    @Test
    fun should_keepTheRequest_when_routingIsDisabledWhileWaitingForResume() {
        setShell()

        // The shell started waiting, then became unavailable before RESUMED.
        update { enabled = false }
        update { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertEquals(album, pending)
        assertEquals(emptyList<WidgetDetailRequest>(), consumed)
        assertEquals(emptyList<WidgetDetailRequest>(), opened)

        update { enabled = true }
        assertNull(pending)
        assertEquals(listOf(album), consumed)
        assertEquals(listOf(album), opened)
    }

    @Test
    fun should_openOnlyTheLatestRequest_when_anotherWidgetTapArrivesBeforeResume() {
        setShell()
        val artist = WidgetDetailRequest(WidgetEntity.ARTIST, MediaId.subsonic("artist-2"))

        update { pending = artist }
        update { owner.registry.currentState = Lifecycle.State.RESUMED }

        assertNull(pending)
        assertEquals(listOf(artist), consumed)
        assertEquals(listOf(artist), opened)
    }

    @Test
    fun should_notOpenTheStaleRequest_when_theActivityAlreadyOwnsANewerWidgetTap() {
        val artist = WidgetDetailRequest(WidgetEntity.ARTIST, MediaId.subsonic("artist-2"))
        var activityRequest: WidgetDetailRequest? = album
        val attempts = mutableListOf<WidgetDetailRequest>()
        owner.registry.currentState = Lifecycle.State.STARTED
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TakeWidgetDetailRequest(
                    // Deliberately retain the old composed value: the Activity has
                    // received a new Intent before recomposition cancels this effect.
                    request = album,
                    enabled = true,
                    onConsumed = { request ->
                        attempts += request
                        if (activityRequest == request) {
                            activityRequest = null
                            true
                        } else {
                            false
                        }
                    },
                    onOpen = { opened += it },
                )
            }
        }
        rule.waitForIdle()

        update {
            activityRequest = artist
            owner.registry.currentState = Lifecycle.State.RESUMED
        }

        assertEquals(listOf(album), attempts)
        assertEquals(artist, activityRequest)
        assertEquals(emptyList<WidgetDetailRequest>(), opened)
    }

    @Test
    fun should_notConsumeOrOpen_when_theLiveGateClosesBeforeRecomposition() {
        var ready = true
        var attempts = 0
        owner.registry.currentState = Lifecycle.State.STARTED
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TakeWidgetDetailRequest(
                    request = album,
                    enabled = true,
                    canOpen = { ready },
                    onConsumed = {
                        attempts++
                        true
                    },
                    onOpen = { opened += it },
                )
            }
        }
        rule.waitForIdle()

        update {
            ready = false
            owner.registry.currentState = Lifecycle.State.RESUMED
        }

        assertEquals(0, attempts)
        assertEquals(emptyList<WidgetDetailRequest>(), opened)
    }

    @Test
    fun should_restoreTheDestination_when_savedEntityAndNamespacedIdAreParsed() {
        val playlist = WidgetDetailRequest(
            WidgetEntity.PLAYLIST,
            MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:p.playlist-1"),
        )

        assertEquals(playlist, WidgetDetailRequest.parse(playlist.entity.name, playlist.id.toString()))
    }

    @Test
    fun should_ignoreTheDestination_when_entityOrIdIsInvalid() {
        assertNull(WidgetDetailRequest.parse(null, album.id.toString()))
        assertNull(WidgetDetailRequest.parse("TRACK", album.id.toString()))
        assertNull(WidgetDetailRequest.parse("ALBUM", null))
        assertNull(WidgetDetailRequest.parse("ALBUM", ""))
        assertNull(WidgetDetailRequest.parse("ALBUM", "album-without-provider"))
        assertNull(WidgetDetailRequest.parse("ALBUM", "spotify:"))
    }

    private fun setShell() {
        owner.registry.currentState = Lifecycle.State.STARTED
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                key(shellGeneration) {
                    TakeWidgetDetailRequest(
                        request = pending,
                        enabled = enabled,
                        onConsumed = { request ->
                            if (pending == request) {
                                events += "consume"
                                consumed += request
                                pending = null
                                true
                            } else {
                                false
                            }
                        },
                        onOpen = { request ->
                            // It must be safe to recreate the shell as soon as navigation starts.
                            assertNull(pending)
                            events += "open"
                            opened += request
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun update(block: () -> Unit) {
        rule.runOnIdle(block)
        rule.waitForIdle()
    }

    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry
    }
}
