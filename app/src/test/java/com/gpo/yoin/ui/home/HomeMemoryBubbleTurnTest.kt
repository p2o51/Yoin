package com.gpo.yoin.ui.home

import androidx.compose.ui.platform.AccessibilityManager
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the Memories bubble speaks and when it goes (owner 2026-10-11). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeMemoryBubbleTurnTest {

    private val idleReminder = MemoryBubbleTurn().after(MemoryBubbleCue.IdleElapsed)

    // ---- Said in full, it goes after the dwell ----

    @Test
    fun should_goAfterTheDwell_when_saidInFullAndLeftAlone() = runTest {
        val fullySaid = MutableStateFlow(false)
        var gone = false
        backgroundScope.launch {
            awaitMemoryBubbleDwell(fullySaid, held = MutableStateFlow(false), dwellMs = MemoryBubbleDwellMs)
            gone = true
        }
        // Still unrolling: the clock hasn't started, however long that takes.
        step(20_000L)
        assertFalse(gone)

        fullySaid.value = true
        step(MemoryBubbleDwellMs - 1)
        assertFalse(gone)
        step(1L)
        assertTrue(gone)
        val after = idleReminder.after(MemoryBubbleCue.DwellOver)
        assertEquals(MemoryBubbleTurn(reason = null, spokeThisForeground = true), after)
    }

    @Test
    fun should_startTheDwell_when_fullyOutAndAtRest() {
        assertFalse(fullySaid(unroll = 0.6f, unrolling = true))
        // The Expressive spring overshoots past 1 on its way in.
        assertFalse(fullySaid(unroll = 1.04f, unrolling = true))
        assertFalse(fullySaid(unroll = 0.98f, unrolling = false))
        assertTrue(fullySaid(unroll = 1f, unrolling = false))
    }

    @Test
    fun should_notStartTheDwell_when_calledBackWhileFlyingAway() {
        // Flying off, unroll stays at 1 and at rest — only flyAway moves.
        assertFalse(fullySaid(unroll = 1f, unrolling = false, flyAway = 0.4f, flyingAway = true))
        // Cancelled mid-flight, before the overlay starts it over from the title.
        assertFalse(fullySaid(unroll = 1f, unrolling = false, flyAway = 0.4f, flyingAway = false))
        assertFalse(fullySaid(unroll = 1f, unrolling = false, flyAway = 0f, flyingAway = true))
    }

    @Test
    fun should_waitForTheRelease_when_theDwellEndsUnderAFinger() = runTest {
        val held = MutableStateFlow(false)
        var gone = false
        backgroundScope.launch {
            awaitMemoryBubbleDwell(MutableStateFlow(true), held, dwellMs = MemoryBubbleDwellMs)
            gone = true
        }
        step(MemoryBubbleDwellMs - 100L)
        // A finger comes down on the bubble just before the dwell ends.
        held.value = true
        step(10_000L)
        assertFalse(gone)
        held.value = false
        step(0L)
        assertTrue(gone)
    }

    // ---- How long it holds ----

    @Test
    fun should_holdSixSeconds_when_noAccessibilityTimeoutIsSet() {
        assertEquals(6_000L, memoryBubbleDwellMs(MemoryBubbleDwellMs, accessibility = null))
        assertEquals(6_000L, memoryBubbleDwellMs(MemoryBubbleDwellMs, FakeAccessibility(userTimeoutMs = 0L)))
    }

    @Test
    fun should_holdLonger_when_theAccessibilityTimeoutAsksForIt() {
        val accessibility = FakeAccessibility(userTimeoutMs = 60_000L)
        assertEquals(60_000L, memoryBubbleDwellMs(MemoryBubbleDwellMs, accessibility))
        // Asked as what it is: a picture, words, and a control.
        assertEquals(Triple(true, true, true), accessibility.asked)
    }

    // ---- The background puts it away ----

    @Test
    fun should_beTuckedAway_when_backFromTheBackground() {
        val speaking = MemoryBubbleTurn().after(MemoryBubbleCue.News)
        val back = speaking.after(MemoryBubbleCue.Backgrounded)
        assertNull(back.reason)
        // A new time in the foreground: it may remind again.
        assertEquals(BubbleReason.Idle, back.after(MemoryBubbleCue.IdleElapsed).reason)
    }

    // ---- The idle reminder: at most once per time in the foreground ----

    @Test
    fun should_remindOnce_when_leftAloneAgainInTheSameForeground() {
        assertEquals(BubbleReason.Idle, idleReminder.reason)
        val gone = idleReminder.after(MemoryBubbleCue.DwellOver)
        assertEquals(gone, gone.after(MemoryBubbleCue.IdleElapsed))
        // Touched, covered by a detail page, back again: still used up.
        val back = gone.after(MemoryBubbleCue.Touch).after(MemoryBubbleCue.OutOfReach)
        assertNull(back.after(MemoryBubbleCue.IdleElapsed).reason)
    }

    @Test
    fun should_notRemind_when_newsAlreadySpokeThisForeground() {
        val told = MemoryBubbleTurn().after(MemoryBubbleCue.News).after(MemoryBubbleCue.DwellOver)
        assertNull(told.after(MemoryBubbleCue.IdleElapsed).reason)
    }

    @Test
    fun should_stillTellNews_when_theReminderIsUsedUp() {
        val gone = idleReminder.after(MemoryBubbleCue.DwellOver)
        assertEquals(BubbleReason.News, gone.after(MemoryBubbleCue.News).reason)
    }

    @Test
    fun should_keepQuiet_when_anotherWindowIsInFront() {
        assertTrue(memoryBubbleWindowReadable(Lifecycle.State.RESUMED, focused = true))
        // The account card's dialog, the notification shade.
        assertFalse(memoryBubbleWindowReadable(Lifecycle.State.RESUMED, focused = false))
    }

    @Test
    fun should_keepQuiet_when_homeIsOnlyStarted() {
        // Under a detail page (translucent: Home only pauses) — even if the
        // window still reports focus for a moment.
        assertFalse(memoryBubbleWindowReadable(Lifecycle.State.STARTED, focused = true))
        assertFalse(memoryBubbleWindowReadable(Lifecycle.State.STARTED, focused = false))
        assertFalse(memoryBubbleWindowReadable(Lifecycle.State.CREATED, focused = true))
    }

    // ---- The bar counts as touching the screen ----

    @Test
    fun should_tuck_when_theBarIsPressed() {
        // The shell's root saw the press and Home's root didn't: the bar, the pill.
        assertTrue(isShellOnlyPress(shellDownAt = 2_000L, homeDownAt = 1_000L))
        assertNull(idleReminder.after(MemoryBubbleCue.Touch).reason)
    }

    @Test
    fun should_leaveHomeItsOwnPress_when_homeSawItToo() {
        // A tap on the bubble itself is Home's tap, not a tuck.
        assertFalse(isShellOnlyPress(shellDownAt = 2_000L, homeDownAt = 2_000L))
    }

    @Test
    fun should_startTheIdleClockOver_when_theBarIsPressed() = runTest {
        val touches = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var elapsed = false
        backgroundScope.launch {
            awaitMemoryBubbleIdle(MutableStateFlow(true), touches, idleMs = MemoryBubbleIdleMs)
            elapsed = true
        }
        step(10_000L)
        touches.tryEmit(Unit)
        step(MemoryBubbleIdleMs - 1)
        assertFalse(elapsed)
        step(1L)
        assertTrue(elapsed)
    }

    @Test
    fun should_startTheIdleClockOver_when_homeGoesOutOfReach() = runTest {
        val readable = MutableStateFlow(true)
        var elapsed = false
        backgroundScope.launch {
            awaitMemoryBubbleIdle(readable, MutableSharedFlow(), idleMs = MemoryBubbleIdleMs)
            elapsed = true
        }
        step(14_000L)
        // A detail page over Home: the clock doesn't run under it.
        readable.value = false
        step(60_000L)
        assertFalse(elapsed)

        readable.value = true
        step(MemoryBubbleIdleMs - 1)
        assertFalse(elapsed)
        step(1L)
        assertTrue(elapsed)
    }

    private fun fullySaid(unroll: Float, unrolling: Boolean, flyAway: Float = 0f, flyingAway: Boolean = false) =
        memoryBubbleFullySaid(unroll = unroll, unrolling = unrolling, flyAway = flyAway, flyingAway = flyingAway)

    /** The system's "Time to take action": never shorter than asked. */
    private class FakeAccessibility(private val userTimeoutMs: Long) : AccessibilityManager {
        var asked: Triple<Boolean, Boolean, Boolean>? = null

        override fun calculateRecommendedTimeoutMillis(
            originalTimeoutMillis: Long,
            containsIcons: Boolean,
            containsText: Boolean,
            containsControls: Boolean
        ): Long {
            asked = Triple(containsIcons, containsText, containsControls)
            return maxOf(originalTimeoutMillis, userTimeoutMs)
        }
    }

    /** Moves the virtual clock [ms] on and runs whatever is due then. */
    private fun TestScope.step(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }
}
