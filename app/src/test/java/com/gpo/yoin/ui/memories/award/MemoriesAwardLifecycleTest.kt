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

    private class FakeDiaryEmblem(override val hasAward: Boolean = true) : MemoriesDiaryEmblemTarget {
        val poses = mutableListOf<String>()

        override fun showWaiting(small: Boolean) {
            poses += if (small) "small" else "uncut"
        }

        override fun nod() {
            poses += "nod"
        }

        override fun showRest() {
            poses += "rest"
        }
    }

    private fun inDiary(key: String, fingerDown: Boolean = false, nearCard: Boolean = true) =
        MemoriesAwardInputs(key, fingerDown, nearCard, revealIn = true, cardState = false, diaryState = true)

    @Test
    fun should_nod_once_when_card_is_met_in_the_diary() {
        val open = MemoriesAwardLifecycle()
        open.register("m2", FakeTarget())
        val dem = FakeDiaryEmblem()
        open.registerDiary("m2", dem)
        // not yet awarded, not nodded: the 48 waits small
        assertEquals(listOf("small"), dem.poses)
        // in the diary a card is never awarded ...
        assertNull(open.startDelayMs(inDiary("m2"), nowMs = 0))
        // ... it nods, once the page has settled with the finger off
        assertFalse(open.nod(inDiary("m2", fingerDown = true)))
        assertFalse(open.nod(inDiary("m2", nearCard = false)))
        assertTrue(open.nod(inDiary("m2")))
        assertEquals(listOf("small", "nod"), dem.poses)
        assertFalse(open.nod(inDiary("m2")))
        // back on the card it earns its full award; the 48 stops waiting with it
        assertTrue(open.start(onCard("m2")))
        assertEquals("rest", dem.poses.last())
        assertTrue(pInDiaryState(0.99f, settling = false))
        assertFalse(pInDiaryState(0.99f, settling = true))
        assertFalse(pInDiaryState(0.9f, settling = false))
    }

    @Test
    fun should_land_uncut_without_a_nod_when_diary_opens_before_the_award() {
        val open = MemoriesAwardLifecycle()
        val m1 = FakeTarget()
        open.register("m1", m1)
        val dem = FakeDiaryEmblem()
        open.registerDiary("m1", dem)
        // the Diary button beat the award: the card was seen, so no nod; the 48 lands full size, uncut
        open.onDiaryOpening("m1")
        assertEquals(listOf("small", "uncut"), dem.poses)
        assertTrue(open.isNodded("m1"))
        assertFalse(open.nod(inDiary("m1")))
        // the award waits for the card
        assertFalse(open.isAwarded("m1"))
        assertTrue(open.start(onCard("m1")))

        // a running award is interrupted by the diary opening and still counts
        val m3 = FakeTarget()
        open.register("m3", m3)
        open.start(onCard("m3"))
        open.onDiaryOpening("m3")
        assertEquals(1, m3.runs.single().interrupts)
        assertTrue(open.isAwarded("m3"))
    }

    @Test
    fun should_hold_award_while_back_preview_runs() {
        val open = MemoriesAwardLifecycle()
        open.register("m1", FakeTarget())
        // the back preview parks q under the open gate: the award must not start under it (prototype busyQ)
        val blocked = MemoriesAwardInputs("m1", false, true, true, true, blocked = true)
        assertNull(open.startDelayMs(blocked, nowMs = 0))
        assertFalse(open.start(blocked))
        assertTrue(open.start(onCard("m1")))
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
