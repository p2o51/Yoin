package com.gpo.yoin.ui.home.edit

import com.gpo.yoin.shared.home.DragSideStep
import com.gpo.yoin.shared.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.shared.home.HomeSection.Activities
import com.gpo.yoin.shared.home.HomeSection.JumpBackIn
import com.gpo.yoin.shared.home.HomeSection.MadeForYou
import com.gpo.yoin.shared.home.HomeSection.MemoryGrid
import com.gpo.yoin.shared.home.HomeSection.RecentlyAdded
import com.gpo.yoin.shared.home.HomeSection.RecentlyPlayed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drop rules (HomeDragRules.kt): which drop the finger picks, and what each does to the rows. */
class HomeDragRulesTest {

    // ── Rule 1: a half in its own row ───────────────────────────────────────

    @Test
    fun should_measureFingerAcrossContent() {
        assertEquals(.5f, dragRel(fingerX = 500f, contentLeft = 100f, contentWidth = 800f), 1e-6f)
        assertTrue(dragRel(fingerX = Float.NaN, contentLeft = 0f, contentWidth = 800f).isNaN())
        assertTrue(dragRel(fingerX = 10f, contentLeft = 0f, contentWidth = 0f).isNaN())
    }

    @Test
    fun should_swapSides_atMiddleWithHysteresis() {
        // A right half goes left only below 0.45; a left half goes right only above 0.55.
        assertEquals(HomeDragLane.Right, dragPairSide(HomeDragLane.Right, .46f))
        assertEquals(HomeDragLane.Left, dragPairSide(HomeDragLane.Right, .44f))
        assertEquals(HomeDragLane.Left, dragPairSide(HomeDragLane.Left, .54f))
        assertEquals(HomeDragLane.Right, dragPairSide(HomeDragLane.Left, .56f))
        // Between the two lines either side stays.
        assertEquals(HomeDragLane.Right, dragPairSide(HomeDragLane.Right, .5f))
        assertEquals(HomeDragLane.Left, dragPairSide(HomeDragLane.Left, .5f))
        assertEquals(HomeDragLane.Right, dragPairSide(HomeDragLane.Right, Float.NaN))
    }

    @Test
    fun should_growOwnRowByBuffer() {
        val own = DragRowBand(HomeFeedRow.Halves(Activities, MadeForYou), 100f, 400f)
        assertTrue(dragInOwnRow(84f, own, bufferPx = 16f))
        assertTrue(dragInOwnRow(416f, own, bufferPx = 16f))
        assertFalse(dragInOwnRow(83f, own, bufferPx = 16f))
        assertFalse(dragInOwnRow(417f, own, bufferPx = 16f))
    }

    @Test
    fun should_useNamedTokens_forTheRules() {
        assertEquals(16f, HomeDragRuleTokens.RowBuffer.value, 0f)
        assertEquals(.45f, HomeDragRuleTokens.SwapToLeft, 0f)
        assertEquals(.55f, HomeDragRuleTokens.SwapToRight, 0f)
        assertEquals(.25f, HomeDragRuleTokens.RowGapZone, 0f)
        assertEquals(.30f, HomeDragRuleTokens.PairLeft, 0f)
        assertEquals(.70f, HomeDragRuleTokens.PairRight, 0f)
    }

    // ── Rule 2: over another full row ───────────────────────────────────────

    @Test
    fun should_splitFullRowIntoQuarterHalfQuarter() {
        val row = band(MemoryGrid, 100f, 500f)
        assertEquals(DragRowPart.Above, dragRowPart(199f, row))
        assertEquals(DragRowPart.Middle, dragRowPart(201f, row))
        assertEquals(DragRowPart.Middle, dragRowPart(399f, row))
        assertEquals(DragRowPart.Below, dragRowPart(401f, row))
    }

    @Test
    fun should_pairOnlyInOuterThirtyPercent() {
        assertEquals(HomeDragLane.Left, dragPairZone(.29f))
        assertNull(dragPairZone(.31f))
        assertNull(dragPairZone(.5f))
        assertNull(dragPairZone(.69f))
        assertEquals(HomeDragLane.Right, dragPairZone(.71f))
        assertNull(dragPairZone(Float.NaN))
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

    private fun full(rel: Float, fingerY: Float) =
        dragSideStep(ids, pairs, MadeForYou, side = null, rel = rel, fingerY = fingerY, bands = bands, bufferPx = 16f)

    @Test
    fun should_insertOwnRow_when_inTopOrBottomQuarterOfFullRow_anyX() {
        for (rel in listOf(.1f, .5f, .9f)) {
            val above = full(rel, 50f) as DragSideStep.Arrange
            assertNull(above.side)
            assertEquals(listOf(MadeForYou, Activities, RecentlyAdded, RecentlyPlayed, MemoryGrid), above.arrangement.ids)
            assertEquals(pairs, above.arrangement.pairs)
            // Under Activities already: the bottom quarter keeps it where it is.
            assertEquals(DragArrangement(ids, pairs), (full(rel, 250f) as DragSideStep.Arrange).arrangement)
            val belowMemories = full(rel, 1080f) as DragSideStep.Arrange
            assertEquals(listOf(Activities, RecentlyAdded, RecentlyPlayed, MemoryGrid, MadeForYou), belowMemories.arrangement.ids)
        }
    }

    @Test
    fun should_pairWithFullRow_when_middleAndOuterThirty() {
        val left = full(.2f, 150f) as DragSideStep.Arrange
        assertEquals(HomeDragLane.Left, left.side)
        assertEquals(listOf(MadeForYou, Activities, RecentlyAdded, RecentlyPlayed, MemoryGrid), left.arrangement.ids)
        assertEquals(pairs + (MadeForYou to Activities), left.arrangement.pairs)
        val right = full(.8f, 150f) as DragSideStep.Arrange
        assertEquals(HomeDragLane.Right, right.side)
        assertEquals(ids, right.arrangement.ids)
        assertEquals(pairs + (Activities to MadeForYou), right.arrangement.pairs)
    }

    @Test
    fun should_leaveToHalfTravel_when_middleCentreOwnRowOrGap() {
        assertEquals(DragSideStep.Vertical, full(.5f, 150f))
        assertEquals(DragSideStep.Vertical, full(.31f, 150f))
        // Over its own row, any x.
        assertEquals(DragSideStep.Vertical, full(.05f, 400f))
        // In the gap between rows.
        assertEquals(DragSideStep.Vertical, full(.05f, 309f))
    }

    @Test
    fun should_pairWithNothing_but_stillPlaceByRows_while_edgeScrollRuns() {
        fun scrolling(rel: Float, fingerY: Float) =
            dragSideStep(ids, pairs, MadeForYou, side = null, rel = rel, fingerY = fingerY, bands = bands, bufferPx = 16f, pairing = false)
        // A full row's middle, either side: the half-travel rule moves it past, no pair.
        assertEquals(DragSideStep.Vertical, scrolling(.8f, 150f))
        assertEquals(DragSideStep.Vertical, scrolling(.2f, 150f))
        // Its top and bottom quarters still put it in a row of its own.
        assertEquals(full(.9f, 50f), scrolling(.9f, 50f))
        assertEquals(full(.9f, 1080f), scrolling(.9f, 1080f))
        // A half leaving its pair over another full row's side goes above or below it, by the row's middle.
        val half = dragSideStep(besideIds, besidePairs, MadeForYou, HomeDragLane.Right, .9f, 700f, besideBands, bufferPx = 16f, pairing = false)
        assertEquals(beside(.5f, 700f), half)
    }

    @Test
    fun should_insertBesidePairOfOthers_byItsMiddle_neverThreeAcross() {
        // Above the pair already: its top half keeps it there; its bottom half puts it below.
        assertEquals(DragArrangement(ids, pairs), (full(.1f, 600f) as DragSideStep.Arrange).arrangement)
        val below = full(.9f, 750f) as DragSideStep.Arrange
        assertNull(below.side)
        assertEquals(listOf(Activities, RecentlyAdded, RecentlyPlayed, MadeForYou, MemoryGrid), below.arrangement.ids)
        assertEquals(pairs, below.arrangement.pairs)
    }

    // Beside Activities (on its right): rows Activities | MadeForYou 0..300, the pair 318..618, MemoryGrid 636..880.
    private val besideIds = listOf(Activities, MadeForYou, RecentlyAdded, RecentlyPlayed, MemoryGrid)
    private val besidePairs = mapOf(Activities to MadeForYou, RecentlyAdded to RecentlyPlayed)
    private val besideBands = listOf(
        DragRowBand(HomeFeedRow.Halves(Activities, MadeForYou), 0f, 300f),
        DragRowBand(HomeFeedRow.Halves(RecentlyAdded, RecentlyPlayed), 318f, 618f),
        band(MemoryGrid, 636f, 880f),
    )

    private fun beside(rel: Float, fingerY: Float) =
        dragSideStep(besideIds, besidePairs, MadeForYou, HomeDragLane.Right, rel, fingerY, besideBands, bufferPx = 16f)

    @Test
    fun should_stayBesidePartner_when_crossingTheMiddleOfItsOwnRow() {
        assertEquals(DragSideStep.Stay, beside(.5f, 150f))
        assertEquals(DragSideStep.Stay, beside(.46f, 150f))
        // Never full width while it stays in its row, whatever x.
        assertEquals(DragSideStep.Stay, beside(.9f, 150f))
    }

    @Test
    fun should_swapWithPartner_when_pastTheSwapLine() {
        val step = beside(.44f, 150f) as DragSideStep.Arrange
        assertEquals(HomeDragLane.Left, step.side)
        assertEquals(mapOf(MadeForYou to Activities, RecentlyAdded to RecentlyPlayed), step.arrangement.pairs)
        assertEquals(MadeForYou, step.arrangement.ids.first())
    }

    @Test
    fun should_stayInRow_when_fingerWobblesWithinBuffer() {
        assertEquals(DragSideStep.Stay, beside(.5f, -10f))
        assertEquals(DragSideStep.Stay, beside(.5f, 310f))
        // Swapping sides still works in the buffer.
        assertEquals(HomeDragLane.Left, (beside(.3f, 312f) as DragSideStep.Arrange).side)
    }

    @Test
    fun should_leavePairAndPartnerFull_when_outOfRowPastBuffer() {
        val above = beside(.5f, -20f) as DragSideStep.Arrange
        assertNull(above.side)
        assertEquals(listOf(MadeForYou, Activities, RecentlyAdded, RecentlyPlayed, MemoryGrid), above.arrangement.ids)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), above.arrangement.pairs)
        // Out of its row in the gap under the pair below: a row of its own there.
        val gap = beside(.5f, 627f) as DragSideStep.Arrange
        assertEquals(listOf(Activities, RecentlyAdded, RecentlyPlayed, MadeForYou, MemoryGrid), gap.arrangement.ids)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), gap.arrangement.pairs)
    }

    @Test
    fun should_becomeOwnRowBesidePair_when_overPairOfOthers() {
        val above = beside(.9f, 400f) as DragSideStep.Arrange
        assertNull(above.side)
        assertEquals(listOf(Activities, MadeForYou, RecentlyAdded, RecentlyPlayed, MemoryGrid), above.arrangement.ids)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), above.arrangement.pairs)
        val below = beside(.1f, 550f) as DragSideStep.Arrange
        assertEquals(listOf(Activities, RecentlyAdded, RecentlyPlayed, MadeForYou, MemoryGrid), below.arrangement.ids)
    }

    @Test
    fun should_applyFullRowThirds_when_halfLeavesForAnotherFullRow() {
        // MemoryGrid 636..880: top quarter to 697, bottom from 819, centre 758.
        val paired = beside(.2f, 700f) as DragSideStep.Arrange
        assertEquals(HomeDragLane.Left, paired.side)
        assertEquals(listOf(Activities, RecentlyAdded, RecentlyPlayed, MadeForYou, MemoryGrid), paired.arrangement.ids)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed, MadeForYou to MemoryGrid), paired.arrangement.pairs)
        // The middle's centre: a row of its own above or below by the row's middle.
        val above = beside(.5f, 700f) as DragSideStep.Arrange
        assertNull(above.side)
        assertEquals(listOf(Activities, RecentlyAdded, RecentlyPlayed, MadeForYou, MemoryGrid), above.arrangement.ids)
        assertEquals(mapOf(RecentlyAdded to RecentlyPlayed), above.arrangement.pairs)
        val belowByMiddle = beside(.5f, 800f) as DragSideStep.Arrange
        assertEquals(listOf(Activities, RecentlyAdded, RecentlyPlayed, MemoryGrid, MadeForYou), belowByMiddle.arrangement.ids)
        // The top quarter, any x: above.
        assertEquals(above.arrangement, (beside(.95f, 650f) as DragSideStep.Arrange).arrangement)
        // The bottom quarter, any x: below.
        val below = beside(.05f, 870f) as DragSideStep.Arrange
        assertNull(below.side)
        assertEquals(listOf(Activities, RecentlyAdded, RecentlyPlayed, MemoryGrid, MadeForYou), below.arrangement.ids)
    }

    // ── Rule 4: the anchor ──────────────────────────────────────────────────

    @Test
    fun should_rescaleGrabX_when_slotWidthChanges() {
        // Grabbed 190px into a 490px half; full (1000px) puts the finger 387.8px in, the same 38.8%.
        assertEquals(387.755f, dragRescaleGrabX(190f, oldWidth = 490f, newWidth = 1000f), .01f)
        assertEquals(190f, dragRescaleGrabX(387.755f, oldWidth = 1000f, newWidth = 490f), .01f)
        assertEquals(190f, dragRescaleGrabX(190f, oldWidth = 490f, newWidth = 490f), 0f)
        assertEquals(190f, dragRescaleGrabX(190f, oldWidth = 0f, newWidth = 1000f), 0f)
    }

    @Test
    fun should_measureLaneWidths() {
        assertEquals(1000f, dragLaneWidth(HomeDragLane.Full, contentWidth = 1000f, gap = 20f), 0f)
        assertEquals(490f, dragLaneWidth(HomeDragLane.Left, contentWidth = 1000f, gap = 20f), 0f)
        assertEquals(490f, dragLaneWidth(HomeDragLane.Right, contentWidth = 1000f, gap = 20f), 0f)
    }

    // ── Edge scroll ─────────────────────────────────────────────────────────

    @Test
    fun should_easeScrollInWithDepthSquared_fromMinToMax() {
        // Area 100..1100, 100px edges, 120..900px/s.
        fun speed(y: Float) = dragAutoScrollSpeed(y, top = 100f, bottom = 1100f, edge = 100f, min = 120f, max = 900f)
        assertEquals(0f, speed(600f), 0f)
        assertEquals(0f, speed(200f), 0f)
        assertEquals(0f, speed(1000f), 0f)
        // Just inside the band: the minimum.
        assertEquals(-120f, speed(199.999f), .1f)
        assertEquals(120f, speed(1000.001f), .1f)
        // Halfway in: min + (max - min) · 0.25.
        assertEquals(-315f, speed(150f), .01f)
        assertEquals(315f, speed(1050f), .01f)
        // At the edge and beyond: the maximum.
        assertEquals(-900f, speed(100f), .01f)
        assertEquals(-900f, speed(20f), .01f)
        assertEquals(900f, speed(1300f), .01f)
    }

    @Test
    fun should_useScrollTokens() {
        // Quicker edge scroll (owner 2026-11-27).
        assertEquals(96f, HomeDragTokens.AutoScrollEdge.value, 0f)
        assertEquals(.25f, HomeDragTokens.AutoScrollEdgeMaxFraction, 0f)
        assertEquals(200f, HomeDragTokens.AutoScrollMin.value, 0f)
        assertEquals(1400f, HomeDragTokens.AutoScrollMax.value, 0f)
        assertEquals(4f, HomeDragTokens.EdgeStartPush.value, 0f)
        assertEquals(120f, HomeDragTokens.EdgeStartDwellMs, 0f)
    }

    @Test
    fun should_capTheEdgeBandAtAQuarterOfTheArea_when_theWindowIsShort() {
        // A tablet's 1900px area keeps the 96dp (192px at 2x) band; a landscape phone's 600px caps it at 150.
        assertEquals(192f, com.gpo.yoin.shared.home.dragEdgeBand(192f, 100f, 2000f), 0f)
        assertEquals(150f, com.gpo.yoin.shared.home.dragEdgeBand(192f, 100f, 700f), 0f)
    }

    @Test
    fun should_takeNearerEnd_when_areaNarrowerThanTwoEdges() {
        assertEquals(-1, dragEdgeOf(110f, top = 100f, bottom = 250f, edge = 100f))
        assertEquals(1, dragEdgeOf(240f, top = 100f, bottom = 250f, edge = 100f))
        assertEquals(0, dragEdgeOf(Float.NaN, top = 0f, bottom = 1000f, edge = 100f))
        assertTrue(dragAutoScrollSpeed(110f, top = 100f, bottom = 250f, edge = 100f, min = 1f, max = 10f) < 0f)
        assertTrue(dragAutoScrollSpeed(240f, top = 100f, bottom = 250f, edge = 100f, min = 1f, max = 10f) > 0f)
    }

    @Test
    fun should_holdEdgeGuard_untilPushedOrRested() {
        fun holds(startEdge: Int, dy: Float, heldMs: Float) =
            dragEdgeGuardHolds(startEdge, startY = 1000f, y = 1000f + dy, heldMs = heldMs, pushPx = 16f, dwellMs = 180f)
        // Not lifted in a band: no guard.
        assertFalse(holds(0, 0f, 0f))
        // Bottom band: 16px further down lets it go, less (or away from the edge) doesn't.
        assertTrue(holds(1, 15f, 0f))
        assertFalse(holds(1, 16f, 0f))
        assertTrue(holds(1, -40f, 0f))
        // Top band: up is toward the edge.
        assertTrue(holds(-1, -15f, 0f))
        assertFalse(holds(-1, -16f, 0f))
        // Resting there 180ms lets it go too.
        assertTrue(holds(1, 0f, 179f))
        assertFalse(holds(1, 0f, 180f))
    }

    // ── Rule 5: interruptions ───────────────────────────────────────────────

    @Test
    fun should_keepDrop_onlyForDoneAndAppExits() {
        assertTrue(dragExitKeepsDrop(HomeEditExitReason.Done))
        assertTrue(dragExitKeepsDrop(HomeEditExitReason.Programmatic))
        assertFalse(dragExitKeepsDrop(HomeEditExitReason.Back))
        assertFalse(dragExitKeepsDrop(HomeEditExitReason.Blank))
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
