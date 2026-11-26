package com.gpo.yoin.ui.home.edit

import androidx.compose.ui.unit.dp
import com.gpo.yoin.shared.home.DragSideStep
import com.gpo.yoin.shared.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.shared.home.sections as sharedSections

// Home edit reordering (owner 2026-11-22; two-column rules by design, approved 2026-11-22): the drop rules
// live in :shared (com.gpo.yoin.shared.home.HomeDragRules.kt, golden home-drag.json), so iOS and HarmonyOS
// drop where Android does. These keep the app's names; HomeDragEngine drives them.

/** Where a section sits across a two-column row ([com.gpo.yoin.shared.home.HomeDragLane]). */
internal typealias HomeDragLane = com.gpo.yoin.shared.home.HomeDragLane

/** Numbers of the drop rules ([com.gpo.yoin.shared.home.HomeDragRuleTokens], the lengths as Dp). */
internal object HomeDragRuleTokens {
    /** A half's own row reaches this far above and below it: a finger wobbling while it swaps sides stays in. */
    val RowBuffer = com.gpo.yoin.shared.home.HomeDragRuleTokens.RowBuffer.dp

    const val SwapToLeft = com.gpo.yoin.shared.home.HomeDragRuleTokens.SwapToLeft
    const val SwapToRight = com.gpo.yoin.shared.home.HomeDragRuleTokens.SwapToRight
    const val RowGapZone = com.gpo.yoin.shared.home.HomeDragRuleTokens.RowGapZone
    const val PairLeft = com.gpo.yoin.shared.home.HomeDragRuleTokens.PairLeft
    const val PairRight = com.gpo.yoin.shared.home.HomeDragRuleTokens.PairRight
}

/** An arrangement of the drag ([com.gpo.yoin.shared.home.DragArrangement]). */
internal typealias DragArrangement = com.gpo.yoin.shared.home.DragArrangement

/** A laid-out row's vertical extent in Box px ([com.gpo.yoin.shared.home.DragRowBand]). */
internal typealias DragRowBand = com.gpo.yoin.shared.home.DragRowBand

/** Where over another full row the finger is ([com.gpo.yoin.shared.home.DragRowPart]). */
internal typealias DragRowPart = com.gpo.yoin.shared.home.DragRowPart

internal fun dragRows(ids: List<HomeSection>, pairs: Map<HomeSection, HomeSection>, twoColumn: Boolean): List<HomeFeedRow> =
    com.gpo.yoin.shared.home.dragRows(ids, pairs, twoColumn)

internal fun dragLaneOf(section: HomeSection, rows: List<HomeFeedRow>): HomeDragLane =
    com.gpo.yoin.shared.home.dragLaneOf(section, rows)

internal fun dragRowIndexOf(section: HomeSection, rows: List<HomeFeedRow>): Int =
    com.gpo.yoin.shared.home.dragRowIndexOf(section, rows)

/** [row]'s sections, left to right. */
internal fun HomeFeedRow.sections(): List<HomeSection> = sharedSections()

internal fun dragUnpair(pairs: Map<HomeSection, HomeSection>, section: HomeSection): Map<HomeSection, HomeSection> =
    com.gpo.yoin.shared.home.dragUnpair(pairs, section)

internal fun dragRel(fingerX: Float, contentLeft: Float, contentWidth: Float): Float =
    com.gpo.yoin.shared.home.dragRel(fingerX, contentLeft, contentWidth)

internal fun dragPairSide(side: HomeDragLane, rel: Float): HomeDragLane = com.gpo.yoin.shared.home.dragPairSide(side, rel)

internal fun dragInOwnRow(fingerY: Float, own: DragRowBand, bufferPx: Float): Boolean =
    com.gpo.yoin.shared.home.dragInOwnRow(fingerY, own, bufferPx)

internal fun dragRowPart(fingerY: Float, band: DragRowBand): DragRowPart = com.gpo.yoin.shared.home.dragRowPart(fingerY, band)

internal fun dragPairZone(rel: Float): HomeDragLane? = com.gpo.yoin.shared.home.dragPairZone(rel)

internal fun dragSideAllowed(row: HomeFeedRow, dragged: HomeSection): Boolean = com.gpo.yoin.shared.home.dragSideAllowed(row, dragged)

internal fun dragPlaceBeside(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    dragged: HomeSection,
    target: HomeSection,
    side: HomeDragLane,
): DragArrangement = com.gpo.yoin.shared.home.dragPlaceBeside(ids, pairs, dragged, target, side)

internal fun dragInsertRow(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    dragged: HomeSection,
    gap: Int,
): DragArrangement = com.gpo.yoin.shared.home.dragInsertRow(ids, pairs, dragged, gap)

internal fun dragSwapRow(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    dragged: HomeSection,
    dir: Int,
    twoColumn: Boolean,
): List<HomeSection> = com.gpo.yoin.shared.home.dragSwapRow(ids, pairs, dragged, dir, twoColumn)

internal fun dragSwapDir(visualTop: Float, slot: DragRowBand, prev: DragRowBand?, next: DragRowBand?): Int =
    com.gpo.yoin.shared.home.dragSwapDir(visualTop, slot, prev, next)

internal fun dragSideStep(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    dragged: HomeSection,
    side: HomeDragLane?,
    rel: Float,
    fingerY: Float,
    bands: List<DragRowBand>,
    bufferPx: Float,
    pairing: Boolean = true,
): DragSideStep = com.gpo.yoin.shared.home.dragSideStep(ids, pairs, dragged, side, rel, fingerY, bands, bufferPx, pairing)

internal fun dragPartnerOf(pairs: Map<HomeSection, HomeSection>, section: HomeSection): HomeSection? =
    com.gpo.yoin.shared.home.dragPartnerOf(pairs, section)

internal fun dragRescaleGrabX(grabX: Float, oldWidth: Float, newWidth: Float): Float =
    com.gpo.yoin.shared.home.dragRescaleGrabX(grabX, oldWidth, newWidth)

internal fun dragLaneWidth(lane: HomeDragLane, contentWidth: Float, gap: Float): Float =
    com.gpo.yoin.shared.home.dragLaneWidth(lane, contentWidth, gap)

internal fun dragEdgeOf(y: Float, top: Float, bottom: Float, edge: Float): Int = com.gpo.yoin.shared.home.dragEdgeOf(y, top, bottom, edge)

internal fun dragAutoScrollSpeed(y: Float, top: Float, bottom: Float, edge: Float, min: Float, max: Float): Float =
    com.gpo.yoin.shared.home.dragAutoScrollSpeed(y, top, bottom, edge, min, max)

internal fun dragEdgeGuardHolds(startEdge: Int, startY: Float, y: Float, heldMs: Float, pushPx: Float, dwellMs: Float): Boolean =
    com.gpo.yoin.shared.home.dragEdgeGuardHolds(startEdge, startY, y, heldMs, pushPx, dwellMs)

internal fun dragExitKeepsDrop(reason: HomeEditExitReason): Boolean = com.gpo.yoin.shared.home.dragExitKeepsDrop(reason)

internal fun dragLayoutFollows(visibleKeys: List<HomeSection>, rows: List<HomeFeedRow>, draggedKey: HomeSection): Boolean =
    com.gpo.yoin.shared.home.dragLayoutFollows(visibleKeys, rows, draggedKey)
