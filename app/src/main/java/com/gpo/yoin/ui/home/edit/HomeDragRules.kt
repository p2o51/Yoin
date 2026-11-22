package com.gpo.yoin.ui.home.edit

import androidx.compose.runtime.Immutable
import com.gpo.yoin.ui.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.homeFeedRows

// Home edit reordering (owner 2026-11-22): a block dragged in place, in the
// feed or in the pinched list. The other blocks make way as its slot
// changes, and a finger near the top or bottom scrolls. In two columns a
// block can also go BESIDE another one (stage 1, 2026-11-16): the finger in
// the column's outer quarters over a full row pairs the dragged block with
// it; in the middle half it goes between rows, a row of its own. Lifting one
// half out of a pair leaves its partner full once the finger crosses the
// column's middle. Never beside a pair of two other blocks; beside its own
// partner it only changes sides. Everything here is pure; HomeDragEngine
// drives it.

/** Where a section sits across a two-column row. */
internal enum class HomeDragLane { Full, Left, Right }

/** Numbers of the drop rules. */
internal object HomeDragRuleTokens {
    /** The column's outer fraction, each side, that pairs instead of inserting. */
    const val EdgeZone = .25f

    /** Once beside a block, the dragged block holds there until the finger crosses the column's middle. */
    const val HoldTo = .5f
}

/** An arrangement of the drag: the order and the pairs (left → right, each pair adjacent in [ids]). */
@Immutable
internal data class DragArrangement(val ids: List<HomeSection>, val pairs: Map<HomeSection, HomeSection>)

/** A laid-out row's vertical extent in Box px. */
@Immutable
internal data class DragRowBand(val row: HomeFeedRow, val top: Float, val bottom: Float) {
    val center: Float get() = (top + bottom) / 2f
}

/** The drag's rows: [ids] in order; in [twoColumn], [pairs] side by side. */
internal fun dragRows(ids: List<HomeSection>, pairs: Map<HomeSection, HomeSection>, twoColumn: Boolean): List<HomeFeedRow> =
    homeFeedRows(ids, pairs, twoColumn)

/** [section]'s lane in [rows] (Full when absent). */
internal fun dragLaneOf(section: HomeSection, rows: List<HomeFeedRow>): HomeDragLane {
    for (row in rows) {
        if (row is HomeFeedRow.Halves) {
            if (row.left == section) return HomeDragLane.Left
            if (row.right == section) return HomeDragLane.Right
        }
    }
    return HomeDragLane.Full
}

/** The index of the row holding [section], or -1. */
internal fun dragRowIndexOf(section: HomeSection, rows: List<HomeFeedRow>): Int =
    rows.indexOfFirst { section in it.sections() }

/** [row]'s sections, left to right. */
internal fun HomeFeedRow.sections(): List<HomeSection> = when (this) {
    is HomeFeedRow.Full -> listOf(section)
    is HomeFeedRow.Halves -> listOf(left, right)
}

/** [pairs] without the pair [section] is in: lifted out, its partner is full. */
internal fun dragUnpair(pairs: Map<HomeSection, HomeSection>, section: HomeSection): Map<HomeSection, HomeSection> =
    pairs.filter { (left, right) -> left != section && right != section }

/**
 * The edge zone the finger at [fingerX] is in across the column
 * ([columnLeft], [columnWidth]): Left or Right in the outer quarters, null
 * in the middle. A zone already [current] holds over its whole half (a half
 * lifted on its own side stays beside its partner until it crosses over).
 */
internal fun dragEdgeZone(fingerX: Float, columnLeft: Float, columnWidth: Float, current: HomeDragLane?): HomeDragLane? {
    if (columnWidth <= 0f || fingerX.isNaN()) return null
    val rel = (fingerX - columnLeft) / columnWidth
    val zone = HomeDragRuleTokens.EdgeZone
    val hold = HomeDragRuleTokens.HoldTo
    return when {
        rel < zone || (current == HomeDragLane.Left && rel < hold) -> HomeDragLane.Left
        rel > 1f - zone || (current == HomeDragLane.Right && rel > hold) -> HomeDragLane.Right
        else -> null
    }
}

/**
 * The dragged block may go beside [row]: a full row of another section, or
 * the pair it is already in (it only changes sides). Never beside a pair of
 * two other sections.
 */
internal fun dragSideAllowed(row: HomeFeedRow, dragged: HomeSection): Boolean = when (row) {
    is HomeFeedRow.Full -> row.section != dragged
    is HomeFeedRow.Halves -> row.left == dragged || row.right == dragged
}

/**
 * [dragged] put beside [target] on [side] (Left or Right): out of wherever
 * it was, next to the target in the order, the two a pair.
 */
internal fun dragPlaceBeside(
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
internal fun dragInsertRow(
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
internal fun dragSwapRow(
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
internal fun dragSwapDir(visualTop: Float, slot: DragRowBand, prev: DragRowBand?, next: DragRowBand?): Int {
    if (prev != null && visualTop < (slot.top + prev.top) / 2f) return -1
    if (next != null) {
        val topThere = next.bottom - (slot.bottom - slot.top)
        if (visualTop > (slot.top + topThere) / 2f) return 1
    }
    return 0
}

/** What the finger asks of a two-column drag ([dragSideStep]). */
internal sealed interface DragSideStep {
    /** Between rows: the half-travel rule ([dragSwapDir]) decides. */
    data object Vertical : DragSideStep

    /** Beside a block, staying there. */
    data object Stay : DragSideStep

    /** A new arrangement, the dragged block now on [side] (null: a row of its own). */
    data class Arrange(val arrangement: DragArrangement, val side: HomeDragLane?) : DragSideStep
}

/**
 * Two columns: where the finger at [fingerY] (in edge [zone], null in the
 * middle) puts the [dragged] block, now beside a block on [side] (null:
 * between rows). [bands] are the laid-out rows of the live arrangement,
 * top to bottom. Between rows, an edge zone over another section's full row
 * pairs with it; a pair of two others or the block's own row leave it to the
 * half-travel rule. Beside a block, the middle sends it to a row of its own
 * above or below its partner (by the finger against the pair's centre);
 * over another full row it pairs with that one; over a pair of two others
 * it becomes a row of its own beside that pair; over its own pair (or a gap)
 * it changes sides when the zone did. Pure.
 */
internal fun dragSideStep(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    dragged: HomeSection,
    side: HomeDragLane?,
    zone: HomeDragLane?,
    fingerY: Float,
    bands: List<DragRowBand>,
): DragSideStep {
    val under = bands.firstOrNull { fingerY >= it.top && fingerY <= it.bottom }
    if (side == null) {
        val row = under?.row ?: return DragSideStep.Vertical
        if (zone == null || dragged in row.sections() || row !is HomeFeedRow.Full) return DragSideStep.Vertical
        return DragSideStep.Arrange(dragPlaceBeside(ids, pairs, dragged, row.section, zone), zone)
    }
    val partner = pairs[dragged] ?: pairs.entries.firstOrNull { it.value == dragged }?.key
        ?: return DragSideStep.Vertical
    val own = bands.firstOrNull { dragged in it.row.sections() }
    if (zone == null) {
        val below = own != null && fingerY > own.center
        return DragSideStep.Arrange(dragInsertRow(ids, pairs, dragged, dragGapBy(ids, pairs, dragged, partner, below)), null)
    }
    if (under == null || under === own) {
        if (zone == side) return DragSideStep.Stay
        return DragSideStep.Arrange(dragPlaceBeside(ids, pairs, dragged, partner, zone), zone)
    }
    val row = under.row
    if (row is HomeFeedRow.Full && row.section != dragged) {
        return DragSideStep.Arrange(dragPlaceBeside(ids, pairs, dragged, row.section, zone), zone)
    }
    val below = fingerY > under.center
    return DragSideStep.Arrange(dragInsertRow(ids, pairs, dragged, dragGapBy(ids, pairs, dragged, row.first, below)), null)
}

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
 * The feed's scroll speed (px/s, + = toward the end) for a finger at [y]:
 * 0 outside the edge bands, rising in proportion to how deep it is inside
 * the [edge]-deep band at [top] or [bottom], to [max] at the edge itself and
 * beyond. A band narrower than two edges takes the nearer end. Pure.
 */
internal fun dragAutoScrollSpeed(y: Float, top: Float, bottom: Float, edge: Float, max: Float): Float {
    if (edge <= 0f || bottom <= top || y.isNaN()) return 0f
    val upDepth = (top + edge - y) / edge
    val downDepth = (y - (bottom - edge)) / edge
    return when {
        upDepth > 0f && (downDepth <= 0f || y < (top + bottom) / 2f) -> -max * upDepth.coerceAtMost(1f)
        downDepth > 0f -> max * downDepth.coerceAtMost(1f)
        else -> 0f
    }
}

/**
 * The laid-out rows ([visibleKeys]: each visible row item's first section,
 * top to bottom) already follow [rows]: every known one in order, the dragged
 * block's ([draggedKey]) among them. Unknown keys (a row fading out) are
 * ignored. A drag decides only on a layout that has caught up with its last
 * change. Pure.
 */
internal fun dragLayoutFollows(visibleKeys: List<HomeSection>, rows: List<HomeFeedRow>, draggedKey: HomeSection): Boolean {
    val firsts = rows.map { it.first }
    val indices = visibleKeys.map(firsts::indexOf).filter { it >= 0 }
    return draggedKey in visibleKeys && indices.zipWithNext().all { (a, b) -> a < b }
}
