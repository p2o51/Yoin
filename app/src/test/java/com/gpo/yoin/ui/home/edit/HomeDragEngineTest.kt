package com.gpo.yoin.ui.home.edit

import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.shared.home.HomeSection.Activities
import com.gpo.yoin.shared.home.HomeSection.JumpBackIn
import com.gpo.yoin.shared.home.HomeSection.MadeForYou
import com.gpo.yoin.shared.home.HomeSection.MemoryGrid
import com.gpo.yoin.shared.home.HomeSection.OtherProfiles
import com.gpo.yoin.shared.home.HomeSection.RecentlyAdded
import com.gpo.yoin.shared.home.HomeSection.RecentlyPlayed
import com.gpo.yoin.shared.home.HomeSection.Rediscover
import com.gpo.yoin.shared.home.HomeSection.YourPlaylists
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

        /** The arrangement laid out, when it lags the drag's (a change not laid out yet). */
        var shown: DragArrangement? = null
        val scrolls = mutableListOf<Float>()
        private var scrolled = 0f

        override fun order(): List<HomeSection> = order

        override fun twoColumn(): Boolean = twoColumn

        override fun pairs(): Map<HomeSection, HomeSection> = pairs

        override fun visibleRows(): List<DragItemBand> {
            val live = shown ?: engine?.arrangement ?: DragArrangement(order, if (twoColumn) pairs else emptyMap())
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
    fun should_keepFingerAtSameFractionOfDrawnBlock_andStayOnContent_when_halfTurnsFull() = runEditClockTest { scope ->
        // JBI (right half, x 510..1000) grabbed 190px in; out of its row it is full (x 0..1000).
        val host = FakeHost(twoColumn = true, order = listOf(Activities, JumpBackIn, MemoryGrid), pairs = mapOf(Activities to JumpBackIn))
        val rig = Rig(scope, this, host, specs)
        rig.lift(JumpBackIn, x = 700f, y = 250f)
        rig.moveTo(700f, 440f)
        frames()
        assertEquals(emptyMap<HomeSection, HomeSection>(), rig.engine.arrangement?.pairs)
        // Still drawn half wide (its width springs): it stays under the finger, 190px in, not thrown left.
        assertEquals(510f, rig.engine.translation(JumpBackIn, inList = false, width = 490f).x, .5f)
        // Mid-spring, 745px: the finger at 190/490 of it would put its left at 411; it keeps to the content (≤ 255).
        assertEquals(255f, rig.engine.translation(JumpBackIn, inList = false, width = 745f).x, .5f)
        // Full width: in its row, not half off the screen with the finger on its 190/490 mark.
        assertEquals(0f, rig.engine.translation(JumpBackIn, inList = false, width = 1000f).x, .5f)
    }

    @Test
    fun should_pairWithNoRow_andKeepBlockUnderFingerOnContent_while_edgeScrollCarriesRowsPast() = runEditClockTest { scope ->
        // Eight full rows, 100..400, 418..718, …; the bottom edge band is 1856..2000. Activities is
        // dragged with the finger 95% across, to the bottom band: the scroll carries the rows below
        // up under the finger, each one's middle in the pairing zone (> 0.70) as it passes.
        val order = listOf(Activities, JumpBackIn, MadeForYou, MemoryGrid, RecentlyAdded, RecentlyPlayed, YourPlaylists, Rediscover, OtherProfiles)
        val host = FakeHost(twoColumn = true, order = order)
        val rig = Rig(scope, this, host, specs)
        rig.lift(Activities, x = 950f, y = 250f)
        rig.moveTo(950f, 1950f)
        var passed = 0
        repeat(90) {
            frames(1)
            val live = rig.engine.arrangement ?: error("dragging")
            // No pair while the rows scroll past: the block never turns half under the finger.
            assertEquals(emptyMap<HomeSection, HomeSection>(), live.pairs)
            if (host.visibleRows().any { it.key != Activities && 1950f in it.top..it.bottom }) passed++
            // Drawn at any width its spring passes through, the block holds the finger and stays on the content.
            for (width in listOf(1000f, 745f, 490f)) {
                val left = rig.engine.translation(Activities, inList = false, width = width).x
                assertTrue("left $left at width $width", left >= -.5f && left + width <= 1000.5f)
                assertTrue("finger off the block at width $width", 950f in left..left + width)
            }
        }
        assertTrue(host.scrolls.isNotEmpty())
        assertTrue("rows passed under the finger: $passed", passed > 10)

        // Out of the band, the scroll stops: over a full row's middle at the side it pairs again.
        val row = host.visibleRows().first { it.key != Activities && it.top > 300f && it.bottom < 1800f }
        rig.moveTo(950f, (row.top + row.bottom) / 2f)
        frames(2)
        rig.moveTo(990f, host.visibleRows().first { it.key == row.key }.let { (it.top + it.bottom) / 2f })
        frames(2)
        assertEquals(mapOf(row.key to Activities), rig.engine.arrangement?.pairs)
        // As a right half, drawn half wide: on the content, under the finger.
        val left = rig.engine.translation(Activities, inList = false, width = 490f).x + 510f
        assertTrue("left $left", left >= -.5f && left + 490f <= 1000.5f && 990f in left..left + 490f)
    }

    @Test
    fun should_pairWhereFingerLetGo_when_releasedBeforeAFrameDecided() = runEditClockTest { scope ->
        // Jump Back In 100..400, Activities 418..718, All Memories 736..1036, all full rows.
        val host = FakeHost(twoColumn = true, order = listOf(JumpBackIn, Activities, MemoryGrid))
        val rig = Rig(scope, this, host, specs)
        rig.lift(Activities, x = 900f, y = 568f)
        // A quick drag up: the last frame sees the finger still in its own row…
        rig.moveTo(900f, 450f)
        frames()
        assertEquals(listOf(JumpBackIn, Activities, MemoryGrid), rig.engine.arrangement?.ids)

        // …and it lets go over Jump Back In's middle, 90% across, before another frame.
        rig.moveTo(900f, 250f)
        rig.engine.release(cancelled = false, uptimeMs = 0L)

        assertEquals(
            listOf(DragArrangement(listOf(JumpBackIn, Activities, MemoryGrid), mapOf(JumpBackIn to Activities))),
            rig.arrangements,
        )
    }

    @Test
    fun should_decideFromLaidOutLayout_when_lastChangeNotLaidOutAtRelease() = runEditClockTest { scope ->
        val host = FakeHost(twoColumn = true, order = listOf(JumpBackIn, Activities, MemoryGrid))
        val rig = Rig(scope, this, host, specs)
        val before = DragArrangement(listOf(JumpBackIn, Activities, MemoryGrid), emptyMap())
        // Grabbed low in its block (282px down), the block placed in the layout before any change.
        rig.lift(Activities, x = 900f, y = 700f)
        assertEquals(Offset(0f, 0f), rig.engine.dragOffset(Activities, inList = false, width = 1000f, placedAt = Offset(0f, 418f)))
        host.shown = before
        // A frame decides on a finger still in its own row: its top past the half-travel line, the rows swap…
        rig.moveTo(900f, 530f)
        frames()
        assertEquals(listOf(Activities, JumpBackIn, MemoryGrid), rig.engine.arrangement?.ids)

        // …but the list still shows the rows before it when the finger lets go over Jump Back In's middle.
        rig.moveTo(900f, 250f)
        rig.engine.release(cancelled = false, uptimeMs = 0L)

        assertEquals(listOf(DragArrangement(listOf(JumpBackIn, Activities, MemoryGrid), mapOf(JumpBackIn to Activities))), rig.arrangements)
    }

    @Test
    fun should_glideFromWhereDrawnIntoWhereverPlaced_when_dropped() = runEditClockTest { scope ->
        val rig = Rig(scope, this, FakeHost(twoColumn = false, order = listOf(Activities, MadeForYou, MemoryGrid)), specs)
        rig.lift(MadeForYou, x = 500f, y = 568f)
        rig.moveTo(500f, 600f)
        frames()
        // Placed at its slot (418), drawn 32px lower under the finger.
        assertEquals(Offset(0f, 32f), rig.engine.dragOffset(MadeForYou, inList = false, width = 1000f, placedAt = Offset(0f, 418f)))

        rig.engine.release(cancelled = false, uptimeMs = 0L)

        // The glide starts where it was drawn, against wherever this frame places it (here 400).
        assertEquals(Offset(0f, 50f), rig.engine.settleOffset(MadeForYou, inList = false, placedAt = Offset(0f, 400f)))
        assertNull(rig.engine.dragOffset(MadeForYou, inList = false, width = 1000f, placedAt = Offset(0f, 400f)))
        frames(60)
        assertNull(rig.engine.settleOffset(MadeForYou, inList = false, placedAt = Offset(0f, 400f)))
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
