package com.gpo.yoin.ui.home.edit

import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSection.Activities
import com.gpo.yoin.ui.home.HomeSection.JumpBackIn
import com.gpo.yoin.ui.home.HomeSection.RecentlyAdded
import com.gpo.yoin.ui.home.HomeSection.Rediscover
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strip carry against a fake feed, on a frame clock that drops
 * cancelled frames as the app's does. Density 2: the safe area is
 * 88..1344px, so four strips are 128px tall on a 144px pitch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeCarryEngineTest {

    private val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = false)
    private val density = Density(2f)
    private val feed = listOf(Activities, JumpBackIn, RecentlyAdded, Rediscover)

    /** Plates in Box px; a null plate is not laid out, [above] picks its side. */
    private class FakeHost(
        var order: List<HomeSection>,
        val plates: Map<HomeSection, Rect?>,
        val above: Set<HomeSection> = emptySet(),
    ) : CarryHost {
        val anchors = mutableListOf<Triple<HomeSection, List<HomeSection>, Float>>()

        override fun displayedOrder(): List<HomeSection> = order

        override fun plateRect(section: HomeSection): Rect? = plates[section]

        override fun isAbove(section: HomeSection): Boolean = section in above

        override fun safeArea(): HomeEditSafeArea = HomeEditSafeArea(top = SafeTop, bottom = SafeBottom)

        override fun contentLeft(): Float = ContentLeft

        override fun contentWidth(): Float = ContentWidth

        override suspend fun anchorAndAwaitLayout(dropped: HomeSection, order: List<HomeSection>, slotTop: Float) {
            anchors += Triple(dropped, order, slotTop)
        }
    }

    private inner class Rig(scope: CoroutineScope, private val test: TestScope, host: FakeHost) {
        var editing by mutableStateOf(true)
        val haptics = RecordingHomeEditFeedback()
        val motion = HomeEditMotion(
            scope = scope,
            specs = specs,
            style = HomeWiggleStyle(),
            reduced = { false },
            progress = { if (editing) 1f else 0f },
            editing = { editing },
            feedback = haptics,
            uptimeMs = ::now,
        )
        val committed = mutableListOf<List<HomeSection>>()
        val exits = mutableListOf<HomeEditExitReason>()
        val engine = HomeCarryEngine(
            scope = scope,
            specs = specs,
            motion = motion,
            feedback = haptics,
            density = density,
            // The controller's half: it confirms a changed order.
            commitOrder = {
                committed += it
                haptics.confirm()
                true
            },
            finishDeferredExit = { exits += it },
            uptimeMs = ::now,
        ).also { it.host = host }

        fun now(): Long = test.testScheduler.currentTime

        /** Lifts [section] and starts the carry with the finger at [fingerY]. */
        fun carry(section: HomeSection, fingerY: Float) {
            motion.onEnter(section, lifted = true)
            engine.liftBlock(section, Offset(20f, 20f))
            engine.start(fingerY, now())
        }

        val session: CarrySession get() = checkNotNull(engine.session) { "no carry" }

        fun slotY(index: Int): Float = session.metrics.slotY(index)

        /** Where [section]'s strip sits on screen now (its slot plus its make-way offset). */
        fun shownY(section: HomeSection): Float = slotY(session.ids.indexOf(section)) + engine.stripOffset(section)
    }

    private fun TestScope.rig(scope: CoroutineScope, host: FakeHost = FakeHost(feed, feedPlates)) =
        Rig(scope, this, host)

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

    /** Frames until the carry is over (or [limitMs] passes). */
    private fun TestScope.untilFinished(rig: Rig, limitMs: Long = 3000) {
        var elapsed = 0L
        while (rig.engine.session != null && elapsed < limitMs) {
            frames(16)
            elapsed += 16
        }
        assertNull("the carry should have finished", rig.engine.session)
    }

    @Test
    fun should_centreCarriedStripOnFinger_when_started() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(JumpBackIn, fingerY = 600f)
        assertEquals(1, rig.session.k)
        assertEquals(feed, rig.session.ids)
        assertTrue(rig.engine.isCarrying)
        assertFalse(rig.engine.isBusy)
        assertTrue(rig.motion.carryActive)
        assertTrue(rig.motion.placeholdersAboveOpen)
        // 4 strips on the 1256px safe height: h_s caps at 64dp, 8dp gaps.
        assertEquals(128f, rig.session.metrics.height, 0f)
        assertEquals(144f, rig.session.metrics.pitch, 0f)
        assertEquals(rig.slotY(1), rig.engine.holeY.value, 0f)

        frames(800)
        assertEquals(1f, rig.engine.fold.value, 0f)
        val frame = checkNotNull(rig.engine.frameFor(JumpBackIn))
        // The lift grows the strip about its centre, so the centre stays on the finger.
        assertEquals(600f, frame.rect.center.y, .01f)
        assertEquals(ContentLeft + ContentWidth / 2f, frame.rect.center.x, .01f)
        assertTrue(frame.shadowAlpha > .99f)
    }

    @Test
    fun should_swapAndShiftNeighbour_when_dragPastHalfPitch() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(JumpBackIn, fingerY = 600f)
        frames(400)
        val recentlyAddedY = rig.shownY(RecentlyAdded)

        rig.engine.move(670f, rig.now())
        assertEquals(feed, rig.session.ids)
        rig.engine.move(680f, rig.now())
        assertEquals(listOf(Activities, RecentlyAdded, JumpBackIn, Rediscover), rig.session.ids)
        assertEquals(2, rig.session.k)
        // The neighbour keeps its place on screen, then makes way.
        assertEquals(144f, rig.engine.stripOffset(RecentlyAdded), 0f)
        assertEquals(recentlyAddedY, rig.shownY(RecentlyAdded), .01f)
        // 80px down, one pitch spent on the swap: the strip still sits under the finger.
        assertEquals(80f - 144f, rig.session.display, .01f)

        frames(600)
        assertEquals(0f, rig.engine.stripOffset(RecentlyAdded), 0f)
        assertEquals(rig.slotY(2), rig.engine.holeY.value, 0f)
        assertEquals(680f, rig.engine.frameFor(JumpBackIn)!!.rect.center.y, .01f)
    }

    @Test
    fun should_keepNeighbourScreenPosition_when_swappedBackMidFlight() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(JumpBackIn, fingerY = 600f)
        frames(400)
        rig.engine.move(680f, rig.now())
        frames(48)
        val inFlight = rig.engine.stripOffset(RecentlyAdded)
        assertTrue(inFlight > 10f && inFlight < 144f)
        val shown = rig.shownY(RecentlyAdded)

        // A quick swap back: the neighbour turns round from where it is, no jump.
        rig.engine.move(590f, rig.now())
        assertEquals(feed, rig.session.ids)
        assertEquals(shown, rig.shownY(RecentlyAdded), .01f)
        assertEquals(inFlight - 144f, rig.engine.stripOffset(RecentlyAdded), .01f)

        frames(600)
        assertEquals(0f, rig.engine.stripOffset(RecentlyAdded), 0f)
    }

    @Test
    fun should_tickOncePerSwap() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(Activities, fingerY = 300f)
        frames(400)
        rig.haptics.clear()

        // Two and a half pitches in one event: two swaps, two ticks.
        rig.engine.move(550f, rig.now())
        assertEquals(2, rig.session.k)
        assertEquals(listOf("segmentTick", "segmentTick"), rig.haptics.events)

        rig.engine.move(560f, rig.now())
        assertEquals(2, rig.haptics.events.size)
        rig.engine.move(400f, rig.now())
        assertEquals(1, rig.session.k)
        assertEquals(listOf("segmentTick", "segmentTick", "segmentTick"), rig.haptics.events)
    }

    @Test
    fun should_rubberBandAndThresholdOnce_when_draggingPastEnds() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(Activities, fingerY = 300f)
        frames(400)
        rig.haptics.clear()

        rig.engine.move(298f, rig.now())
        // Resistance from the first px, but no haptic within 2dp.
        assertEquals(-rubber(2f, density), rig.session.display, .001f)
        assertTrue(rig.haptics.events.isEmpty())
        rig.engine.move(290f, rig.now())
        rig.engine.move(250f, rig.now())
        rig.engine.move(200f, rig.now())
        assertEquals(-rubber(100f, density), rig.session.display, .001f)
        assertEquals(listOf("threshold"), rig.haptics.events)

        // Back inside and out again: one more.
        rig.engine.move(310f, rig.now())
        assertEquals(10f, rig.session.display, .001f)
        rig.engine.move(280f, rig.now())
        assertEquals(listOf("threshold", "threshold"), rig.haptics.events)

        // The far end too.
        val end = rig(scope)
        end.carry(Rediscover, fingerY = 900f)
        frames(400)
        end.haptics.clear()
        end.engine.move(1000f, end.now())
        assertEquals(rubber(100f, density), end.session.display, .001f)
        assertEquals(listOf("threshold"), end.haptics.events)
    }

    @Test
    fun should_flingAtMostOneExtraSlot_when_releaseVelocityAbove1600() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(Activities, fingerY = 300f)
        frames(400)
        val t0 = rig.now()
        // 80px every 16ms: 5000px/s, 2500dp/s (the clamp would allow far more).
        rig.engine.move(380f, t0 + 16)
        rig.engine.move(460f, t0 + 32)
        assertEquals(1, rig.session.k)
        rig.engine.release(cancelled = false, uptimeMs = t0 + 32)
        assertEquals(2, rig.session.k)
        assertEquals(listOf(JumpBackIn, RecentlyAdded, Activities, Rediscover), rig.session.ids)
        assertTrue(rig.engine.isBusy)
        // Settles from where it showed (slot 1 + 16px), now one slot further on.
        assertEquals(rig.slotY(1) + 16f - rig.slotY(2), rig.engine.settleY.value, .01f)

        untilFinished(rig)
        assertEquals(listOf(listOf(JumpBackIn, RecentlyAdded, Activities, Rediscover)), rig.committed)
    }

    @Test
    fun should_notFling_when_releaseIsSlow() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(Activities, fingerY = 300f)
        frames(400)
        val t0 = rig.now()
        // 1500dp/s: under the fling speed.
        rig.engine.move(348f, t0 + 16)
        rig.engine.move(396f, t0 + 32)
        rig.engine.release(cancelled = false, uptimeMs = t0 + 32)
        assertEquals(1, rig.session.k)
    }

    @Test
    fun should_commitIds_when_settleEndsWithChange() = runEditClockTest { scope ->
        val host = FakeHost(feed, feedPlates)
        val rig = rig(scope, host)
        rig.carry(JumpBackIn, fingerY = 600f)
        frames(400)
        // One slot down and 36px on: the strip has a way to settle.
        rig.engine.move(780f, rig.now())
        frames(200)
        rig.engine.release(cancelled = false, uptimeMs = rig.now())
        assertEquals(CarryPhase.Settle, rig.session.phase)
        assertEquals(36f, rig.engine.settleY.value, .01f)
        frames(96)
        assertTrue("no commit before the settle ends", rig.committed.isEmpty())

        untilFinished(rig)
        val order = listOf(Activities, RecentlyAdded, JumpBackIn, Rediscover)
        assertEquals(listOf(order), rig.committed)
        assertEquals(1, rig.haptics.events.count { it == "confirm" })
        assertEquals(1, host.anchors.size)
        val (dropped, anchoredOrder, slotTop) = host.anchors.single()
        assertEquals(JumpBackIn, dropped)
        assertEquals(order, anchoredOrder)
        assertEquals(392f + 2 * 144f, slotTop, .01f)
        assertEquals(0f, rig.engine.fold.value, 0f)
        assertEquals(0f, rig.engine.lift.value, 0f)
        assertNull(rig.engine.liftSection)
        assertFalse(rig.motion.carryActive)
    }

    @Test
    fun should_notConfirm_when_orderUnchanged() = runEditClockTest { scope ->
        val host = FakeHost(feed, feedPlates)
        val rig = rig(scope, host)
        rig.carry(JumpBackIn, fingerY = 600f)
        frames(400)
        rig.engine.move(640f, rig.now())
        rig.engine.move(600f, rig.now())
        frames(200)
        rig.engine.release(cancelled = false, uptimeMs = rig.now())

        untilFinished(rig)
        assertTrue(rig.committed.isEmpty())
        assertFalse("confirm" in rig.haptics.events)
        // The drop still anchors and unfolds.
        assertEquals(1, host.anchors.size)
        assertEquals(0f, rig.engine.fold.value, 0f)
    }

    @Test
    fun should_regrabOnlyWhileSettling() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(JumpBackIn, fingerY = 600f)
        frames(400)
        val inStrip = { Offset(ContentLeft + 100f, rig.slotY(rig.session.k) + rig.engine.settleY.value + 40f) }
        assertFalse("not while dragging", rig.engine.tryRegrab(inStrip(), rig.now()))

        rig.engine.move(780f, rig.now())
        frames(200)
        rig.engine.release(cancelled = false, uptimeMs = rig.now())
        frames(48)
        assertTrue(abs(rig.engine.settleY.value) > 1f)
        assertFalse("off the strip", rig.engine.tryRegrab(Offset(ContentLeft + 100f, 90f), rig.now()))
        assertFalse("past the side", rig.engine.tryRegrab(inStrip().copy(x = ContentLeft - 2f), rig.now()))
        rig.haptics.clear()
        val settle = rig.engine.settleY.value
        assertTrue(rig.engine.tryRegrab(inStrip(), rig.now()))
        assertEquals(CarryPhase.Drag, rig.session.phase)
        assertEquals(settle, rig.session.display, .001f)
        assertEquals(listOf("dragStart"), rig.haptics.events)

        // Caught: the commit never runs.
        frames(1500)
        assertTrue(rig.committed.isEmpty())
        assertEquals(CarryPhase.Drag, rig.session.phase)

        rig.engine.release(cancelled = false, uptimeMs = rig.now())
        var elapsed = 0L
        while (rig.engine.session?.phase != CarryPhase.Unfold && elapsed < 2000) {
            frames(16)
            elapsed += 16
        }
        assertEquals(CarryPhase.Unfold, rig.engine.session?.phase)
        assertFalse("never while unfolding", rig.engine.tryRegrab(inStrip(), rig.now()))
        untilFinished(rig)
        assertEquals(1, rig.committed.size)
    }

    @Test
    fun should_animateHoleNotSnap_when_regrabbed() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(Activities, fingerY = 300f)
        frames(400)
        val t0 = rig.now()
        rig.engine.move(380f, t0 + 16)
        rig.engine.move(460f, t0 + 32)
        // The fling moves the slot (and the hole) one further.
        rig.engine.release(cancelled = false, uptimeMs = t0 + 32)
        frames(32)
        val hole = rig.engine.holeY.value
        assertNotEquals(rig.slotY(2), hole)

        val y = rig.slotY(rig.session.k) + rig.engine.settleY.value + 40f
        assertTrue(rig.engine.tryRegrab(Offset(ContentLeft + 100f, y), rig.now()))
        runCurrent()
        assertTrue(rig.engine.holeY.isRunning)
        assertEquals(hole, rig.engine.holeY.value, 1f)
        frames(600)
        assertEquals(rig.slotY(2), rig.engine.holeY.value, 0f)
    }

    @Test
    fun should_runDeferredExitAtFinish() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(JumpBackIn, fingerY = 600f)
        frames(400)
        rig.engine.move(744f, rig.now())

        rig.engine.deferExit(HomeEditExitReason.Done)
        // Released as cancelled at the slot it is over; the exit waits.
        assertEquals(CarryPhase.Settle, rig.session.phase)
        assertTrue(rig.exits.isEmpty())
        // The swallowed finger moves no more.
        rig.engine.move(900f, rig.now())
        assertEquals(2, rig.session.k)

        untilFinished(rig)
        assertEquals(listOf(HomeEditExitReason.Done), rig.exits)
        assertEquals(listOf(listOf(Activities, RecentlyAdded, JumpBackIn, Rediscover)), rig.committed)
    }

    @Test
    fun should_commitChangedOrder_when_cancelled() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(Activities, fingerY = 300f)
        frames(400)
        val t0 = rig.now()
        rig.engine.move(380f, t0 + 16)
        rig.engine.move(460f, t0 + 32)
        // A system cancel: no fling, but the order it reached still commits.
        rig.engine.release(cancelled = true, uptimeMs = t0 + 32)
        assertEquals(1, rig.session.k)

        untilFinished(rig)
        assertEquals(listOf(listOf(JumpBackIn, Activities, RecentlyAdded, Rediscover)), rig.committed)
    }

    @Test
    fun should_kickCarriedAndMovedNeighboursAtFinish() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(JumpBackIn, fingerY = 600f)
        frames(400)
        rig.engine.move(744f, rig.now())
        frames(200)
        rig.engine.release(cancelled = false, uptimeMs = rig.now())
        untilFinished(rig)

        val peaks = feed.associateWith { 0f }.toMutableMap()
        frames(600) { feed.forEach { peaks[it] = max(peaks.getValue(it), abs(rig.motion.blockKick(it))) } }
        assertEquals(HomeEditTokens.KickDrop, peaks.getValue(JumpBackIn), .03f)
        assertEquals(HomeEditTokens.KickNeighbour, peaks.getValue(RecentlyAdded), .03f)
        assertEquals(0f, peaks.getValue(Activities), 0f)
        assertEquals(0f, peaks.getValue(Rediscover), 0f)
    }

    @Test
    fun should_commitAndTearDown_when_abortNow() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.carry(JumpBackIn, fingerY = 600f)
        frames(400)
        rig.engine.move(680f, rig.now())
        rig.engine.deferExit(HomeEditExitReason.Back)
        frames(32)

        rig.engine.abortNow()
        // Committed in the call, before anything else moves.
        assertEquals(listOf(listOf(Activities, RecentlyAdded, JumpBackIn, Rediscover)), rig.committed)
        assertNull(rig.engine.session)
        assertFalse(rig.engine.isCarrying)
        assertNull(rig.engine.liftSection)
        assertFalse(rig.motion.carryActive)
        runCurrent()
        assertEquals(0f, rig.engine.fold.value, 0f)
        assertEquals(0f, rig.engine.lift.value, 0f)
        assertEquals(0f, rig.engine.liftTint.value, 0f)
        assertEquals(0f, rig.engine.settleY.value, 0f)
        assertEquals(0f, rig.engine.holeY.value, 0f)
        assertEquals(0f, rig.engine.stripOffset(RecentlyAdded), 0f)

        frames(1500)
        // The snap exit completes the exit itself: no deferred exit, no second commit.
        assertTrue(rig.exits.isEmpty())
        assertEquals(1, rig.committed.size)
        assertEquals(0f, rig.engine.fold.value, 0f)
    }

    @Test
    fun should_passFullPermutation_when_carryStartsWithDeferredPlaceholderAbove() = runEditClockTest { scope ->
        // Jump Back In is empty and held back above the lifted block: not laid out yet.
        val order = listOf(Activities, JumpBackIn, RecentlyAdded)
        val host = FakeHost(order, feedPlates - JumpBackIn, above = setOf(JumpBackIn))
        val rig = rig(scope, host)
        rig.carry(RecentlyAdded, fingerY = 1000f)
        assertEquals(order, rig.session.ids)
        val start = rig.session.starts.getValue(JumpBackIn)
        assertEquals(0f, start.alpha, 0f)
        assertEquals(SafeTop + rig.session.metrics.height * .02f, start.rect.top, .01f)

        frames(400)
        rig.engine.move(900f, rig.now())
        frames(200)
        rig.engine.release(cancelled = false, uptimeMs = rig.now())
        untilFinished(rig)
        assertEquals(listOf(listOf(Activities, RecentlyAdded, JumpBackIn)), rig.committed)
    }

    @Test
    fun should_dropLift_when_releasedWithoutCarry() = runEditClockTest { scope ->
        val rig = rig(scope, FakeHost(listOf(Activities), feedPlates))
        // The lifted block is not in the order: no carry starts.
        rig.carry(Rediscover, fingerY = 600f)
        assertNull(rig.engine.session)
        frames(200)
        rig.engine.release(cancelled = false, uptimeMs = rig.now())
        frames(800)
        assertEquals(0f, rig.engine.lift.value, 0f)
        assertNull(rig.engine.liftSection)
    }

    private companion object {
        const val SafeTop = 88f
        const val SafeBottom = 1344f
        const val ContentLeft = 32f
        const val ContentWidth = 760f

        val feedPlates: Map<HomeSection, Rect?> = mapOf(
            Activities to Rect(16f, 100f, 808f, 500f),
            JumpBackIn to Rect(16f, 520f, 808f, 900f),
            RecentlyAdded to Rect(16f, 920f, 808f, 1300f),
            Rediscover to null,
        )
    }
}
