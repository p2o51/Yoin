package com.gpo.yoin.ui.home.edit

import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.gpo.yoin.testutil.virtualUptimeMs
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSection.Activities
import com.gpo.yoin.ui.home.HomeSection.JumpBackIn
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCarryLiftTest {

    private val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = false)

    private inner class Rig(scope: CoroutineScope, uptimeMs: () -> Long) {
        var editing by mutableStateOf(true)
        val motion = HomeEditMotion(
            scope = scope,
            specs = specs,
            style = HomeWiggleStyle(),
            reduced = { false },
            progress = { if (editing) 1f else 0f },
            editing = { editing },
            feedback = HomeEditFeedback.None,
            uptimeMs = uptimeMs,
        )
        val press = HomeEditPressState()
        val committed = mutableListOf<List<HomeSection>>()
        val exits = mutableListOf<HomeEditExitReason>()
        val engine = HomeCarryEngine(
            scope = scope,
            specs = specs,
            motion = motion,
            feedback = HomeEditFeedback.None,
            density = Density(2f),
            commitOrder = {
                committed += it
                true
            },
            finishDeferredExit = { exits += it },
            uptimeMs = uptimeMs,
        )
    }

    private fun TestScope.rig(scope: CoroutineScope) = Rig(scope, virtualUptimeMs())

    private fun TestScope.frames(ms: Long, each: () -> Unit = {}) {
        var elapsed = 0L
        while (elapsed < ms) {
            Snapshot.sendApplyNotifications()
            advanceTimeBy(16)
            runCurrent()
            each()
            elapsed += 16
        }
    }

    @Test
    fun should_stopKicks_when_lifting() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.motion.impulse(Activities, 1f)
        rig.motion.impulseCard(Activities, 2, 1f)
        frames(48)
        assertTrue(abs(rig.motion.blockKick(Activities)) > .1f)
        assertTrue(abs(rig.motion.cardKick(Activities, 2)) > .1f)

        rig.engine.liftBlock(Activities, Offset(40f, 60f))
        runCurrent()
        assertEquals(0f, rig.motion.blockKick(Activities), 0f)
        assertEquals(0f, rig.motion.cardKick(Activities, 2), 0f)
        assertEquals(Activities, rig.engine.liftSection)
        assertEquals(Offset(40f, 60f), rig.engine.liftOrigin)

        var residue = 0f
        frames(500) { residue = max(residue, abs(rig.motion.blockKick(Activities))) }
        assertEquals(0f, residue, 0f)
        assertEquals(1f, rig.engine.lift.value, 0f)
        assertEquals(1f, rig.engine.liftTint.value, 0f)
    }

    @Test
    fun should_snapPreviousLift_when_liftingAnotherSection() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.engine.liftBlock(Activities, Offset.Zero)
        frames(64)
        assertTrue(rig.engine.lift.value > .2f)

        rig.engine.liftBlock(JumpBackIn, Offset(10f, 10f))
        runCurrent()
        // The new block lifts from 0 instead of inheriting a half-lifted value.
        assertEquals(0f, rig.engine.lift.value, 0f)
        assertEquals(0f, rig.engine.liftTint.value, 0f)
        assertEquals(JumpBackIn, rig.engine.liftSection)

        frames(400)
        assertEquals(1f, rig.engine.lift.value, 0f)
    }

    @Test
    fun should_kickAfterDrop_when_dropLift() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.engine.liftBlock(Activities, Offset.Zero)
        frames(400)

        rig.engine.dropLift(HomeEditTokens.KickTap)
        var peak = 0f
        frames(600) { peak = max(peak, abs(rig.motion.blockKick(Activities))) }
        assertEquals(HomeEditTokens.KickTap, peak, .03f)
        assertEquals(0f, rig.engine.lift.value, 0f)
        assertEquals(0f, rig.engine.liftTint.value, 0f)
        assertNull(rig.engine.liftSection)
    }

    @Test
    fun should_notKick_when_droppedWithZero() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.engine.liftBlock(Activities, Offset.Zero)
        frames(400)

        rig.engine.dropLift(0f)
        var peak = 0f
        frames(600) { peak = max(peak, abs(rig.motion.blockKick(Activities))) }
        assertEquals(0f, peak, 0f)
        assertNull(rig.engine.liftSection)
    }

    @Test
    fun should_dropLiftAndReleasePress_when_editExits() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.motion.onEnter(Activities, lifted = true)
        rig.press.start(scope, Activities, Offset(20f, 20f), specs)
        frames(160)
        rig.engine.liftBlock(Activities, Offset(20f, 20f))
        frames(160)
        assertTrue(rig.press.charge.value > .3f)

        // Done arrives while the finger still holds the block.
        rig.editing = false
        rig.engine.onExit(HomeEditExitReason.Done, rig.press)
        var kick = 0f
        frames(800) { kick = max(kick, abs(rig.motion.blockKick(Activities))) }
        assertEquals(0f, rig.engine.lift.value, 0f)
        assertNull(rig.engine.liftSection)
        assertEquals(0f, rig.press.charge.value, 0f)
        assertEquals(0f, kick, 0f)
        assertEquals(0f, rig.motion.envelope.value, 0f)
        assertEquals(1f, rig.motion.footerAlpha.value, 0f)
    }

    @Test
    fun should_snapLiftAndPress_when_snapExit() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.motion.onEnter(Activities, lifted = true)
        rig.press.start(scope, Activities, Offset.Zero, specs)
        rig.engine.liftBlock(Activities, Offset.Zero)
        frames(64)

        rig.editing = false
        rig.engine.onSnapExit(rig.press)
        runCurrent()
        assertEquals(0f, rig.engine.lift.value, 0f)
        assertEquals(0f, rig.engine.liftTint.value, 0f)
        assertNull(rig.engine.liftSection)
        assertEquals(0f, rig.press.charge.value, 0f)
        assertEquals(0f, rig.motion.trayAlpha.value, 0f)

        frames(200)
        assertEquals(0f, rig.engine.lift.value, 0f)
    }

    @Test
    fun should_finishDeferredExitAtOnce_when_noCarry() = runEditClockTest { scope ->
        val rig = rig(scope)
        assertEquals(false, rig.engine.isCarrying)
        assertEquals(false, rig.engine.isBusy)
        rig.engine.deferExit(HomeEditExitReason.Back)
        assertEquals(listOf(HomeEditExitReason.Back), rig.exits)
    }
}
