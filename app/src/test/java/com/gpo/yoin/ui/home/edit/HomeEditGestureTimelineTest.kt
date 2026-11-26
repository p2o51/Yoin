package com.gpo.yoin.ui.home.edit

import com.gpo.yoin.shared.home.HomeEditGestureAction
import com.gpo.yoin.shared.home.HomeEditGestureAction.Abandon
import com.gpo.yoin.shared.home.HomeEditGestureAction.BlockTap
import com.gpo.yoin.shared.home.HomeEditGestureAction.CarryRelease
import com.gpo.yoin.shared.home.HomeEditGestureAction.DropLift
import com.gpo.yoin.shared.home.HomeEditGestureAction.Lift
import com.gpo.yoin.shared.home.HomeEditGestureAction.StartCarry
import com.gpo.yoin.shared.home.HomeEditGestureEvent.Down
import com.gpo.yoin.shared.home.HomeEditGestureEvent.Move
import com.gpo.yoin.shared.home.HomeEditGestureEvent.Up
import com.gpo.yoin.shared.home.HomeEditGestureMachine
import com.gpo.yoin.shared.home.HomeEditGestureState
import com.gpo.yoin.shared.home.HomeEditGestureTimeline
import com.gpo.yoin.shared.home.HomeEditHit
import com.gpo.yoin.shared.home.HomePoint
import com.gpo.yoin.shared.home.HomeSize
import com.gpo.yoin.shared.home.LateTimerMs
import com.gpo.yoin.shared.home.StalledInputGraceMs
import com.gpo.yoin.ui.home.HomeSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector's clock: timers run on the pointer events' own stamps, so a
 * fling's moves handed over late (a long frame held them back past the body
 * hold's timer) never let the hold lift and carry the block it started on.
 * Batches are replayed as the detector gets them: a wake at the timer's
 * instant with no input, then the held-back samples, oldest first.
 */
class HomeEditGestureTimelineTest {

    private val section = HomeSection.Activities
    private val block = HomeEditHit.Block(section, HomePoint(40f, 60f), HomeSize(600f, 400f))
    private val point = HomePoint(300f, 500f)
    private val machine = HomeEditGestureMachine(longPressMs = LongPressMs, slopPx = Slop)
    private val actions = mutableListOf<HomeEditGestureAction>()
    private var flinging = false
    private val timeline = HomeEditGestureTimeline(machine, { flinging }) { event ->
        machine.handle(event).also { actions += it }
    }

    private fun downOnBlock(fling: Boolean = false) {
        timeline.start(0L)
        actions += machine.handle(Down(block, point, editing = true, flingInProgress = fling, t = 0L))
    }

    // A fling upward: 1000px in 150ms, sampled every 8ms from [fromMs].
    private fun flingSamples(fromMs: Long, untilMs: Long = fromMs + 150L): List<Move> =
        (fromMs..untilMs step 8L).map { t -> Move(point - HomePoint(0f, (t - fromMs) * 1000f / 150f), t) }

    private fun deliver(batch: List<Move>) = batch.forEach { timeline.sample(it) }

    @Test
    fun should_notLift_when_flingMovesStampedBeforeHoldArriveAfterLateWake() {
        downOnBlock()
        assertEquals(BodyHoldMs, timeline.untilDue())

        // The wait for the hold wakes 130ms late (a long frame): it waits for held-back input…
        assertTrue(timeline.graceAfter(wallMs = BodyHoldMs + 130L) > 0L)
        // …which comes in one batch, stamped from 20ms on.
        deliver(flingSamples(fromMs = 20L))

        assertFalse(actions.any { it is Lift })
        assertFalse(actions.any { it is StartCarry })
        assertTrue(Abandon in actions)
        assertEquals(HomeEditGestureState.Passive, machine.state)
        assertNull(machine.pendingTimer)
    }

    @Test
    fun should_takeLiftBack_when_movesStampedBeforeHoldComeAfterItFired() {
        downOnBlock()

        // No input even in the grace: the hold fires and lifts…
        timeline.timeout()
        assertTrue(actions.any { it is Lift })
        assertEquals(HomeEditGestureState.Lifted, machine.state)

        // …then the fling's moves from before it arrive: the finger never held still that long.
        actions.clear()
        deliver(flingSamples(fromMs = 20L, untilMs = 300L))
        timeline.sample(Up(310L))

        assertEquals(DropLift(0f), actions.first())
        assertFalse(actions.any { it is StartCarry })
        assertFalse(actions.any { it is CarryRelease })
    }

    @Test
    fun should_liftThenCarry_when_batchHeldStillPastHoldThenMoved() {
        downOnBlock()

        // One late batch: resting (within the slop) until after the hold, then a drag.
        deliver(
            listOf(
                Move(point + HomePoint(2f, 1f), 90L),
                Move(point + HomePoint(3f, 2f), 180L),
                Move(point + HomePoint(3f, -40f), 260L),
                Move(point + HomePoint(3f, -80f), 268L),
            ),
        )

        val lift = actions.indexOfFirst { it is Lift }
        val carry = actions.indexOfFirst { it is StartCarry }
        assertTrue(lift >= 0)
        assertTrue(carry > lift)
        assertEquals(StartCarry(point.y - 40f, 260L), actions[carry])
        assertEquals(HomeEditGestureState.Carry, machine.state)
    }

    @Test
    fun should_fireTimerAtItsInstant_when_batchStraddlesIt() {
        downOnBlock()

        // The first sample past the slop is stamped after the hold: the hold goes first, as it did.
        deliver(listOf(Move(point + HomePoint(0f, 2f), 150L), Move(point + HomePoint(0f, -30f), BodyHoldMs)))

        assertTrue(actions.any { it is Lift })
        assertEquals(HomeEditGestureState.Carry, machine.state)
        assertEquals(BodyHoldMs, timeline.now)
    }

    @Test
    fun should_notGrace_when_waitWokeOnTime() {
        downOnBlock()

        assertEquals(0L, timeline.graceAfter(wallMs = BodyHoldMs + 2L))
        assertEquals(StalledInputGraceMs, timeline.graceAfter(wallMs = BodyHoldMs + LateTimerMs + 1L))
    }

    @Test
    fun should_neitherLiftNorKick_when_pressStopsFling() {
        downOnBlock(fling = true)

        assertEquals(HomeEditGestureState.Passive, machine.state)
        assertNull(timeline.untilDue())
        // Held well past the body hold, then let go: nothing lifts, nothing kicks.
        timeline.sample(Move(point + HomePoint(1f, 1f), 400L))
        timeline.sample(Up(450L))

        assertFalse(actions.any { it is Lift })
        assertFalse(actions.any { it is BlockTap })
    }

    @Test
    fun should_notLift_when_feedFlingsAsHoldFires() {
        downOnBlock()
        flinging = true

        timeline.timeout()

        assertFalse(actions.any { it is Lift })
        assertEquals(HomeEditGestureState.Passive, machine.state)
    }

    @Test
    fun should_notLongPress_when_normalPressStopsFling() {
        timeline.start(0L)
        machine.handle(Down(block, point, editing = false, flingInProgress = true, t = 0L))

        assertEquals(HomeEditGestureState.Idle, machine.state)
        assertNull(machine.pendingTimer)
    }

    @Test
    fun should_countAsFlinging_when_scrollStoppedMomentsAgo() {
        var now = 1_000L
        var scrolling = false
        val watch = HomeEditFlingWatch(inProgress = { scrolling }, windowMs = 100L, uptimeMs = { now })
        assertFalse(watch.flinging())

        scrolling = true
        watch.onScrolling(true)
        assertTrue(watch.flinging())

        now = 1_400L
        scrolling = false
        // Read live before the change is reported: already stopped, but the report is what dates it.
        assertTrue(watch.flinging())
        watch.onScrolling(false)
        now = 1_460L
        assertTrue(watch.flinging())
        now = 1_500L
        assertFalse(watch.flinging())
    }

    private companion object {
        const val LongPressMs = 400L
        const val BodyHoldMs = 200L
        const val Slop = 8f
    }
}
