package com.gpo.yoin.ui.home.edit

import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import com.gpo.yoin.ui.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSection.Activities
import com.gpo.yoin.ui.home.HomeSection.JumpBackIn
import com.gpo.yoin.ui.home.HomeSection.MadeForYou
import com.gpo.yoin.ui.home.HomeSection.MemoryGrid
import com.gpo.yoin.ui.home.HomeSection.RecentlyAdded
import com.gpo.yoin.ui.home.HomeSection.RecentlyPlayed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The side drop (HomeCarrySide.kt): which drop the finger picks, and what each does to the rows. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeCarrySideTest {

    // ── Edge zones ──────────────────────────────────────────────────────────

    /** The zone across a column 100..900px. */
    private fun zone(x: Float, current: StripLane?): StripLane? =
        carryEdgeZone(x, columnLeft = 100f, columnWidth = 800f, current = current)

    @Test
    fun should_pickEdgeZone_when_fingerInOuterQuarter() {
        assertEquals(StripLane.Left, zone(120f, null))
        assertEquals(StripLane.Right, zone(880f, null))
        assertNull(zone(500f, null))
    }

    @Test
    fun should_holdZoneOverItsHalf_when_alreadyBeside() {
        // 40% across: the middle for a fresh drag, still the left half for a strip already on the left.
        assertNull(zone(420f, null))
        assertEquals(StripLane.Left, zone(420f, StripLane.Left))
        assertNull(zone(520f, StripLane.Left))
        assertEquals(StripLane.Right, zone(520f, StripLane.Right))
    }

    @Test
    fun should_haveNoZone_when_fingerUnknown() {
        assertNull(carryEdgeZone(fingerX = Float.NaN, columnLeft = 0f, columnWidth = 800f, current = null))
    }

    // ── Allowed targets ─────────────────────────────────────────────────────

    @Test
    fun should_allowFullRowOfAnotherSection_only() {
        assertTrue(carrySideAllowed(HomeFeedRow.Full(Activities), carried = JumpBackIn))
        assertEquals(false, carrySideAllowed(HomeFeedRow.Full(JumpBackIn), carried = JumpBackIn))
    }

    @Test
    fun should_forbidPairOfOthers_but_allowOwnPair() {
        assertEquals(false, carrySideAllowed(HomeFeedRow.Halves(Activities, RecentlyAdded), carried = JumpBackIn))
        assertTrue(carrySideAllowed(HomeFeedRow.Halves(Activities, JumpBackIn), carried = JumpBackIn))
    }

    // ── Which row the strip pairs with ─────────────────────────────────────

    @Test
    fun should_leanToOverlappedRow_when_offTheSlot() {
        // Row 2 of 4, 144px pitch: 40px up leans to the row above (index 1 without it), 40px down to the one below (2).
        assertEquals(1, carrySideCandidate(k = 2, rowCount = 4, display = -40f, pitch = 144f, lastSwapDir = 0))
        assertEquals(2, carrySideCandidate(k = 2, rowCount = 4, display = 40f, pitch = 144f, lastSwapDir = 0))
    }

    @Test
    fun should_pickRowJustPassed_when_restingInSlot() {
        // Moved down past a row: that row is now above.
        assertEquals(1, carrySideCandidate(k = 2, rowCount = 4, display = 0f, pitch = 144f, lastSwapDir = 1))
        assertEquals(2, carrySideCandidate(k = 2, rowCount = 4, display = 0f, pitch = 144f, lastSwapDir = -1))
    }

    @Test
    fun should_fallBackToOnlyNeighbour_when_atEnds() {
        assertEquals(0, carrySideCandidate(k = 0, rowCount = 3, display = -60f, pitch = 144f, lastSwapDir = 0))
        assertEquals(1, carrySideCandidate(k = 2, rowCount = 3, display = 60f, pitch = 144f, lastSwapDir = 0))
        assertNull(carrySideCandidate(k = 0, rowCount = 1, display = 0f, pitch = 144f, lastSwapDir = 0))
    }

    @Test
    fun should_findRowUnderStripCentre() {
        assertEquals(0, carryRowAt(centerY = 160f, top = 100f, pitch = 144f, gap = 16f, rowCount = 4))
        assertEquals(1, carryRowAt(centerY = 300f, top = 100f, pitch = 144f, gap = 16f, rowCount = 4))
        assertEquals(3, carryRowAt(centerY = 2000f, top = 100f, pitch = 144f, gap = 16f, rowCount = 4))
    }

    // ── Arrangements ────────────────────────────────────────────────────────

    @Test
    fun should_pairBeside_when_droppedOnFullRowEdge() {
        val ids = listOf(Activities, MemoryGrid, MadeForYou, RecentlyPlayed)
        val right = carryPlaceBeside(ids, emptyMap(), carried = MadeForYou, target = MemoryGrid, side = StripLane.Right)
        assertEquals(listOf(Activities, MemoryGrid, MadeForYou, RecentlyPlayed), right.ids)
        assertEquals(mapOf(MemoryGrid to MadeForYou), right.pairs)
        val left = carryPlaceBeside(ids, emptyMap(), carried = MadeForYou, target = MemoryGrid, side = StripLane.Left)
        assertEquals(listOf(Activities, MadeForYou, MemoryGrid, RecentlyPlayed), left.ids)
        assertEquals(mapOf(MadeForYou to MemoryGrid), left.pairs)
    }

    @Test
    fun should_swapSides_when_droppedOnOwnPartnersOtherEdge() {
        val ids = listOf(Activities, JumpBackIn, MemoryGrid)
        val swapped = carryPlaceBeside(ids, mapOf(Activities to JumpBackIn), JumpBackIn, Activities, StripLane.Left)
        assertEquals(listOf(JumpBackIn, Activities, MemoryGrid), swapped.ids)
        assertEquals(mapOf(JumpBackIn to Activities), swapped.pairs)
    }

    @Test
    fun should_leavePartnerFull_when_movedToAnotherBlock() {
        val ids = listOf(Activities, JumpBackIn, MemoryGrid)
        val moved = carryPlaceBeside(ids, mapOf(Activities to JumpBackIn), JumpBackIn, MemoryGrid, StripLane.Right)
        assertEquals(listOf(Activities, MemoryGrid, JumpBackIn), moved.ids)
        assertEquals(mapOf(MemoryGrid to JumpBackIn), moved.pairs)
        assertEquals(
            listOf(HomeFeedRow.Full(Activities), HomeFeedRow.Halves(MemoryGrid, JumpBackIn)),
            moved.rows,
        )
    }

    @Test
    fun should_splitPair_when_halfLifted() {
        val pairs = mapOf(Activities to JumpBackIn, RecentlyAdded to RecentlyPlayed)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), carryUnpair(pairs, JumpBackIn))
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), carryUnpair(pairs, Activities))
    }

    @Test
    fun should_insertOwnRow_at_gapBetweenRows() {
        val ids = listOf(Activities, JumpBackIn, MemoryGrid, RecentlyAdded, RecentlyPlayed)
        val pairs = mapOf(Activities to JumpBackIn, RecentlyAdded to RecentlyPlayed)
        // Lifted out of the first pair, dropped after All Memories: rows Activities, All Memories, JBI, the pair.
        val dropped = carryInsertRow(ids, pairs, carried = JumpBackIn, gap = 2)
        assertEquals(listOf(Activities, MemoryGrid, JumpBackIn, RecentlyAdded, RecentlyPlayed), dropped.ids)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), dropped.pairs)
        assertEquals(
            listOf(
                HomeFeedRow.Full(Activities),
                HomeFeedRow.Full(MemoryGrid),
                HomeFeedRow.Full(JumpBackIn),
                HomeFeedRow.Halves(RecentlyAdded, RecentlyPlayed),
            ),
            dropped.rows,
        )
        assertEquals(JumpBackIn, carryInsertRow(ids, pairs, JumpBackIn, gap = 0).ids.first())
        assertEquals(JumpBackIn, carryInsertRow(ids, pairs, JumpBackIn, gap = 99).ids.last())
    }

    @Test
    fun should_jumpWholePair_when_swappedPastIt() {
        val ids = listOf(MemoryGrid, RecentlyAdded, RecentlyPlayed, MadeForYou)
        val pairs = mapOf(RecentlyAdded to RecentlyPlayed)
        val down = carrySwapRow(ids, pairs, MemoryGrid, 1)
        assertEquals(listOf(RecentlyAdded, RecentlyPlayed, MemoryGrid, MadeForYou), down)
        val up = carrySwapRow(ids, pairs, MadeForYou, -1)
        assertEquals(listOf(MemoryGrid, MadeForYou, RecentlyAdded, RecentlyPlayed), up)
    }

    @Test
    fun should_leaveAboveOrBelow_when_leavingSide() {
        assertEquals(2, carryLeaveGap(k = 2, display = 0f, pitch = 144f, side = StripLane.Left, rowCount = 4))
        assertEquals(3, carryLeaveGap(k = 2, display = 0f, pitch = 144f, side = StripLane.Right, rowCount = 4))
        assertEquals(3, carryLeaveGap(k = 2, display = 60f, pitch = 144f, side = StripLane.Left, rowCount = 4))
        assertEquals(2, carryLeaveGap(k = 2, display = -60f, pitch = 144f, side = StripLane.Right, rowCount = 4))
    }

    // ── The engine, two columns ─────────────────────────────────────────────

    private val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = false)
    private val density = Density(2f)

    private class TwoColumnHost(
        private val order: List<HomeSection>,
        private val pairs: Map<HomeSection, HomeSection>,
    ) : CarryHost {
        override fun displayedOrder(): List<HomeSection> = order

        override fun plateRect(section: HomeSection): Rect? = null

        override fun isAbove(section: HomeSection): Boolean = false

        override fun safeArea(): HomeEditSafeArea = HomeEditSafeArea(top = 88f, bottom = 1344f)

        override fun contentLeft(): Float = Left

        override fun contentWidth(): Float = Width

        override suspend fun anchorAndAwaitLayout(dropped: HomeSection, order: List<HomeSection>, slotTop: Float) = Unit

        override fun twoColumn(): Boolean = true

        override fun pairs(): Map<HomeSection, HomeSection> = pairs
    }

    private class Committed(val ids: List<HomeSection>, val pairs: Map<HomeSection, HomeSection>)

    private fun engine(
        scope: CoroutineScope,
        test: TestScope,
        host: CarryHost,
        out: MutableList<Committed>,
    ): HomeCarryEngine {
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
        return HomeCarryEngine(
            scope = scope,
            specs = specs,
            motion = motion,
            feedback = HomeEditFeedback.None,
            density = density,
            commitOrder = { error("two columns commit the arrangement") },
            finishDeferredExit = {},
            uptimeMs = { test.testScheduler.currentTime },
            commitArrangement = { ids, pairs ->
                out += Committed(ids, pairs)
                true
            },
        ).also { it.host = host }
    }

    private fun TestScope.frames(ms: Long) {
        var elapsed = 0L
        while (elapsed < ms) {
            Snapshot.sendApplyNotifications()
            advanceTimeBy(16)
            runCurrent()
            elapsed += 16
        }
    }

    private fun TestScope.untilFinished(engine: HomeCarryEngine) {
        var elapsed = 0L
        while (engine.session != null && elapsed < 3000) {
            frames(16)
            elapsed += 16
        }
        assertNull(engine.session)
    }

    @Test
    fun should_widenPartner_when_halfLiftedInTheMiddle() = runEditClockTest { scope ->
        val out = mutableListOf<Committed>()
        val host = TwoColumnHost(listOf(Activities, JumpBackIn, MemoryGrid), mapOf(Activities to JumpBackIn))
        val engine = engine(scope, this, host, out)
        engine.liftBlock(JumpBackIn, Offset.Zero)
        engine.fingerX = Left + Width / 2f
        engine.start(fingerY = 400f, uptimeMs = testScheduler.currentTime)
        val s = checkNotNull(engine.session)
        assertNull(s.side)
        assertTrue(s.pairs.isEmpty())
        assertEquals(3, s.rowCount)
        frames(600)
        assertEquals(Width, engine.laneWidth(Activities), 1f)
    }

    @Test
    fun should_stayBesidePartner_when_halfLiftedOnItsOwnSide() = runEditClockTest { scope ->
        val out = mutableListOf<Committed>()
        val host = TwoColumnHost(listOf(Activities, JumpBackIn, MemoryGrid), mapOf(Activities to JumpBackIn))
        val engine = engine(scope, this, host, out)
        engine.liftBlock(JumpBackIn, Offset.Zero)
        engine.fingerX = Left + Width * .6f
        engine.start(fingerY = 200f, uptimeMs = testScheduler.currentTime)
        val s = checkNotNull(engine.session)
        assertEquals(StripLane.Right, s.side)
        assertEquals(mapOf(Activities to JumpBackIn), s.pairs)
        frames(400)
        engine.release(cancelled = false, uptimeMs = testScheduler.currentTime)
        untilFinished(engine)
        assertTrue("nothing changed: nothing commits", out.isEmpty())
    }

    @Test
    fun should_pairWithFullRow_when_movedToItsEdge_and_commitOnce() = runEditClockTest { scope ->
        val out = mutableListOf<Committed>()
        val host = TwoColumnHost(listOf(Activities, MemoryGrid, MadeForYou, RecentlyPlayed), emptyMap())
        val engine = engine(scope, this, host, out)
        engine.liftBlock(MadeForYou, Offset.Zero)
        engine.fingerX = Left + Width / 2f
        engine.start(fingerY = 600f, uptimeMs = testScheduler.currentTime)
        frames(400)
        val s = checkNotNull(engine.session)
        // Leaning up a little and over to the right edge: beside All Memories.
        engine.fingerX = Left + Width * .9f
        engine.move(600f - s.metrics.pitch * .3f, testScheduler.currentTime)
        assertEquals(StripLane.Right, s.side)
        assertEquals(mapOf(MemoryGrid to MadeForYou), s.pairs)
        assertEquals(3, s.rowCount)
        frames(200)
        engine.release(cancelled = false, uptimeMs = testScheduler.currentTime)
        untilFinished(engine)
        assertEquals(1, out.size)
        assertEquals(listOf(Activities, MemoryGrid, MadeForYou, RecentlyPlayed), out.single().ids)
        assertEquals(mapOf(MemoryGrid to MadeForYou), out.single().pairs)
    }

    @Test
    fun should_notPairWithAnotherPair_when_overItsEdge() = runEditClockTest { scope ->
        val out = mutableListOf<Committed>()
        val host = TwoColumnHost(
            listOf(RecentlyAdded, RecentlyPlayed, MadeForYou),
            mapOf(RecentlyAdded to RecentlyPlayed),
        )
        val engine = engine(scope, this, host, out)
        engine.liftBlock(MadeForYou, Offset.Zero)
        engine.fingerX = Left + Width / 2f
        engine.start(fingerY = 600f, uptimeMs = testScheduler.currentTime)
        frames(400)
        val s = checkNotNull(engine.session)
        engine.fingerX = Left + Width * .9f
        engine.move(600f - s.metrics.pitch * .3f, testScheduler.currentTime)
        assertNull(s.side)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), s.pairs)
    }

    @Test
    fun should_goBetweenRows_when_movedBackToMiddle() = runEditClockTest { scope ->
        val out = mutableListOf<Committed>()
        val host = TwoColumnHost(listOf(Activities, JumpBackIn, MemoryGrid), mapOf(Activities to JumpBackIn))
        val engine = engine(scope, this, host, out)
        engine.liftBlock(JumpBackIn, Offset.Zero)
        engine.fingerX = Left + Width * .8f
        engine.start(fingerY = 200f, uptimeMs = testScheduler.currentTime)
        val s = checkNotNull(engine.session)
        assertEquals(StripLane.Right, s.side)
        engine.fingerX = Left + Width * .4f
        engine.move(200f, testScheduler.currentTime)
        assertNull(s.side)
        assertTrue(s.pairs.isEmpty())
        // A right half leaving its partner goes below it.
        assertEquals(listOf(Activities, JumpBackIn, MemoryGrid), s.ids)
        engine.release(cancelled = false, uptimeMs = testScheduler.currentTime)
        untilFinished(engine)
        assertEquals(emptyMap<HomeSection, HomeSection>(), out.single().pairs)
    }

    @Test
    fun should_neverPair_inOneColumn() = runEditClockTest { scope ->
        val out = mutableListOf<Committed>()
        val host = object : CarryHost by TwoColumnHost(listOf(Activities, MemoryGrid, MadeForYou), emptyMap()) {
            override fun twoColumn(): Boolean = false
        }
        val committed = mutableListOf<List<HomeSection>>()
        val motion = HomeEditMotion(
            scope = scope,
            specs = specs,
            style = HomeWiggleStyle(),
            reduced = { false },
            progress = { 1f },
            editing = { true },
            feedback = HomeEditFeedback.None,
            uptimeMs = { testScheduler.currentTime },
        )
        val engine = HomeCarryEngine(
            scope = scope,
            specs = specs,
            motion = motion,
            feedback = HomeEditFeedback.None,
            density = density,
            commitOrder = {
                committed += it
                true
            },
            finishDeferredExit = {},
            uptimeMs = { testScheduler.currentTime },
            commitArrangement = { ids, pairs ->
                out += Committed(ids, pairs)
                true
            },
        ).also { it.host = host }
        engine.liftBlock(MadeForYou, Offset.Zero)
        engine.fingerX = Left + Width * .95f
        engine.start(fingerY = 600f, uptimeMs = testScheduler.currentTime)
        val s = checkNotNull(engine.session)
        engine.move(560f, testScheduler.currentTime)
        assertNull(s.side)
        assertTrue(s.pairs.isEmpty())
    }

    private companion object {
        const val Left = 64f
        const val Width = 2400f
    }
}
