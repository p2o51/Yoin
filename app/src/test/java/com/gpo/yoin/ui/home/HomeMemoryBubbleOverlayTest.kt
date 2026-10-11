package com.gpo.yoin.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Memories bubble's wiring on a real composition (owner 2026-10-11): the
 * dwell from the moment it is said in full, a press outside Home (the bar),
 * a finger held on it, and the app going to the background.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class HomeMemoryBubbleOverlayTest {

    @get:Rule
    val rule = createComposeRule()

    private val owner = TestOwner()
    private val controller = MemoryBubbleController()
    private lateinit var shell: MemoryBubbleShell
    private val opened = mutableListOf<Long>()

    private val pill = HomeMemoryPill(
        latest = HomeMemoryPill.Latest(
            sessionId = 7L,
            albumId = MediaId.subsonic("pang"),
            albumName = "Pang",
            artistName = "Caroline Polachek",
            coverArtUrl = null,
            scoreKind = MemoryScoreKind.ALBUM_RATING,
            scoreText = "8.4"
        ),
        noteCount = 3,
        scope = "subsonic|p1",
        // Never told: it speaks as soon as it can be read.
        newsKey = "100#3"
    )

    private val speaking: Boolean get() = rule.runOnIdle { controller.onBubbleTap != null }

    private fun setHome() {
        owner.registry.currentState = Lifecycle.State.RESUMED
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalWindowInfo provides FocusedWindow,
                LocalMemoryBubbleSeenStore provides InMemoryMemoryBubbleSeenStore()
            ) {
                shell = rememberMemoryBubbleShell()
                CompositionLocalProvider(LocalMemoryBubbleShell provides shell) {
                    YoinTheme {
                        // The window's root hears every press; Home is one part
                        // of it, the bar another.
                        Column(Modifier.fillMaxSize().reportMemoryBubbleShellPresses(shell)) {
                            val home = Modifier.fillMaxWidth().height(600.dp).testTag(HOME)
                            Box(home.watchMemoryBubbleTouches(controller)) {
                                MemoryBubbleOverlay(
                                    pill = pill,
                                    controller = controller,
                                    hintProgress = { 0f },
                                    revealProgress = { 1f },
                                    scrolledPx = { 0f },
                                    covered = false,
                                    extractBackdropColors = false,
                                    onOpenMemoryFocus = { opened += it },
                                    onNavigateToMemories = {},
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Box(Modifier.fillMaxWidth().height(80.dp).testTag(BAR))
                        }
                    }
                }
            }
        }
    }

    /** Runs the clock until the bubble is out, at rest; returns how long that took. */
    private fun untilSaidInFull(): Long {
        var waited = 0L
        while (!rule.runOnIdle { fullySaid() }) {
            check(waited < 10_000L) { "the bubble never came out" }
            advance(16L)
            waited += 16L
        }
        return waited
    }

    private fun fullySaid() = memoryBubbleFullySaid(
        unroll = controller.unroll.value,
        unrolling = controller.unroll.isRunning,
        flyAway = controller.flyAway.value,
        flyingAway = controller.flyAway.isRunning
    )

    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    @Test
    fun should_flyBack_when_saidInFullAndHeldForTheDwell() {
        setHome()
        untilSaidInFull()
        assertTrue(speaking)

        advance(MemoryBubbleDwellMs - 200L)
        assertTrue(speaking)
        advance(400L)
        assertFalse(speaking)
        // On the flyAway spring, not a cut: it is on its way into the arrow.
        advance(32L)
        assertTrue(rule.runOnIdle { controller.flyAway.value > 0f })
    }

    @Test
    fun should_tuck_when_theBarIsPressed() {
        setHome()
        untilSaidInFull()
        assertTrue(speaking)

        rule.onNodeWithTag(BAR).performTouchInput { click() }
        advance(16L)
        assertFalse(speaking)
    }

    @Test
    fun should_stayOut_when_homeSeesThePressOnTheBubbleItself() {
        setHome()
        untilSaidInFull()
        val bubble = rule.runOnIdle { controller.bubbleBounds.center }

        // The shell's root hears this press too; Home's own watcher has it.
        rule.onNodeWithTag(HOME).performTouchInput { down(bubble) }
        advance(16L)
        assertTrue(speaking)
        rule.onNodeWithTag(HOME).performTouchInput { up() }
        advance(16L)
        assertEquals(listOf(7L), opened)
    }

    @Test
    fun should_notLeaveFromUnderTheFinger_when_theDwellEndsWhileHeld() {
        setHome()
        untilSaidInFull()
        val bubble = rule.runOnIdle { controller.bubbleBounds.center }

        advance(MemoryBubbleDwellMs - 1_000L)
        rule.onNodeWithTag(HOME).performTouchInput { down(bubble) }
        advance(5_000L)
        // Long past the dwell: still out under the finger.
        assertTrue(speaking)
        rule.onNodeWithTag(HOME).performTouchInput { up() }
        advance(16L)
        // Let go, it was a tap: the memory opens.
        assertEquals(listOf(7L), opened)
    }

    @Test
    fun should_beAtRest_when_theAppGoesToTheBackground() {
        setHome()
        untilSaidInFull()
        assertTrue(rule.runOnIdle { shell.spokeThisForeground })

        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        advance(16L)

        assertFalse(speaking)
        rule.runOnIdle {
            assertEquals(0f, controller.unroll.value)
            assertEquals(0f, controller.markIn.value)
            assertEquals(0f, controller.flyAway.value)
            // The next time in the foreground may remind again.
            assertFalse(shell.spokeThisForeground)
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun should_freeTheReminder_when_theAppStopsWithLibraryShowing() {
        owner.registry.currentState = Lifecycle.State.RESUMED
        // Home isn't composed (Library is showing): the shell resets on its own.
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                shell = rememberMemoryBubbleShell()
            }
        }
        rule.runOnIdle { shell.spokeThisForeground = true }

        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.waitForIdle()

        assertFalse(rule.runOnIdle { shell.spokeThisForeground })
    }

    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry
    }

    private object FocusedWindow : WindowInfo {
        override val isWindowFocused: Boolean get() = true
    }

    private companion object {
        const val HOME = "home"
        const val BAR = "bar"
    }
}
