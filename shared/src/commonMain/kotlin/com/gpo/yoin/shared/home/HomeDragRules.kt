package com.gpo.yoin.shared.home

// Home edit reordering (owner 2026-11-22; two-column rules by design,
// approved 2026-11-22): a block dragged in place, in the feed or in the
// pinched list. The other blocks make way as its slot changes, and a finger
// near the top or bottom scrolls. In two columns:
//  1. A half dragged within its own row (grown 16dp above and below) only
//     changes sides, across the middle with hysteresis (0.45 / 0.55); only a
//     finger out of the row leaves the partner full.
//  2. Over another block's full row: its top and bottom quarters put the
//     dragged block in a row of its own above or below it; in its middle half
//     the outer 30% each side pairs with it, and between them the half-travel
//     rule moves rows.
//  3. Over a pair of two others: a row of its own above or below it, by the
//     row's middle. Never three across, never splitting someone else's pair.
// Width is the span handle's; a drag changes position and pairing only.
// Everything here is pure; HomeDragEngine drives it.

/** Where a section sits across a two-column row. */
enum class HomeDragLane { Full, Left, Right }

/** Numbers of the drop rules (dp unless noted). */
object HomeDragRuleTokens {
    /** A half's own row reaches this far above and below it: a finger wobbling while it swaps sides stays in. */
    const val RowBuffer = 16f

    /** A right half swaps to the left once the finger is left of this fraction across the content… */
    const val SwapToLeft = .45f

    /** …and a left half swaps right past this one. Between the two it stays (hysteresis). */
    const val SwapToRight = .55f

    /** The top and the bottom fraction of another full row that put the dragged block in a row of its own beside it. */
    const val RowGapZone = .25f

    /** Over a full row's middle: left of this fraction across the content pairs as [dragged | row]… */
    const val PairLeft = .30f

    /** …right of this one as [row | dragged]. Between the two the half-travel rule decides. */
    const val PairRight = .70f
}

/** Numbers of the in-place drag (dp unless noted). */
object HomeDragTokens {
    // Quicker edge scroll (owner 2026-11-27, 「更灵敏：更早开始、加速更快」): the band was narrower than
    // a fingertip and a half over the bar, the first half of it crawled (120–330dp/s), and a block lifted
    // near the edge sat still for 180ms.

    /** The list scrolls while the finger is this close to the bar (or the status tide)… */
    const val AutoScrollEdge = 96f

    /** …but a band never takes more than this fraction of the scroll area: a short window keeps its middle half. */
    const val AutoScrollEdgeMaxFraction = .25f

    /** …from this many dp/s where the band starts… */
    const val AutoScrollMin = 200f

    /** …easing in (depth squared) to this many at the edge. */
    const val AutoScrollMax = 1400f

    /** Lifted with the finger already in a band, it scrolls once the finger pushes this much further toward the edge… */
    const val EdgeStartPush = 4f

    /** …or rests there this long, ms. */
    const val EdgeStartDwellMs = 120f

    /** A change waits for this much finger (or scroll) travel since the last one. */
    const val ChangeTravel = 12f

    /** A frame after a stall scrolls no more than this far in time, s. */
    const val MaxFrameSeconds = 1f / 24f
}

/** An arrangement of the drag: the order and the pairs (left → right, each pair adjacent in [ids]). */
data class DragArrangement(val ids: List<HomeSection>, val pairs: Map<HomeSection, HomeSection>)

/** A laid-out row's vertical extent in Box px. */
data class DragRowBand(val row: HomeFeedRow, val top: Float, val bottom: Float) {
    val center: Float get() = (top + bottom) / 2f
}

/** The drag's rows: [ids] in order; in [twoColumn], [pairs] side by side. */
fun dragRows(ids: List<HomeSection>, pairs: Map<HomeSection, HomeSection>, twoColumn: Boolean): List<HomeFeedRow> =
    homeFeedRows(ids, pairs, twoColumn)

/** [section]'s lane in [rows] (Full when absent). */
fun dragLaneOf(section: HomeSection, rows: List<HomeFeedRow>): HomeDragLane {
    for (row in rows) {
        if (row is HomeFeedRow.Halves) {
            if (row.left == section) return HomeDragLane.Left
            if (row.right == section) return HomeDragLane.Right
        }
    }
    return HomeDragLane.Full
}

/** The index of the row holding [section], or -1. */
fun dragRowIndexOf(section: HomeSection, rows: List<HomeFeedRow>): Int =
    rows.indexOfFirst { section in it.sections() }

/** [row]'s sections, left to right. */
fun HomeFeedRow.sections(): List<HomeSection> = when (this) {
    is HomeFeedRow.Full -> listOf(section)
    is HomeFeedRow.Halves -> listOf(left, right)
}

/** [pairs] without the pair [section] is in: lifted out, its partner is full. */
fun dragUnpair(pairs: Map<HomeSection, HomeSection>, section: HomeSection): Map<HomeSection, HomeSection> =
    pairs.filter { (left, right) -> left != section && right != section }

/** The finger's fraction across the content ([contentLeft], [contentWidth]); NaN with no finger or no width. */
fun dragRel(fingerX: Float, contentLeft: Float, contentWidth: Float): Float =
    if (contentWidth <= 0f || fingerX.isNaN()) Float.NaN else (fingerX - contentLeft) / contentWidth

/**
 * Rule 1: the side a half on [side] of its own pair takes with the finger at
 * [rel] across the content. A right half goes left below
 * [HomeDragRuleTokens.SwapToLeft], a left half right above
 * [HomeDragRuleTokens.SwapToRight]; between them it stays.
 */
fun dragPairSide(side: HomeDragLane, rel: Float): HomeDragLane = when {
    rel.isNaN() -> side
    side == HomeDragLane.Right && rel < HomeDragRuleTokens.SwapToLeft -> HomeDragLane.Left
    side == HomeDragLane.Left && rel > HomeDragRuleTokens.SwapToRight -> HomeDragLane.Right
    else -> side
}

/** Rule 1: the finger at [fingerY] is still in the row [own], grown by [bufferPx] above and below. */
fun dragInOwnRow(fingerY: Float, own: DragRowBand, bufferPx: Float): Boolean =
    fingerY >= own.top - bufferPx && fingerY <= own.bottom + bufferPx

/** Where over another full row the finger is: its top quarter, its middle half or its bottom quarter. */
enum class DragRowPart { Above, Middle, Below }

/** Rule 2: the part of [band] the finger at [fingerY] is over ([HomeDragRuleTokens.RowGapZone]). */
fun dragRowPart(fingerY: Float, band: DragRowBand): DragRowPart {
    val height = band.bottom - band.top
    if (height <= 0f) return if (fingerY < band.center) DragRowPart.Above else DragRowPart.Below
    val f = (fingerY - band.top) / height
    return when {
        f < HomeDragRuleTokens.RowGapZone -> DragRowPart.Above
        f > 1f - HomeDragRuleTokens.RowGapZone -> DragRowPart.Below
        else -> DragRowPart.Middle
    }
}

/** Rule 2: over a full row's middle, the side the dragged block pairs on at [rel], or null between. */
fun dragPairZone(rel: Float): HomeDragLane? = when {
    rel.isNaN() -> null
    rel < HomeDragRuleTokens.PairLeft -> HomeDragLane.Left
    rel > HomeDragRuleTokens.PairRight -> HomeDragLane.Right
    else -> null
}

/**
 * The dragged block may go beside [row]: a full row of another section, or
 * the pair it is already in (it only changes sides). Never beside a pair of
 * two other sections.
 */
fun dragSideAllowed(row: HomeFeedRow, dragged: HomeSection): Boolean = when (row) {
    is HomeFeedRow.Full -> row.section != dragged
    is HomeFeedRow.Halves -> row.left == dragged || row.right == dragged
}

/**
 * [dragged] put beside [target] on [side] (Left or Right): out of wherever
 * it was, next to the target in the order, the two a pair.
 */
fun dragPlaceBeside(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    dragged: HomeSection,
    target: HomeSection,
    side: HomeDragLane,
): DragArrangement {
    require(side != HomeDragLane.Full && dragged != target)
    val rest = ids - dragged
    val unpaired = dragUnpair(dragUnpair(pairs, dragged), target)
    val at = rest.indexOf(target)
    val order = rest.toMutableList().apply { add(if (side == HomeDragLane.Left) at else at + 1, dragged) }
    val pair = if (side == HomeDragLane.Left) dragged to target else target to dragged
    return DragArrangement(order, unpaired + pair)
}

/** [dragged] as a full row of its own at [gap] among the two-column rows without it (0 = first, their count = last). */
fun dragInsertRow(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    dragged: HomeSection,
    gap: Int,
): DragArrangement {
    val rest = ids - dragged
    val unpaired = dragUnpair(pairs, dragged)
    val rows = dragRows(rest, unpaired, twoColumn = true)
    val at = rows.getOrNull(gap)?.let { rest.indexOf(it.first) } ?: rest.size
    val order = rest.toMutableList().apply { add(at, dragged) }
    return DragArrangement(order, unpaired)
}

/**
 * Between rows, the dragged block's own row swapped one row [dir] (±1): it
 * jumps the whole neighbouring row, a pair included.
 */
fun dragSwapRow(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    dragged: HomeSection,
    dir: Int,
    twoColumn: Boolean,
): List<HomeSection> {
    val rows = dragRows(ids, pairs, twoColumn)
    val k = dragRowIndexOf(dragged, rows)
    val neighbour = rows.getOrNull(k + dir) ?: return ids
    val rest = ids - dragged
    val at = if (dir > 0) rest.indexOf(neighbour.sections().last()) + 1 else rest.indexOf(neighbour.first)
    return rest.toMutableList().apply { add(at, dragged) }
}

/**
 * Between rows, the half-travel rule: the dragged block, drawn with its top
 * at [visualTop], takes the slot above when it is closer to where it would
 * sit there than to its own [slot] (its top would be [prev]'s), or the slot
 * below likewise (its bottom would be [next]'s). The two thresholds meet, so
 * a swap never swaps straight back. Rows not laid out (null) never swap.
 * Returns -1, 0 or +1.
 */
fun dragSwapDir(visualTop: Float, slot: DragRowBand, prev: DragRowBand?, next: DragRowBand?): Int {
    if (prev != null && visualTop < (slot.top + prev.top) / 2f) return -1
    if (next != null) {
        val topThere = next.bottom - (slot.bottom - slot.top)
        if (visualTop > (slot.top + topThere) / 2f) return 1
    }
    return 0
}

/** What the finger asks of a two-column drag ([dragSideStep]). */
sealed interface DragSideStep {
    /** A row of its own over its own row, a gap or another row's middle: the half-travel rule ([dragSwapDir]) decides. */
    data object Vertical : DragSideStep

    /** Beside its partner, staying there. */
    data object Stay : DragSideStep

    /** A new arrangement, the dragged block now on [side] (null: a row of its own). */
    data class Arrange(val arrangement: DragArrangement, val side: HomeDragLane?) : DragSideStep
}

/**
 * Two columns: where the finger at [rel] across the content and [fingerY]
 * puts the [dragged] block, now a half on [side] of a pair (null: a row of
 * its own). [bands] are the laid-out rows of the live arrangement, top to
 * bottom; [bufferPx] is [HomeDragRuleTokens.RowBuffer]. Pure.
 *
 * 1. A half with the finger in its own row (± the buffer) only changes
 *    sides ([dragPairSide]). Out of it, the partner turns full and the rules
 *    below place the block.
 * 2. Over another full row: its top or bottom quarter puts the block in a
 *    row of its own above or below; in its middle the outer 30% pairs with
 *    it, and between them a row of its own swaps by the half-travel rule
 *    (a half leaving its pair goes above or below by the row's middle).
 * 3. Over a pair of two others: a row of its own above or below it, by its middle.
 *
 * A half in a gap between rows becomes a row of its own there.
 *
 * Not [pairing] (the edge scroll is moving the rows under the finger): a
 * full row's middle pairs with nothing, the half-travel rule moves the block
 * past it. Each row the scroll carried under a finger near a side paired the
 * block with it and let it go again a moment later: the block sprang half ⇄
 * full under the finger with every row (2026-11-23).
 */
fun dragSideStep(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    dragged: HomeSection,
    side: HomeDragLane?,
    rel: Float,
    fingerY: Float,
    bands: List<DragRowBand>,
    bufferPx: Float,
    pairing: Boolean = true,
): DragSideStep {
    val own = bands.firstOrNull { dragged in it.row.sections() }
    val partner = if (side != null) dragPartnerOf(pairs, dragged) else null
    if (side != null && partner != null) {
        // Its own row not laid out (scrolled off): nothing to judge against yet.
        if (own == null) return DragSideStep.Stay
        if (dragInOwnRow(fingerY, own, bufferPx)) {
            val next = dragPairSide(side, rel)
            if (next == side) return DragSideStep.Stay
            return DragSideStep.Arrange(dragPlaceBeside(ids, pairs, dragged, partner, next), next)
        }
    } else if (own != null && fingerY >= own.top && fingerY <= own.bottom) {
        return DragSideStep.Vertical
    }
    val under = bands.firstOrNull { it !== own && fingerY >= it.top && fingerY <= it.bottom }
    fun ownRow(band: DragRowBand, below: Boolean) = ownRowBy(ids, pairs, dragged, band, below)
    if (under == null) {
        if (partner == null) return DragSideStep.Vertical
        val above = bands.lastOrNull { it.bottom < fingerY }
        val belowBand = bands.firstOrNull { it.top > fingerY }
        return when {
            above != null -> ownRow(above, below = true)
            belowBand != null -> ownRow(belowBand, below = false)
            else -> DragSideStep.Stay
        }
    }
    val row = under.row
    if (row is HomeFeedRow.Full) {
        when (dragRowPart(fingerY, under)) {
            DragRowPart.Above -> return ownRow(under, below = false)
            DragRowPart.Below -> return ownRow(under, below = true)
            DragRowPart.Middle -> {
                val zone = if (pairing) dragPairZone(rel) else null
                if (zone != null) return DragSideStep.Arrange(dragPlaceBeside(ids, pairs, dragged, row.section, zone), zone)
                if (partner == null) return DragSideStep.Vertical
                return ownRow(under, below = fingerY > under.center)
            }
        }
    }
    return ownRow(under, below = fingerY > under.center)
}

// A row of its own right above (or [below]) [band]'s row; a band of the dragged block's own pair counts as its partner's.
private fun ownRowBy(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    dragged: HomeSection,
    band: DragRowBand,
    below: Boolean,
): DragSideStep.Arrange {
    val anchor = band.row.sections().firstOrNull { it != dragged } ?: band.row.first
    return DragSideStep.Arrange(dragInsertRow(ids, pairs, dragged, dragGapBy(ids, pairs, dragged, anchor, below)), null)
}

/** [section]'s partner in [pairs], or null when it is a row of its own. */
fun dragPartnerOf(pairs: Map<HomeSection, HomeSection>, section: HomeSection): HomeSection? =
    pairs[section] ?: pairs.entries.firstOrNull { it.value == section }?.key

// The gap above (or [below]) the row holding [section], among the two-column rows without [dragged].
private fun dragGapBy(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    dragged: HomeSection,
    section: HomeSection,
    below: Boolean,
): Int {
    val rest = dragRows(ids - dragged, dragUnpair(pairs, dragged), twoColumn = true)
    val index = dragRowIndexOf(section, rest).coerceAtLeast(0)
    return if (below) index + 1 else index
}

/**
 * Rule 4 (anchor): the finger's x offset in the dragged block, [grabX] of
 * [oldWidth], at the same fraction of [newWidth] once its slot turns half or
 * full, so the block doesn't jump sideways under the finger. Pure.
 */
fun dragRescaleGrabX(grabX: Float, oldWidth: Float, newWidth: Float): Float =
    if (oldWidth <= 0f || newWidth <= 0f || oldWidth == newWidth) grabX else grabX / oldWidth * newWidth

/** A slot's width across the content: a full row's, or a half's beside the column [gap]. */
fun dragLaneWidth(lane: HomeDragLane, contentWidth: Float, gap: Float): Float =
    if (lane == HomeDragLane.Full) contentWidth else ((contentWidth - gap) / 2f).coerceAtLeast(0f)

// ── Edge scroll ───────────────────────────────────────────────────────────

/** -1 in the [edge]-deep band at [top], +1 in the one at [bottom], else 0. A band narrower than two edges takes the nearer end. */
fun dragEdgeOf(y: Float, top: Float, bottom: Float, edge: Float): Int {
    if (edge <= 0f || bottom <= top || y.isNaN()) return 0
    val inTop = y < top + edge
    val inBottom = y > bottom - edge
    return when {
        inTop && (!inBottom || y < (top + bottom) / 2f) -> -1
        inBottom -> 1
        else -> 0
    }
}

/**
 * The edge band's depth for an [edgePx] token over the scroll area [top]..[bottom]: the token, but never
 * more than [HomeDragTokens.AutoScrollEdgeMaxFraction] of the area (a landscape phone keeps its middle half
 * free of edge scroll). Pure.
 */
fun dragEdgeBand(edgePx: Float, top: Float, bottom: Float): Float {
    val area = bottom - top
    if (area <= 0f) return edgePx
    return minOf(edgePx, area * HomeDragTokens.AutoScrollEdgeMaxFraction)
}

/**
 * The feed's scroll speed (px/s, + = toward the end) for a finger at [y]:
 * 0 outside the edge bands; inside the [edge]-deep band at [top] or
 * [bottom], from [min] where it starts to [max] at the edge and beyond,
 * easing in with the depth squared. Pure.
 */
fun dragAutoScrollSpeed(y: Float, top: Float, bottom: Float, edge: Float, min: Float, max: Float): Float {
    val dir = dragEdgeOf(y, top, bottom, edge)
    if (dir == 0) return 0f
    val depth = (if (dir < 0) (top + edge - y) / edge else (y - (bottom - edge)) / edge).coerceIn(0f, 1f)
    return dir * (min + (max - min) * depth * depth)
}

/**
 * The start-in-edge guard: a block lifted with the finger already in an
 * edge band ([startEdge] ±1, at [startY]) doesn't scroll until the finger
 * has pushed [pushPx] further toward that edge or rested [dwellMs] there
 * ([heldMs]). True while the guard still holds. Pure.
 */
fun dragEdgeGuardHolds(startEdge: Int, startY: Float, y: Float, heldMs: Float, pushPx: Float, dwellMs: Float): Boolean =
    startEdge != 0 && heldMs < dwellMs && startEdge * (y - startY) < pushPx

// ── Interruptions ─────────────────────────────────────────────────────────

/**
 * An exit that arrives mid-drag keeps the drop (writes the live arrangement)
 * only for Done and the app's own exits; Back (and a blank tap) put the order
 * back as it was before the lift. Pure.
 */
fun dragExitKeepsDrop(reason: HomeEditExitReason): Boolean = when (reason) {
    HomeEditExitReason.Done, HomeEditExitReason.Programmatic -> true
    HomeEditExitReason.Back, HomeEditExitReason.Blank -> false
}

/**
 * The laid-out rows ([visibleKeys]: each visible row item's first section,
 * top to bottom) already follow [rows]: every known one in order, the dragged
 * block's ([draggedKey]) among them. Unknown keys (a row fading out) are
 * ignored. A drag decides only on a layout that has caught up with its last
 * change. Pure.
 */
fun dragLayoutFollows(visibleKeys: List<HomeSection>, rows: List<HomeFeedRow>, draggedKey: HomeSection): Boolean {
    val firsts = rows.map { it.first }
    val indices = visibleKeys.map(firsts::indexOf).filter { it >= 0 }
    return draggedKey in visibleKeys && indices.zipWithNext().all { (a, b) -> a < b }
}
