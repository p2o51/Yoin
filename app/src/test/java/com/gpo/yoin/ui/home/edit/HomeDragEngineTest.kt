package com.gpo.yoin.ui.home.edit

import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSection.Activities
import com.gpo.yoin.ui.home.HomeSection.JumpBackIn
import com.gpo.yoin.ui.home.HomeSection.MadeForYou
import com.gpo.yoin.ui.home.HomeSection.MemoryGrid
import com.gpo.yoin.ui.home.HomeSection.RecentlyAdded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-place drag (HomeDragEngine.kt) over a list that lays out whatever the drag arranges. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeDragEngineTest {

    private val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = false)

    /** Rows [RowHeight] tall, [Gap] apart from y = [Top], 1000px wide: the drag's arrangement laid out at once. */
    private class FakeHost(
        private val twoColumn: Boolean,
        private val order: List<HomeSection>,
        private val pairs: Map<HomeSection, HomeSection> = emptyMap(),
    ) : HomeDragHost {
        var engine: HomeDragEngine? = null
        val scrolls = mutableListOf<Float>()
        private var scrolled = 0f

        override fun order(): List<HomeSection> = order

        override fun twoColumn(): Boolean = twoColumn

        override fun pairs(): Map<HomeSection, HomeSection> = pairs

        override fun visibleRows(): List<DragItemBand> {
            val live = engine?.arrangement ?: DragArrangement(order, if (twoColumn) pairs else emptyMap())
            var y = Top - scrolled
            return dragRows(live.ids, live.pairs, twoColumn).map { row ->
                DragItemBand(row.first, y, y + RowHeight).also { y += RowHeight + Gap }
            }
        }

        override fun contentLeft(): Float = 0f

        override fun contentWidth(): Float = 1000f

        override fun columnGap(): Float = 20f

        override fun scrollArea(): HomeEditSafeArea = HomeEditSafeArea(top = 100f, bottom = 2000f)

        override fun scrollBy(px: Float): Float {
            scrolls += px
            scrolled += px
            return px
        }
    }

    private class Rig(scope: CoroutineScope, test: TestScope, val host: FakeHost, specs: HomeEditSpecs) {
        val orders = mutableListOf<List<HomeSection>>()
        val arrangements = mutableListOf<DragArrangement>()
        val exits = mutableListOf<HomeEditExitReason>()
        val motion = HomeEditMotion(
            scope = scope,
            specs = specs,
            style = HomeWiggleStyle(),
            reduced = { false },
            progress = { 1f },
            editing = { true },
            feedback = HomeEditFeedback.None,
            uptimeMs = { test.testScheduler.currentTime },
        )
        val engine = HomeDragEngine(
            scope = scope,
            specs = specs,
            motion = motion,
            feedback = HomeEditFeedback.None,
            density = Density(2f),
            commitOrder = {
                orders += it
                true
            },
            finishDeferredExit = { exits += it },
            uptimeMs = { test.testScheduler.currentTime },
            commitArrangement = { ids, pairs ->
                arrangements += DragArrangement(ids, pairs)
                true
            },
        ).also {
            it.host = host
            host.engine = it
        }

        fun lift(section: HomeSection, x: Float, y: Float) {
            engine.liftBlock(section, Offset.Zero)
            engine.fingerX = x
            engine.start(fingerY = y, uptimeMs = 0L)
        }

        fun moveTo(x: Float, y: Float) {
            engine.fingerX = x
            engine.move(y, 0L)
        }
    }

    private fun TestScope.frames(count: Int = 3) {
        repeat(count) {
            Snapshot.sendApplyNotifications()
            advanceTimeBy(16)
            runCurrent()
        }
    }

    @Test
    fun should_makeWayAndCommitOnce_when_draggedPastNeighbour() = runEditClockTest { scope ->
        // Activities 100..400, Made For You 418..718, All Memories 736..1036, Recently Added below.
        val rig = Rig(scope, this, FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid, RecentlyAdded)), specs)
        rig.lift(MadeForYou, x = 500f, y = 568f)
        assertEquals(MadeForYou, rig.engine.dragging)
        assertEquals(Offset.Zero, rig.engine.translation(MadeForYou, inList = false))

        // Its top to 618: past the halfway line to All Memories' slot (577).
        rig.moveTo(500f, 768f)
        frames()
        assertEquals(listOf(Activities, MemoryGrid, MadeForYou, RecentlyAdded), rig.engine.arrangement?.ids)
        // Laid out at 736 now, still drawn under the finger at 618.
        assertEquals(Offset(0f, -118f), rig.engine.translation(MadeForYou, inList = false))
        assertTrue(rig.engine.isCarrying)

        rig.engine.release(cancelled = false, uptimeMs = 0L)
        assertEquals(listOf(listOf(Activities, MemoryGrid, MadeForYou, RecentlyAdded)), rig.orders)
        assertNull(rig.engine.dragging)
        assertNull(rig.engine.arrangement)
        assertEquals(MadeForYou, rig.engine.settling)
        frames(60)
        assertNull(rig.engine.settling)
        assertNull(rig.engine.liftSection)
    }

    @Test
    fun should_notCommit_when_droppedWhereItStarted() = runEditClockTest { scope ->
        val rig = Rig(scope, this, FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid)), specs)
        rig.lift(MadeForYou, x = 500f, y = 568f)
        rig.moveTo(500f, 600f)
        frames()
        rig.engine.release(cancelled = false, uptimeMs = 0L)
        assertTrue(rig.orders.isEmpty())
    }

    @Test
    fun should_waitForTravel_when_layoutMovedUnderRestingFinger() = runEditClockTest { scope ->
        val rig = Rig(scope, this, FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid)), specs)
        rig.lift(MadeForYou, x = 500f, y = 568f)
        // 20px of travel at density 2 is under the 24px a change waits for.
        rig.moveTo(500f, 548f)
        frames()
        assertEquals(listOf(Activities, MadeForYou, MemoryGrid), rig.engine.arrangement?.ids)
    }

    @Test
    fun should_neverPair_inOneColumn() = runEditClockTest { scope ->
        val host = FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid), pairs = mapOf(Activities to MadeForYou))
        val rig = Rig(scope, this, host, specs)
        rig.lift(MemoryGrid, x = 500f, y = 886f)
        rig.moveTo(990f, 568f)
        frames()
        rig.engine.release(cancelled = false, uptimeMs = 0L)
        assertTrue(rig.arrangements.isEmpty())
        assertEquals(listOf(listOf(Activities, MemoryGrid, MadeForYou)), rig.orders)
    }

    @Test
    fun should_pairWithFullRow_when_fingerAtItsEdge() = runEditClockTest { scope ->
        // Two columns: Activities 100..400, Made For You 418..718, All Memories 736..1036.
        val rig = Rig(scope, this, FakeHost(twoColumn = true, order = listOf(Activities, MadeForYou, MemoryGrid)), specs)
        rig.lift(MemoryGrid, x = 500f, y = 886f)
        rig.moveTo(950f, 568f)
        frames()
        assertEquals(mapOf(MadeForYou to MemoryGrid), rig.engine.arrangement?.pairs)
        rig.engine.release(cancelled = false, uptimeMs = 0L)
        assertEquals(listOf(DragArrangement(listOf(Activities, MadeForYou, MemoryGrid), mapOf(MadeForYou to MemoryGrid))), rig.arrangements)
        assertTrue(rig.orders.isEmpty())
    }

    @Test
    fun should_swapAtMiddleAndStayBesidePartner_untilTakenOutOfTheRow() = runEditClockTest { scope ->
        // Activities | Jump Back In 100..400, All Memories 418..718; JBI is the right half (x 510..1000).
        val host = FakeHost(twoColumn = true, order = listOf(Activities, JumpBackIn, MemoryGrid), pairs = mapOf(Activities to JumpBackIn))
        val rig = Rig(scope, this, host, specs)
        rig.lift(JumpBackIn, x = 700f, y = 250f)
        assertEquals(mapOf(Activities to JumpBackIn), rig.engine.arrangement?.pairs)
        assertEquals(Offset.Zero, rig.engine.translation(JumpBackIn, inList = false))

        // Across the middle but short of the 45% line: it stays on the right.
        rig.moveTo(460f, 300f)
        frames()
        assertEquals(mapOf(Activities to JumpBackIn), rig.engine.arrangement?.pairs)

        // Past it: the two swap, never full width.
        rig.moveTo(400f, 300f)
        frames()
        assertEquals(DragArrangement(listOf(JumpBackIn, Activities, MemoryGrid), mapOf(JumpBackIn to Activities)), rig.engine.arrangement)

        // Back over the middle: 0.5 is inside the hysteresis, it stays on the left.
        rig.moveTo(500f, 300f)
        frames()
        assertEquals(mapOf(JumpBackIn to Activities), rig.engine.arrangement?.pairs)

        // Just under the row, inside its 16dp (32px) buffer: still a pair.
        rig.moveTo(500f, 428f)
        frames()
        assertEquals(mapOf(JumpBackIn to Activities), rig.engine.arrangement?.pairs)

        // Out of the row, onto All Memories' top quarter: a row of its own above it, Activities full.
        rig.moveTo(500f, 440f)
        frames()
        assertEquals(DragArrangement(listOf(Activities, JumpBackIn, MemoryGrid), emptyMap()), rig.engine.arrangement)
        rig.engine.release(cancelled = false, uptimeMs = 0L)
        assertEquals(listOf(DragArrangement(listOf(Activities, JumpBackIn, MemoryGrid), emptyMap())), rig.arrangements)
    }

    @Test
    fun should_keepFingerAtSameFractionOfBlock_when_halfTurnsFull() = runEditClockTest { scope ->
        // JBI (right half, x 510..1000) grabbed 190px in; out of its row it is full (x 0..1000).
        val host = FakeHost(twoColumn = true, order = listOf(Activities, JumpBackIn, MemoryGrid), pairs = mapOf(Activities to JumpBackIn))
        val rig = Rig(scope, this, host, specs)
        rig.lift(JumpBackIn, x = 700f, y = 250f)
        rig.moveTo(700f, 440f)
        frames()
        assertEquals(emptyMap<HomeSection, HomeSection>(), rig.engine.arrangement?.pairs)
        // 190 / 490 of the way across a 1000px block: its left edge at 700 − 387.8.
        assertEquals(700f - 190f / 490f * 1000f, rig.engine.translation(JumpBackIn, inList = false).x, .5f)
    }

    @Test
    fun should_markDropSlot_whileDraggedInItsOwnHost() = runEditClockTest { scope ->
        val rig = Rig(scope, this, FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid)), specs)
        assertFalse(rig.engine.slotShown(MadeForYou, inList = false))
        rig.lift(MadeForYou, x = 500f, y = 568f)
        assertTrue(rig.engine.slotShown(MadeForYou, inList = false))
        assertFalse(rig.engine.slotShown(MadeForYou, inList = true))
        assertFalse(rig.engine.slotShown(Activities, inList = false))
        rig.engine.release(cancelled = false, uptimeMs = 0L)
        // Still marked as it glides in, gone once it has landed.
        assertTrue(rig.engine.slotShown(MadeForYou, inList = false))
        frames(60)
        assertFalse(rig.engine.slotShown(MadeForYou, inList = false))
    }

    @Test
    fun should_scrollTowardEnd_when_fingerNearBottom() = runEditClockTest { scope ->
        val host = FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid, RecentlyAdded))
        val rig = Rig(scope, this, host, specs)
        rig.lift(Activities, x = 500f, y = 250f)
        rig.moveTo(500f, 1990f)
        frames(30)
        assertTrue(host.scrolls.isNotEmpty())
        assertTrue(host.scrolls.all { it > 0f })
        rig.engine.release(cancelled = false, uptimeMs = 0L)
        val scrolled = host.scrolls.size
        frames(5)
        assertEquals(scrolled, host.scrolls.size)
    }

    @Test
    fun should_notScroll_when_liftedInEdgeBand_untilPushedTowardEdge() = runEditClockTest { scope ->
        // The bottom band is 1856..2000 (72dp at density 2); a push is 16px.
        val host = FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid, RecentlyAdded))
        val rig = Rig(scope, this, host, specs)
        rig.lift(Activities, x = 500f, y = 1950f)
        frames(5)
        assertTrue(host.scrolls.isEmpty())
        rig.moveTo(500f, 1960f)
        frames(2)
        assertTrue(host.scrolls.isEmpty())
        rig.moveTo(500f, 1970f)
        frames(3)
        assertTrue(host.scrolls.isNotEmpty())
        assertTrue(host.scrolls.all { it > 0f })
    }

    @Test
    fun should_scroll_when_liftedInEdgeBand_andRestedThere() = runEditClockTest { scope ->
        val host = FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid, RecentlyAdded))
        val rig = Rig(scope, this, host, specs)
        rig.lift(Activities, x = 500f, y = 1950f)
        frames(8)
        assertTrue(host.scrolls.isEmpty())
        // Past 180ms in the band.
        frames(10)
        assertTrue(host.scrolls.isNotEmpty())
    }

    @Test
    fun should_commitAndExit_when_doneArrivesMidDrag() = runEditClockTest { scope ->
        val rig = Rig(scope, this, FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid)), specs)
        rig.lift(MadeForYou, x = 500f, y = 568f)
        rig.moveTo(500f, 768f)
        frames()
        rig.engine.deferExit(HomeEditExitReason.Done)
        assertEquals(listOf(listOf(Activities, MemoryGrid, MadeForYou)), rig.orders)
        assertEquals(listOf(HomeEditExitReason.Done), rig.exits)
        assertTrue(rig.engine.letGo)
        assertFalse(rig.engine.isCarrying)
    }

    @Test
    fun should_rollBackAndExit_when_backArrivesMidDrag() = runEditClockTest { scope ->
        val rig = Rig(scope, this, FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid)), specs)
        rig.lift(MadeForYou, x = 500f, y = 568f)
        rig.moveTo(500f, 768f)
        frames()
        assertEquals(listOf(Activities, MemoryGrid, MadeForYou), rig.engine.arrangement?.ids)
        rig.engine.deferExit(HomeEditExitReason.Back)
        assertTrue(rig.orders.isEmpty())
        assertEquals(listOf(HomeEditExitReason.Back), rig.exits)
        assertNull(rig.engine.arrangement)
        assertFalse(rig.engine.isCarrying)
    }

    @Test
    fun should_rollBack_when_geometryChangesMidDrag() = runEditClockTest { scope ->
        // Rotation or one column ⇄ two: Home calls cancel().
        val host = FakeHost(twoColumn = true, order = listOf(Activities, MadeForYou, MemoryGrid))
        val rig = Rig(scope, this, host, specs)
        rig.lift(MemoryGrid, x = 500f, y = 886f)
        rig.moveTo(950f, 568f)
        frames()
        assertEquals(mapOf(MadeForYou to MemoryGrid), rig.engine.arrangement?.pairs)
        rig.engine.cancel()
        assertTrue(rig.arrangements.isEmpty())
        assertTrue(rig.orders.isEmpty())
        assertTrue(rig.engine.letGo)
        assertNull(rig.engine.arrangement)
        frames(60)
        assertNull(rig.engine.liftSection)
    }

    @Test
    fun should_rollBack_when_systemCancelsTheFinger() = runEditClockTest { scope ->
        val rig = Rig(scope, this, FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid)), specs)
        rig.lift(MadeForYou, x = 500f, y = 568f)
        rig.moveTo(500f, 768f)
        frames()
        rig.engine.release(cancelled = true, uptimeMs = 0L)
        assertTrue(rig.orders.isEmpty())
        assertNull(rig.engine.dragging)
    }

    @Test
    fun should_neitherLiftNorDrag_while_aResizeHandleIsHeld() = runEditClockTest { scope ->
        val rig = Rig(scope, this, FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid)), specs)
        rig.engine.resizeActive = { true }
        rig.lift(MadeForYou, x = 500f, y = 568f)
        assertNull(rig.engine.liftSection)
        assertNull(rig.engine.dragging)
    }

    @Test
    fun should_toggleList_only_whenNoBlockIsLifted() = runEditClockTest { scope ->
        val rig = Rig(scope, this, FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid)), specs)
        rig.lift(MadeForYou, x = 500f, y = 568f)
        rig.engine.toggleList()
        assertFalse(rig.engine.list.on)
        rig.engine.release(cancelled = false, uptimeMs = 0L)
        frames(60)
        rig.engine.toggleList()
        assertTrue(rig.engine.list.on)
        rig.engine.toggleList()
        assertFalse(rig.engine.list.on)
    }

    @Test
    fun should_putLiftDown_when_dragNeverStarted() = runEditClockTest { scope ->
        val rig = Rig(scope, this, FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou)), specs)
        rig.engine.host = null
        rig.lift(MadeForYou, x = 500f, y = 568f)
        assertNull(rig.engine.dragging)
        rig.engine.release(cancelled = false, uptimeMs = 0L)
        frames(60)
        assertNull(rig.engine.liftSection)
    }

    @Test
    fun should_dragInTheList_when_listIsOn() = runEditClockTest { scope ->
        val feed = FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid))
        val rig = Rig(scope, this, feed, specs)
        val list = object : HomeDragHost by FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid)) {
            override val isList: Boolean = true
        }
        rig.engine.listHost = list
        rig.engine.list.set(true)
        rig.lift(MadeForYou, x = 500f, y = 568f)
        assertEquals(MadeForYou, rig.engine.dragging)
        rig.moveTo(500f, 600f)
        // The feed's block stays put; the list's strip follows.
        assertEquals(Offset.Zero, rig.engine.translation(MadeForYou, inList = false))
        assertEquals(Offset(0f, 32f), rig.engine.translation(MadeForYou, inList = true))
    }

    private companion object {
        const val Top = 100f
        const val RowHeight = 300f
        const val Gap = 18f
    }
}
