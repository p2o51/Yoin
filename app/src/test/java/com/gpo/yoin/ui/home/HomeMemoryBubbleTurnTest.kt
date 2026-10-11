package com.gpo.yoin.ui.home

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
            awaitMemoryBubbleDwell(fullySaid, dwellMs = MemoryBubbleDwellMs)
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
        assertFalse(memoryBubbleFullySaid(unroll = 0.6f, unrolling = true))
        // The Expressive spring overshoots past 1 on its way in.
        assertFalse(memoryBubbleFullySaid(unroll = 1.04f, unrolling = true))
        assertFalse(memoryBubbleFullySaid(unroll = 0.98f, unrolling = false))
        assertTrue(memoryBubbleFullySaid(unroll = 1f, unrolling = false))
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
        // Under a detail page (translucent: Home only pauses).
        assertFalse(memoryBubbleWindowReadable(Lifecycle.State.STARTED, focused = false))
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

    /** Moves the virtual clock [ms] on and runs whatever is due then. */
    private fun TestScope.step(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }
}
