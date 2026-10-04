package com.gpo.yoin.ui.home.edit

import androidx.compose.material3.MotionScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import com.gpo.yoin.testutil.virtualUptimeMs
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.home.HomeEditSessionHints
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.Abandon
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.BlockTap
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.CarryMove
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.CarryRelease
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.ConsumeOther
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.ConsumeRest
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.DragStartHaptic
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.DropLift
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.Enter
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.ExitBlank
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.LatchPlate
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.Lift
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.LongPressHaptic
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.OpenPlaceholders
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.PointerDown
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.ReleaseCharge
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.StartCarry
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.StartCharge
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.Touch
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.TryRegrab
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.Cancel
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.CarryEnded
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.Down
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.EditEnded
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.Move
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.OtherPointer
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.Regrabbed
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.Timer
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.Up
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detector's state machine through its event and action API (port sheet §2.1), and its executor. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeEditGestureMachineTest {

    private val section = HomeSection.JumpBackIn
    private val local = Offset(40f, 60f)
    private val size = Size(328f, 420f)
    private val point = Offset(100f, 300f)
    private val block = HomeEditHit.Block(section, local, size)
    private val handle = HomeEditHit.Handle(section, Offset(300f, 20f), size)
    private val machine = HomeEditGestureMachine(longPressMs = LongPressMs, slopPx = Slop)
    private val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = false)
    private val density = Density(2f)

    private fun down(
        hit: HomeEditHit,
        editing: Boolean = false,
        carryBusy: Boolean = false,
        fling: Boolean = false,
        t: Long = 0L,
        secondaryClick: Boolean = false,
    ) = machine.handle(Down(hit, point, editing, carryBusy, fling, t, secondaryClick))

    private fun moveBy(dx: Float, dy: Float, t: Long = 0L) = machine.handle(Move(point + Offset(dx, dy), t))

    private fun fireNext(): List<HomeEditGestureAction> = machine.handle(Timer(machine.pendingTimer!!.kind))

    private fun liftByLongPress() {
        down(block)
        fireNext()
        fireNext()
        assertEquals(HomeEditGestureState.Lifted, machine.state)
    }

    // ── Normal mode ───────────────────────────────────────────────────────

    @Test
    fun should_abandon_when_movedPastSlopBeforeThreshold() {
        assertEquals(emptyList<HomeEditGestureAction>(), down(block))

        assertEquals(emptyList<HomeEditGestureAction>(), moveBy(0f, Slop))
        assertEquals(listOf(Abandon), moveBy(0f, Slop + 1f))

        assertEquals(HomeEditGestureState.Idle, machine.state)
        assertNull(machine.pendingTimer)
    }

    @Test
    fun should_abandonAndLetClickThrough_when_upBeforeThreshold() {
        down(block)
        assertEquals(listOf(Abandon), machine.handle(Up(150L)))
        assertEquals(HomeEditGestureState.Idle, machine.state)

        // After T/2 the charge has started, so it is released on the way out; still nothing consumed.
        down(block)
        fireNext()
        val actions = machine.handle(Up(250L))
        assertEquals(listOf(ReleaseCharge, Abandon), actions)
        assertFalse(ConsumeRest in actions)
    }

    @Test
    fun should_startChargeAtHalfT_when_pressOnBlock() {
        down(block, t = 1_000L)

        assertEquals(HomeEditTimerAt(HomeEditTimer.HalfT, 1_200L), machine.pendingTimer)
        assertEquals(listOf(StartCharge(section, local)), fireNext())
        assertEquals(HomeEditTimerAt(HomeEditTimer.LongPress, 1_400L), machine.pendingTimer)
        assertEquals(HomeEditGestureState.Pressing, machine.state)
    }

    @Test
    fun should_notCharge_when_pressOnBlank() {
        down(HomeEditHit.Blank(HomeSection.Activities), t = 1_000L)

        assertEquals(HomeEditTimerAt(HomeEditTimer.LongPress, 1_400L), machine.pendingTimer)
        assertEquals(emptyList<HomeEditGestureAction>(), machine.handle(Timer(HomeEditTimer.HalfT)))
    }

    @Test
    fun should_emitThresholdSequenceInOrder_when_longPressOnBlock() {
        down(block)
        fireNext()

        assertEquals(
            listOf(
                LongPressHaptic,
                Enter(section, lifted = true),
                LatchPlate(section, local, size),
                ReleaseCharge,
                Lift(section, local),
                ConsumeRest,
                PointerDown(true),
            ),
            fireNext(),
        )
        assertEquals(HomeEditGestureState.Lifted, machine.state)
        assertNull(machine.pendingTimer)
    }

    @Test
    fun should_enterWithoutLift_when_longPressOnBlank() {
        down(HomeEditHit.Blank(HomeSection.RecentlyAdded))

        assertEquals(
            listOf(
                LongPressHaptic,
                ReleaseCharge,
                Enter(HomeSection.RecentlyAdded, lifted = false),
                ConsumeRest,
                PointerDown(true),
            ),
            fireNext(),
        )
        assertEquals(HomeEditGestureState.Done, machine.state)
        assertEquals(emptyList<HomeEditGestureAction>(), moveBy(0f, 80f))
        assertEquals(listOf(PointerDown(false), Touch, OpenPlaceholders), machine.handle(Up(900L)))
    }

    @Test
    fun should_dropLiftWithHalfKick_when_liftedThenReleased() {
        liftByLongPress()

        val actions = machine.handle(Up(700L))

        assertEquals(listOf(PointerDown(false), Touch, DropLift(.5f), OpenPlaceholders), actions)
        assertEquals(HomeEditGestureState.Idle, machine.state)
    }

    @Test
    fun should_startCarry_when_liftedThenMovedPastSlopFromDown() {
        down(block)
        // Drifting under the slop before the threshold keeps the press.
        assertEquals(emptyList<HomeEditGestureAction>(), moveBy(4f, 3f))
        fireNext()
        fireNext()

        // Measured from the down point, not from the last move.
        assertEquals(emptyList<HomeEditGestureAction>(), moveBy(4f, 6f, t = 450L))
        assertEquals(listOf(StartCarry(point.y + 9f, 460L)), moveBy(0f, 9f, t = 460L))
        assertEquals(HomeEditGestureState.Carry, machine.state)
        assertEquals(listOf(CarryMove(point.y + 60f, 476L)), moveBy(0f, 60f, t = 476L))
    }

    @Test
    fun should_enterWithoutLift_when_mouseSecondaryPress() {
        assertEquals(
            listOf(Enter(section, lifted = false), ConsumeRest, PointerDown(true)),
            down(block, secondaryClick = true),
        )
        assertEquals(HomeEditGestureState.Done, machine.state)
        machine.handle(Up(80L))

        // On blank, the nearest section is the ripple's origin.
        val blank = down(HomeEditHit.Blank(HomeSection.Activities), secondaryClick = true)
        assertEquals(Enter(HomeSection.Activities, lifted = false), blank.first())
        machine.handle(Up(80L))

        // In edit mode the secondary button does nothing.
        assertEquals(emptyList<HomeEditGestureAction>(), down(block, editing = true, secondaryClick = true))
        assertEquals(HomeEditGestureState.Idle, machine.state)
    }

    // ── Edit mode ─────────────────────────────────────────────────────────

    @Test
    fun should_liftAfterBodyHold_when_editMode() {
        assertEquals(listOf(Touch, PointerDown(true)), down(block, editing = true, t = 0L))
        // max(150, T/2) = 200 at the default T.
        assertEquals(HomeEditTimerAt(HomeEditTimer.BodyHold, 200L), machine.pendingTimer)

        assertEquals(listOf(DragStartHaptic, Lift(section, local), ConsumeRest), fireNext())
        assertEquals(HomeEditGestureState.Lifted, machine.state)

        // A short accessibility timeout still waits 150ms.
        val quick = HomeEditGestureMachine(longPressMs = 200L, slopPx = Slop)
        quick.handle(Down(block, point, editing = true, carryBusy = false, flingInProgress = false, t = 0L))
        assertEquals(150L, quick.pendingTimer!!.atMs)
    }

    @Test
    fun should_liftAndCarryOnFirstSlop_when_dragHandle() {
        assertEquals(listOf(Touch, PointerDown(true)), down(handle, editing = true))
        assertNull(machine.pendingTimer)

        assertEquals(emptyList<HomeEditGestureAction>(), moveBy(0f, Slop))
        assertEquals(
            listOf(DragStartHaptic, Lift(section, handle.local), StartCarry(point.y + Slop + 1f, 30L), ConsumeRest),
            moveBy(0f, Slop + 1f, t = 30L),
        )
        assertEquals(HomeEditGestureState.Carry, machine.state)
    }

    @Test
    fun should_kickAndPulseNotExit_when_blockTappedInEdit() {
        down(block, editing = true)

        val actions = machine.handle(Up(90L))

        assertEquals(
            listOf(PointerDown(false), Touch, BlockTap(section, local), ConsumeRest, OpenPlaceholders),
            actions,
        )
        assertFalse(ExitBlank in actions)

        // A tap on the handle kicks the whole block.
        down(handle, editing = true)
        assertTrue(BlockTap(section, null) in machine.handle(Up(90L)))
    }

    @Test
    fun should_exit_when_blankTappedInEdit() {
        down(HomeEditHit.Blank(HomeSection.Activities), editing = true)

        assertEquals(
            listOf(PointerDown(false), Touch, ExitBlank, ConsumeRest, OpenPlaceholders),
            machine.handle(Up(90L)),
        )
    }

    @Test
    fun should_notExit_when_blankTapStopsFling() {
        down(HomeEditHit.Blank(null), editing = true, fling = true)

        assertFalse(ExitBlank in machine.handle(Up(60L)))
    }

    @Test
    fun should_notExit_when_blankHeldPastT() {
        down(HomeEditHit.Blank(null), editing = true)
        assertEquals(HomeEditTimerAt(HomeEditTimer.BlankHold, LongPressMs), machine.pendingTimer)
        fireNext()

        assertFalse(ExitBlank in machine.handle(Up(700L)))
    }

    @Test
    fun should_passScrollThroughAndStillLiftFinger_when_editPressMovesPastSlop() {
        down(block, editing = true)

        assertEquals(listOf(Abandon), moveBy(0f, Slop + 1f))
        assertEquals(HomeEditGestureState.Passive, machine.state)
        assertNull(machine.pendingTimer)
        // The up of the scroll still lifts the finger for the idle rule; no tap.
        assertEquals(listOf(PointerDown(false), Touch, OpenPlaceholders), machine.handle(Up(400L)))
    }

    @Test
    fun should_ignoreButTouch_when_excludedInEdit() {
        assertEquals(listOf(Touch), down(HomeEditHit.Excluded, editing = true))
        assertEquals(HomeEditGestureState.Idle, machine.state)

        assertEquals(emptyList<HomeEditGestureAction>(), down(HomeEditHit.Excluded))
        assertEquals(HomeEditGestureState.Idle, machine.state)
    }

    @Test
    fun should_regrab_when_settling() {
        assertEquals(
            listOf(Touch, PointerDown(true), TryRegrab(point, 10L), ConsumeRest),
            down(block, editing = true, carryBusy = true, t = 10L),
        )

        machine.handle(Regrabbed)

        assertEquals(HomeEditGestureState.Carry, machine.state)
        assertEquals(listOf(CarryMove(point.y + 30f, 40L)), moveBy(0f, 30f, t = 40L))
        assertTrue(CarryRelease(cancelled = false, 60L) in machine.handle(Up(60L)))
    }

    @Test
    fun should_ignore_when_unfolding() {
        // The strip was not caught: the whole press is swallowed, nothing else happens.
        val actions = down(block, editing = true, carryBusy = true)
        assertTrue(ConsumeRest in actions)
        assertEquals(HomeEditGestureState.Done, machine.state)

        assertEquals(emptyList<HomeEditGestureAction>(), moveBy(0f, 100f))
        assertEquals(emptyList<HomeEditGestureAction>(), machine.handle(Timer(HomeEditTimer.BodyHold)))
        // A late catch report is ignored.
        machine.handle(Regrabbed)
        assertEquals(HomeEditGestureState.Done, machine.state)
        assertEquals(listOf(PointerDown(false), Touch, OpenPlaceholders), machine.handle(Up(300L)))
    }

    @Test
    fun should_releaseCancelled_when_carryCancelled() {
        down(handle, editing = true)
        moveBy(0f, 20f)

        assertEquals(
            listOf(PointerDown(false), Touch, CarryRelease(cancelled = true, 120L), OpenPlaceholders),
            machine.handle(Cancel(120L)),
        )
        assertEquals(HomeEditGestureState.Idle, machine.state)
    }

    @Test
    fun should_consumeSecondPointer_when_busy() {
        assertEquals(emptyList<HomeEditGestureAction>(), machine.handle(OtherPointer))

        down(block)
        assertEquals(listOf(ConsumeOther), machine.handle(OtherPointer))
        fireNext()
        fireNext()
        assertEquals(listOf(ConsumeOther), machine.handle(OtherPointer))
        moveBy(0f, 40f)
        assertEquals(listOf(ConsumeOther), machine.handle(OtherPointer))
        // The state is unchanged by the other finger.
        assertEquals(HomeEditGestureState.Carry, machine.state)
    }

    // ── Errata ────────────────────────────────────────────────────────────

    @Test
    fun should_disownGesture_when_editEndsMidHold() {
        down(block, editing = true)

        assertEquals(listOf(ConsumeRest), machine.handle(EditEnded))
        assertEquals(HomeEditGestureState.Done, machine.state)
        // The body hold never lifts, and the up taps nothing.
        assertEquals(emptyList<HomeEditGestureAction>(), machine.handle(Timer(HomeEditTimer.BodyHold)))
        assertEquals(listOf(PointerDown(false), Touch, OpenPlaceholders), machine.handle(Up(400L)))
    }

    @Test
    fun should_keepNormalPress_when_editEndedArrivesOutsideEdit() {
        down(block)

        assertEquals(emptyList<HomeEditGestureAction>(), machine.handle(EditEnded))
        assertEquals(HomeEditGestureState.Pressing, machine.state)
    }

    @Test
    fun should_dropLiftAndOpenPlaceholders_when_liftedCancelled() {
        liftByLongPress()

        assertEquals(listOf(PointerDown(false), Touch, DropLift(0f), OpenPlaceholders), machine.handle(Cancel(600L)))
    }

    @Test
    fun should_swallowRest_when_carryEndedFromOutside() {
        down(handle, editing = true)
        moveBy(0f, 20f)

        machine.handle(CarryEnded)

        assertEquals(HomeEditGestureState.Done, machine.state)
        assertEquals(emptyList<HomeEditGestureAction>(), moveBy(0f, 60f))
        assertEquals(listOf(PointerDown(false), Touch, OpenPlaceholders), machine.handle(Up(200L)))
    }

    // ── Executor (critique 4, port sheet §2.3) ────────────────────────────

    private inner class Rig(scope: CoroutineScope, uptimeMs: () -> Long) {
        val controller = HomeEditController(
            store = ExperienceSessionStore(),
            scope = scope,
            applyLayout = {},
            onEditingChanged = {},
            startSession = { HomeEditSessionHints() },
            feedback = HomeEditFeedback.None,
            reducedMotion = { false },
        )
        val motion = HomeEditMotion(
            scope = scope,
            specs = specs,
            style = HomeWiggleStyle(),
            reduced = { false },
            progress = controller.progressReader,
            editing = { controller.isEditing },
            feedback = HomeEditFeedback.None,
            uptimeMs = uptimeMs,
        )
        val press = HomeEditPressState()
        val engine = HomeCarryEngine(
            scope = scope,
            specs = specs,
            motion = motion,
            feedback = HomeEditFeedback.None,
            density = density,
            commitOrder = { true },
            finishDeferredExit = {},
            uptimeMs = uptimeMs,
        )
        val executor = HomeEditGestureExecutor(
            controller = controller,
            motion = motion,
            press = press,
            engine = engine,
            feedback = HomeEditFeedback.None,
            specs = specs,
            density = density,
            scope = scope,
        )
    }

    @Test
    fun should_dropEditOnlyActions_when_notEditing() = runEditClockTest { scope ->
        val rig = Rig(scope, virtualUptimeMs())

        rig.executor.run(
            listOf(LatchPlate(section, local, size), Lift(section, local), StartCarry(300f, 0L), ExitBlank),
            machine,
        )

        assertNull(rig.motion.plateFrom)
        assertNull(rig.engine.liftSection)
        assertFalse(rig.controller.isEditing)
    }

    @Test
    fun should_latchChargeBeforeReleasingIt_when_thresholdRuns() = runEditClockTest { scope ->
        val rig = Rig(scope, virtualUptimeMs())
        rig.press.start(scope, section, local, specs)
        advanceTimeBy(200L)
        runCurrent()
        val charge = rig.press.charge.value
        assertTrue(charge > .5f)

        down(block)
        fireNext()
        rig.executor.run(fireNext(), machine)

        assertTrue(rig.controller.isEditing)
        assertEquals(PlateFrom(section, pressRect(local, size, charge, density), charge), rig.motion.plateFrom)
        assertEquals(section, rig.engine.liftSection)
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(0f, rig.press.charge.value, .002f)
    }

    private companion object {
        const val LongPressMs = 400L
        const val Slop = 8f
    }
}
