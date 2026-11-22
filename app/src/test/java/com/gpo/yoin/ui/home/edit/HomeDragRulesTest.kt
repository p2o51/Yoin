package com.gpo.yoin.ui.home.edit

import com.gpo.yoin.ui.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSection.Activities
import com.gpo.yoin.ui.home.HomeSection.JumpBackIn
import com.gpo.yoin.ui.home.HomeSection.MadeForYou
import com.gpo.yoin.ui.home.HomeSection.MemoryGrid
import com.gpo.yoin.ui.home.HomeSection.RecentlyAdded
import com.gpo.yoin.ui.home.HomeSection.RecentlyPlayed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drop rules (HomeDragRules.kt): which drop the finger picks, and what each does to the rows. */
class HomeDragRulesTest {

    // ── Edge zones ──────────────────────────────────────────────────────────

    /** The zone across a column 100..900px. */
    private fun zone(x: Float, current: HomeDragLane?): HomeDragLane? =
        dragEdgeZone(x, columnLeft = 100f, columnWidth = 800f, current = current)

    @Test
    fun should_pickEdgeZone_when_fingerInOuterQuarter() {
        assertEquals(HomeDragLane.Left, zone(120f, null))
        assertEquals(HomeDragLane.Right, zone(880f, null))
        assertNull(zone(500f, null))
    }

    @Test
    fun should_holdZoneOverItsHalf_when_alreadyBeside() {
        // 40% across: the middle for a fresh drag, still the left half for a block already on the left.
        assertNull(zone(420f, null))
        assertEquals(HomeDragLane.Left, zone(420f, HomeDragLane.Left))
        assertNull(zone(520f, HomeDragLane.Left))
        assertEquals(HomeDragLane.Right, zone(520f, HomeDragLane.Right))
    }

    @Test
    fun should_haveNoZone_when_fingerUnknown() {
        assertNull(dragEdgeZone(fingerX = Float.NaN, columnLeft = 0f, columnWidth = 800f, current = null))
    }

    // ── Allowed targets ─────────────────────────────────────────────────────

    @Test
    fun should_allowFullRowOfAnotherSection_only() {
        assertTrue(dragSideAllowed(HomeFeedRow.Full(Activities), dragged = JumpBackIn))
        assertFalse(dragSideAllowed(HomeFeedRow.Full(JumpBackIn), dragged = JumpBackIn))
    }

    @Test
    fun should_forbidPairOfOthers_but_allowOwnPair() {
        assertFalse(dragSideAllowed(HomeFeedRow.Halves(Activities, RecentlyAdded), dragged = JumpBackIn))
        assertTrue(dragSideAllowed(HomeFeedRow.Halves(Activities, JumpBackIn), dragged = JumpBackIn))
    }

    // ── Arrangements ────────────────────────────────────────────────────────

    @Test
    fun should_pairBeside_when_droppedOnFullRowEdge() {
        val ids = listOf(Activities, MemoryGrid, MadeForYou, RecentlyPlayed)
        val right = dragPlaceBeside(ids, emptyMap(), dragged = MadeForYou, target = MemoryGrid, side = HomeDragLane.Right)
        assertEquals(listOf(Activities, MemoryGrid, MadeForYou, RecentlyPlayed), right.ids)
        assertEquals(mapOf(MemoryGrid to MadeForYou), right.pairs)
        val left = dragPlaceBeside(ids, emptyMap(), dragged = MadeForYou, target = MemoryGrid, side = HomeDragLane.Left)
        assertEquals(listOf(Activities, MadeForYou, MemoryGrid, RecentlyPlayed), left.ids)
        assertEquals(mapOf(MadeForYou to MemoryGrid), left.pairs)
    }

    @Test
    fun should_swapSides_when_droppedOnOwnPartnersOtherEdge() {
        val ids = listOf(Activities, JumpBackIn, MemoryGrid)
        val swapped = dragPlaceBeside(ids, mapOf(Activities to JumpBackIn), JumpBackIn, Activities, HomeDragLane.Left)
        assertEquals(listOf(JumpBackIn, Activities, MemoryGrid), swapped.ids)
        assertEquals(mapOf(JumpBackIn to Activities), swapped.pairs)
    }

    @Test
    fun should_leavePartnerFull_when_movedToAnotherBlock() {
        val ids = listOf(Activities, JumpBackIn, MemoryGrid)
        val moved = dragPlaceBeside(ids, mapOf(Activities to JumpBackIn), JumpBackIn, MemoryGrid, HomeDragLane.Right)
        assertEquals(listOf(Activities, MemoryGrid, JumpBackIn), moved.ids)
        assertEquals(mapOf(MemoryGrid to JumpBackIn), moved.pairs)
        assertEquals(
            listOf(HomeFeedRow.Full(Activities), HomeFeedRow.Halves(MemoryGrid, JumpBackIn)),
            dragRows(moved.ids, moved.pairs, twoColumn = true),
        )
    }

    @Test
    fun should_splitPair_when_halfLifted() {
        val pairs = mapOf(Activities to JumpBackIn, RecentlyAdded to RecentlyPlayed)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), dragUnpair(pairs, JumpBackIn))
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), dragUnpair(pairs, Activities))
    }

    @Test
    fun should_insertOwnRow_at_gapBetweenRows() {
        val ids = listOf(Activities, JumpBackIn, MemoryGrid, RecentlyAdded, RecentlyPlayed)
        val pairs = mapOf(Activities to JumpBackIn, RecentlyAdded to RecentlyPlayed)
        // Lifted out of the first pair, dropped after All Memories: rows Activities, All Memories, JBI, the pair.
        val dropped = dragInsertRow(ids, pairs, dragged = JumpBackIn, gap = 2)
        assertEquals(listOf(Activities, MemoryGrid, JumpBackIn, RecentlyAdded, RecentlyPlayed), dropped.ids)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), dropped.pairs)
        assertEquals(
            listOf(
                HomeFeedRow.Full(Activities),
                HomeFeedRow.Full(MemoryGrid),
                HomeFeedRow.Full(JumpBackIn),
                HomeFeedRow.Halves(RecentlyAdded, RecentlyPlayed),
            ),
            dragRows(dropped.ids, dropped.pairs, twoColumn = true),
        )
        assertEquals(JumpBackIn, dragInsertRow(ids, pairs, JumpBackIn, gap = 0).ids.first())
        assertEquals(JumpBackIn, dragInsertRow(ids, pairs, JumpBackIn, gap = 99).ids.last())
    }

    @Test
    fun should_jumpWholePair_when_swappedPastIt() {
        val ids = listOf(MemoryGrid, RecentlyAdded, RecentlyPlayed, MadeForYou)
        val pairs = mapOf(RecentlyAdded to RecentlyPlayed)
        val down = dragSwapRow(ids, pairs, MemoryGrid, 1, twoColumn = true)
        assertEquals(listOf(RecentlyAdded, RecentlyPlayed, MemoryGrid, MadeForYou), down)
        val up = dragSwapRow(ids, pairs, MadeForYou, -1, twoColumn = true)
        assertEquals(listOf(MemoryGrid, MadeForYou, RecentlyAdded, RecentlyPlayed), up)
    }

    @Test
    fun should_passOneSectionAtATime_inOneColumn() {
        val ids = listOf(MemoryGrid, RecentlyAdded, RecentlyPlayed, MadeForYou)
        // The stored pair stacks in one column: the drag passes its halves one by one.
        val down = dragSwapRow(ids, emptyMap(), MemoryGrid, 1, twoColumn = false)
        assertEquals(listOf(RecentlyAdded, MemoryGrid, RecentlyPlayed, MadeForYou), down)
        assertEquals(ids, dragSwapRow(ids, emptyMap(), MemoryGrid, -1, twoColumn = false))
    }

    // ── The half-travel rule ────────────────────────────────────────────────

    private fun band(section: HomeSection, top: Float, bottom: Float) = DragRowBand(HomeFeedRow.Full(section), top, bottom)

    @Test
    fun should_swapUp_when_halfwayToSlotAbove() {
        // A 300px block under a 100px one (18px apart): its top would be at 0 there, it is at 118.
        val prev = band(Activities, 0f, 100f)
        val slot = band(JumpBackIn, 118f, 418f)
        assertEquals(0, dragSwapDir(visualTop = 60f, slot = slot, prev = prev, next = null))
        assertEquals(-1, dragSwapDir(visualTop = 58f, slot = slot, prev = prev, next = null))
    }

    @Test
    fun should_swapDown_when_halfwayToSlotBelow() {
        // Its bottom would be the next row's (700): its top 400 there, 118 here, so past 259 it goes.
        val slot = band(JumpBackIn, 118f, 418f)
        val next = band(MemoryGrid, 436f, 700f)
        assertEquals(0, dragSwapDir(visualTop = 258f, slot = slot, prev = null, next = next))
        assertEquals(1, dragSwapDir(visualTop = 260f, slot = slot, prev = null, next = next))
    }

    @Test
    fun should_notSwapBack_when_justSwapped() {
        // Swapped down past a 264px row: now at 282..582 under it (18..282 + 18). The way back is the same line.
        val prev = band(MemoryGrid, 118f, 382f)
        val slot = band(JumpBackIn, 400f, 700f)
        assertEquals(0, dragSwapDir(visualTop = 260f, slot = slot, prev = prev, next = null))
        assertEquals(-1, dragSwapDir(visualTop = 258f, slot = slot, prev = prev, next = null))
    }

    @Test
    fun should_neverSwap_with_rowNotLaidOut() {
        val slot = band(JumpBackIn, 118f, 418f)
        assertEquals(0, dragSwapDir(visualTop = -5000f, slot = slot, prev = null, next = null))
        assertEquals(0, dragSwapDir(visualTop = 5000f, slot = slot, prev = null, next = null))
    }

    // ── Side steps (two columns) ───────────────────────────────────────────

    // Rows: Activities 0..300, MadeForYou (dragged, own row) 318..518, the RA | RP pair 536..836, MemoryGrid 854..1100.
    private val ids = listOf(Activities, MadeForYou, RecentlyAdded, RecentlyPlayed, MemoryGrid)
    private val pairs = mapOf(RecentlyAdded to RecentlyPlayed)
    private val bands = listOf(
        band(Activities, 0f, 300f),
        band(MadeForYou, 318f, 518f),
        DragRowBand(HomeFeedRow.Halves(RecentlyAdded, RecentlyPlayed), 536f, 836f),
        band(MemoryGrid, 854f, 1100f),
    )

    @Test
    fun should_pairWithFullRow_when_fingerInEdgeZoneOverIt() {
        val step = dragSideStep(ids, pairs, MadeForYou, side = null, zone = HomeDragLane.Right, fingerY = 200f, bands = bands)
        val arrange = step as DragSideStep.Arrange
        assertEquals(HomeDragLane.Right, arrange.side)
        assertEquals(listOf(Activities, MadeForYou, RecentlyAdded, RecentlyPlayed, MemoryGrid), arrange.arrangement.ids)
        assertEquals(pairs + (Activities to MadeForYou), arrange.arrangement.pairs)
    }

    @Test
    fun should_leaveToHalfTravel_when_middleOwnRowOrPairOfOthers() {
        assertEquals(
            DragSideStep.Vertical,
            dragSideStep(ids, pairs, MadeForYou, side = null, zone = null, fingerY = 200f, bands = bands),
        )
        assertEquals(
            DragSideStep.Vertical,
            dragSideStep(ids, pairs, MadeForYou, side = null, zone = HomeDragLane.Left, fingerY = 400f, bands = bands),
        )
        assertEquals(
            DragSideStep.Vertical,
            dragSideStep(ids, pairs, MadeForYou, side = null, zone = HomeDragLane.Left, fingerY = 600f, bands = bands),
        )
    }

    // Beside Activities (on its right): rows Activities | MadeForYou 0..300, the pair 318..618, MemoryGrid 636..880.
    private val besideIds = listOf(Activities, MadeForYou, RecentlyAdded, RecentlyPlayed, MemoryGrid)
    private val besidePairs = mapOf(Activities to MadeForYou, RecentlyAdded to RecentlyPlayed)
    private val besideBands = listOf(
        DragRowBand(HomeFeedRow.Halves(Activities, MadeForYou), 0f, 300f),
        DragRowBand(HomeFeedRow.Halves(RecentlyAdded, RecentlyPlayed), 318f, 618f),
        band(MemoryGrid, 636f, 880f),
    )

    private fun beside(zone: HomeDragLane?, fingerY: Float) =
        dragSideStep(besideIds, besidePairs, MadeForYou, HomeDragLane.Right, zone, fingerY, besideBands)

    @Test
    fun should_stay_when_stillOverOwnPairOnSameSide() {
        assertEquals(DragSideStep.Stay, beside(HomeDragLane.Right, 150f))
        // In the gap between rows too.
        assertEquals(DragSideStep.Stay, beside(HomeDragLane.Right, 309f))
    }

    @Test
    fun should_changeSides_when_zoneFlipsOverOwnPair() {
        val step = beside(HomeDragLane.Left, 150f) as DragSideStep.Arrange
        assertEquals(HomeDragLane.Left, step.side)
        assertEquals(mapOf(MadeForYou to Activities, RecentlyAdded to RecentlyPlayed), step.arrangement.pairs)
        assertEquals(MadeForYou, step.arrangement.ids.first())
    }

    @Test
    fun should_leaveAboveOrBelowPartner_when_backInTheMiddle() {
        val above = beside(null, 100f) as DragSideStep.Arrange
        assertNull(above.side)
        assertEquals(listOf(MadeForYou, Activities, RecentlyAdded, RecentlyPlayed, MemoryGrid), above.arrangement.ids)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), above.arrangement.pairs)
        val below = beside(null, 200f) as DragSideStep.Arrange
        assertEquals(listOf(Activities, MadeForYou, RecentlyAdded, RecentlyPlayed, MemoryGrid), below.arrangement.ids)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), below.arrangement.pairs)
    }

    @Test
    fun should_moveToAnotherFullRow_when_overItsEdge() {
        val step = beside(HomeDragLane.Left, 700f) as DragSideStep.Arrange
        assertEquals(HomeDragLane.Left, step.side)
        assertEquals(listOf(Activities, RecentlyAdded, RecentlyPlayed, MadeForYou, MemoryGrid), step.arrangement.ids)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed, MadeForYou to MemoryGrid), step.arrangement.pairs)
    }

    @Test
    fun should_becomeOwnRowBesidePair_when_overPairOfOthers() {
        val above = beside(HomeDragLane.Right, 400f) as DragSideStep.Arrange
        assertNull(above.side)
        assertEquals(listOf(Activities, MadeForYou, RecentlyAdded, RecentlyPlayed, MemoryGrid), above.arrangement.ids)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), above.arrangement.pairs)
        val below = beside(HomeDragLane.Right, 550f) as DragSideStep.Arrange
        assertEquals(listOf(Activities, RecentlyAdded, RecentlyPlayed, MadeForYou, MemoryGrid), below.arrangement.ids)
    }

    // ── Edge scroll ─────────────────────────────────────────────────────────

    @Test
    fun should_scrollInProportionToDepth_capped() {
        // Area 100..1100, 100px edges, 2000px/s at most.
        fun speed(y: Float) = dragAutoScrollSpeed(y, top = 100f, bottom = 1100f, edge = 100f, max = 2000f)
        assertEquals(0f, speed(600f), 0f)
        assertEquals(0f, speed(200f), 0f)
        assertEquals(-1000f, speed(150f), .01f)
        assertEquals(-2000f, speed(100f), .01f)
        assertEquals(-2000f, speed(20f), .01f)
        assertEquals(500f, speed(1025f), .01f)
        assertEquals(2000f, speed(1300f), .01f)
    }

    @Test
    fun should_takeNearerEnd_when_areaNarrowerThanTwoEdges() {
        assertTrue(dragAutoScrollSpeed(110f, top = 100f, bottom = 250f, edge = 100f, max = 1000f) < 0f)
        assertTrue(dragAutoScrollSpeed(240f, top = 100f, bottom = 250f, edge = 100f, max = 1000f) > 0f)
        assertEquals(0f, dragAutoScrollSpeed(Float.NaN, top = 0f, bottom = 1000f, edge = 100f, max = 1000f), 0f)
    }

    // ── Layout catch-up ─────────────────────────────────────────────────────

    @Test
    fun should_followLiveRows_only_when_laidOutInTheirOrder() {
        val rows = listOf(HomeFeedRow.Full(Activities), HomeFeedRow.Full(MadeForYou), HomeFeedRow.Full(MemoryGrid))
        assertTrue(dragLayoutFollows(listOf(Activities, MadeForYou, MemoryGrid), rows, MadeForYou))
        // A row fading out is ignored.
        assertTrue(dragLayoutFollows(listOf(Activities, JumpBackIn, MadeForYou), rows, MadeForYou))
        assertFalse(dragLayoutFollows(listOf(MadeForYou, Activities, MemoryGrid), rows, MadeForYou))
        // The dragged block's row must be laid out.
        assertFalse(dragLayoutFollows(listOf(Activities, MemoryGrid), rows, MadeForYou))
    }
}
