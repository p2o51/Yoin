package com.gpo.yoin.ui.memories.award

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The award lifecycle (twostate4 `awardDue` / `awardOk` / `interruptAward` / `goTo`'s settle), on fake
 * targets: no Compose, no clock but the one each call is handed.
 */
class MemoriesAwardLifecycleTest {

    private class FakeRun : MemoriesAwardRun {
        var climaxedNow = false
        var interrupts = 0
        var pauses = 0
        var resumes = 0
        override val climaxed: Boolean get() = climaxedNow

        override fun interrupt() {
            interrupts++
        }

        override fun pauseBeats() {
            pauses++
        }

        override fun resumeBeats() {
            resumes++
        }
    }

    private class FakeTarget(override val hasAward: Boolean = true) : MemoriesAwardTarget {
        var pendings = 0
        val runs = mutableListOf<FakeRun>()

        override fun showPending() {
            pendings++
        }

        override fun playFirst(): MemoriesAwardRun = FakeRun().also(runs::add)
    }

    private fun onCard(
        key: String,
        fingerDown: Boolean = false,
        nearCard: Boolean = true,
        revealIn: Boolean = true,
        cardState: Boolean = true,
    ) = MemoriesAwardInputs(key, fingerDown, nearCard, revealIn, cardState)

    @Test
    fun should_award_once_per_open_when_card_first_settles() {
        val open = MemoriesAwardLifecycle()
        val m1 = FakeTarget()
        val m2 = FakeTarget()
        open.register("m1", m1)
        open.register("m2", m2)
        // not yet awarded: both wait in the award's first frame
        assertEquals(1, m1.pendings)
        assertEquals(1, m2.pendings)

        assertTrue(open.start(onCard("m1")))
        assertEquals(1, m1.runs.size)
        // settling on it again (back from a neighbour, a dot tap) never replays it
        assertNull(open.startDelayMs(onCard("m1"), nowMs = 10_000))
        assertFalse(open.start(onCard("m1")))
        assertEquals(1, m1.runs.size)

        // its page leaves and comes back (pager disposal): it stays finished, no waiting pose
        open.unregister("m1", m1)
        val m1Again = FakeTarget()
        open.register("m1", m1Again)
        assertEquals(0, m1Again.pendings)

        // a new open is a new lifecycle: every card earns its award again
        val reopen = MemoriesAwardLifecycle()
        val m1Reopened = FakeTarget()
        reopen.register("m1", m1Reopened)
        assertEquals(1, m1Reopened.pendings)
        assertTrue(reopen.start(onCard("m1")))
    }

    @Test
    fun should_delay_first_beat_120ms_after_lift() {
        val open = MemoriesAwardLifecycle()
        open.register("m2", FakeTarget())
        open.onFingerDown()
        // the finger is still on the glass: nothing is due
        assertNull(open.startDelayMs(onCard("m2", fingerDown = true), nowMs = 1_000))
        open.onFingerUp(nowMs = 1_000)
        // a settle 30ms after the lift waits the other 90ms
        assertEquals(90L, open.startDelayMs(onCard("m2"), nowMs = 1_030))
        assertEquals(120L, awardStartDelayMs(liftMs = 1_000, nowMs = 1_000))
        // a settle long after the lift starts at once
        assertEquals(0L, open.startDelayMs(onCard("m2"), nowMs = 1_500))
        assertEquals(0L, awardStartDelayMs(liftMs = 1_000, nowMs = 1_120))
    }

    @Test
    fun should_unaward_when_card_changes_before_climax() {
        val open = MemoriesAwardLifecycle()
        val m1 = FakeTarget()
        val m2 = FakeTarget()
        val m3 = FakeTarget()
        open.register("m1", m1)
        open.register("m2", m2)
        open.register("m3", m3)

        // m1's award is cut off before its climax: the card really changes to m2
        open.start(onCard("m1"))
        open.onCardTargeted("m2")
        assertEquals(1, m1.runs.single().interrupts)
        // still awarded until it is off screen; the pager settles on m2
        open.onPagerSettled("m2")
        assertFalse(open.isAwarded("m1"))
        assertEquals(2, m1.pendings) // waiting again, the whole award next time
        assertTrue(open.start(onCard("m1")))

        // m2 reaches its climax before the card changes: it counts
        open.start(onCard("m2"))
        m2.runs.single().climaxedNow = true
        open.onCardTargeted("m3")
        open.onPagerSettled("m3")
        assertTrue(open.isAwarded("m2"))
        assertEquals(1, m2.pendings)
    }

    @Test
    fun should_start_open_award_when_reveal_passes_85_percent() {
        assertFalse(revealInForAward(0.2f))
        assertFalse(revealInForAward(0.151f))
        assertTrue(revealInForAward(0.15f))
        assertTrue(revealInForAward(0f))

        val open = MemoriesAwardLifecycle()
        open.register("m1", FakeTarget())
        // Home's pull-down is still bringing the page in
        assertNull(open.startDelayMs(onCard("m1", revealIn = revealInForAward(0.4f)), nowMs = 5_000))
        // 85% of the way in: the first card's award is due
        assertEquals(0L, open.startDelayMs(onCard("m1", revealIn = revealInForAward(0.12f)), nowMs = 5_000))
    }

    @Test
    fun should_pause_beats_when_drag_starts_and_resume_when_drag_returns() {
        val open = MemoriesAwardLifecycle()
        val m1 = FakeTarget()
        open.register("m1", m1)
        open.register("m2", FakeTarget())
        open.start(onCard("m1"))
        val run = m1.runs.single()
        open.onDragStart()
        assertEquals(1, run.pauses)
        // the drag came back to the same card: the beats go on, nothing is interrupted
        open.onCardTargeted("m1")
        assertEquals(1, run.resumes)
        assertEquals(0, run.interrupts)
    }

    @Test
    fun should_hold_award_when_pager_is_not_near_card_or_not_in_card_state() {
        val open = MemoriesAwardLifecycle()
        open.register("m1", FakeTarget())
        assertNull(open.startDelayMs(onCard("m1", nearCard = false), nowMs = 0))
        assertNull(open.startDelayMs(onCard("m1", cardState = false), nowMs = 0))
        assertTrue(pagerNearCard(position = 1.1f, card = 1))
        assertFalse(pagerNearCard(position = 1.16f, card = 1))
        assertFalse(pagerNearCard(position = 0.5f, card = 1))
        assertTrue(pInCardState(0f))
        assertFalse(pInCardState(-0.05f))
    }

    @Test
    fun should_never_wait_or_award_when_card_is_unrated() {
        val open = MemoriesAwardLifecycle()
        val unrated = FakeTarget(hasAward = false)
        open.register("m2", unrated)
        assertEquals(0, unrated.pendings)
        assertNull(open.startDelayMs(onCard("m2"), nowMs = 0))
        assertFalse(open.start(onCard("m2")))
    }
}
