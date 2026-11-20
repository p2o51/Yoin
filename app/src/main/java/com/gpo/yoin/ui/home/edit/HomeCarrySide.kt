package com.gpo.yoin.ui.home.edit

import com.gpo.yoin.ui.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.homeFeedRows
import kotlin.math.floor

// Large-screen Home, stage 1 (owner 2026-11-16): a carried block can be put
// down BESIDE another one, as in Notion. In two columns the strips keep the
// feed's rows (a pair is two half strips in one slot). The finger's x picks
// the drop: in the column's outer quarters it pairs the carried block with
// the row under it, which must be a full row (or the block's own pair, where
// it only changes sides); in the middle half it goes between rows, a row of
// its own, as before. Lifting one half out of a pair leaves its partner full.
// Everything here is pure; HomeCarryEngine drives it.

/** Where a strip sits across the column. */
internal enum class StripLane { Full, Left, Right }

/** Numbers of the side drop. */
internal object HomeCarrySideTokens {
    /** The column's outer fraction, each side, that pairs instead of inserting. */
    const val EdgeZone = .25f

    /** Once beside a block, the carried strip holds there until the finger crosses the column's middle. */
    const val HoldTo = .5f

    /** Past this fraction of a pitch the carried strip leans toward the row it overlaps. */
    const val LeanFraction = .15f
}

/** The carry's rows: [ids] in order, [pairs] (left → right) side by side. */
internal fun carryRows(ids: List<HomeSection>, pairs: Map<HomeSection, HomeSection>): List<HomeFeedRow> =
    homeFeedRows(ids, pairs, twoColumn = true)

/** [section]'s lane in [rows] (Full when absent). */
internal fun carryLaneOf(section: HomeSection, rows: List<HomeFeedRow>): StripLane {
    for (row in rows) {
        if (row is HomeFeedRow.Halves) {
            if (row.left == section) return StripLane.Left
            if (row.right == section) return StripLane.Right
        }
    }
    return StripLane.Full
}

/** The index of the row holding [section], or -1. */
internal fun carryRowIndexOf(section: HomeSection, rows: List<HomeFeedRow>): Int = rows.indexOfFirst { row ->
    when (row) {
        is HomeFeedRow.Full -> row.section == section
        is HomeFeedRow.Halves -> row.left == section || row.right == section
    }
}

/** [row]'s sections, left to right. */
internal fun HomeFeedRow.sections(): List<HomeSection> = when (this) {
    is HomeFeedRow.Full -> listOf(section)
    is HomeFeedRow.Halves -> listOf(left, right)
}

/** [pairs] without the pair [section] is in: lifted out, its partner is full. */
internal fun carryUnpair(pairs: Map<HomeSection, HomeSection>, section: HomeSection): Map<HomeSection, HomeSection> =
    pairs.filter { (left, right) -> left != section && right != section }

/**
 * The edge zone the finger at [fingerX] is in across the column
 * ([columnLeft], [columnWidth]): Left or Right in the outer quarters, null
 * in the middle. A zone already [current] holds over its whole half (a half
 * lifted on its own side stays beside its partner until it crosses over).
 */
internal fun carryEdgeZone(fingerX: Float, columnLeft: Float, columnWidth: Float, current: StripLane?): StripLane? {
    if (columnWidth <= 0f || fingerX.isNaN()) return null
    val rel = (fingerX - columnLeft) / columnWidth
    val zone = HomeCarrySideTokens.EdgeZone
    val hold = HomeCarrySideTokens.HoldTo
    return when {
        rel < zone || (current == StripLane.Left && rel < hold) -> StripLane.Left
        rel > 1f - zone || (current == StripLane.Right && rel > hold) -> StripLane.Right
        else -> null
    }
}

/**
 * The carried block may go beside [row]: a full row of another section, or
 * the pair it is already in (it only changes sides). Never beside a pair of
 * two other sections.
 */
internal fun carrySideAllowed(row: HomeFeedRow, carried: HomeSection): Boolean = when (row) {
    is HomeFeedRow.Full -> row.section != carried
    is HomeFeedRow.Halves -> row.left == carried || row.right == carried
}

/**
 * Between rows, the carried strip in row [k] of [rowCount] (its own row
 * counted) [display] px off its slot: the row it would pair with, as an
 * index into the rows without it. It leans to the row it overlaps; resting
 * in its slot, to the row it last passed ([lastSwapDir] +1 = moved down, so
 * the row above), else the row above. Null with no other row.
 */
internal fun carrySideCandidate(k: Int, rowCount: Int, display: Float, pitch: Float, lastSwapDir: Int): Int? {
    val hasAbove = k > 0
    val hasBelow = k < rowCount - 1
    if (!hasAbove && !hasBelow) return null
    val lean = pitch * HomeCarrySideTokens.LeanFraction
    val up = when {
        display < -lean -> true
        display > lean -> false
        lastSwapDir > 0 -> true
        lastSwapDir < 0 -> false
        else -> true
    }
    return when {
        up && hasAbove -> k - 1
        !up && hasBelow -> k
        hasAbove -> k - 1
        else -> k
    }
}

/** The row under a strip centre at [centerY] in a stack starting at [top]; clamped to [rowCount]. */
internal fun carryRowAt(centerY: Float, top: Float, pitch: Float, gap: Float, rowCount: Int): Int {
    if (rowCount <= 0 || pitch <= 0f) return 0
    return floor((centerY - top + gap / 2f) / pitch).toInt().coerceIn(0, rowCount - 1)
}

/**
 * Leaving the block in row [k] it was beside (on [side]) for a row of its
 * own: above it, or below it when it leans down past
 * [HomeCarrySideTokens.LeanFraction] of a pitch or, resting, was its right
 * half. [rowCount] counts the rows without the carried block.
 */
internal fun carryLeaveGap(k: Int, display: Float, pitch: Float, side: StripLane, rowCount: Int): Int {
    val lean = pitch * HomeCarrySideTokens.LeanFraction
    val below = when {
        display > lean -> true
        display < -lean -> false
        else -> side == StripLane.Right
    }
    return (if (below) k + 1 else k).coerceIn(0, rowCount)
}

/** An arrangement of the carry: the order and the pairs (left → right). */
internal data class CarryArrangement(val ids: List<HomeSection>, val pairs: Map<HomeSection, HomeSection>) {
    val rows: List<HomeFeedRow> get() = carryRows(ids, pairs)
}

/**
 * [carried] put beside [target] on [side] (Left or Right): out of wherever
 * it was, next to the target in the order, the two a pair.
 */
internal fun carryPlaceBeside(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    carried: HomeSection,
    target: HomeSection,
    side: StripLane,
): CarryArrangement {
    require(side != StripLane.Full && carried != target)
    val rest = ids - carried
    val unpaired = carryUnpair(carryUnpair(pairs, carried), target)
    val at = rest.indexOf(target)
    val order = rest.toMutableList().apply { add(if (side == StripLane.Left) at else at + 1, carried) }
    val pair = if (side == StripLane.Left) carried to target else target to carried
    return CarryArrangement(order, unpaired + pair)
}

/** [carried] as a full row of its own at [gap] among the rows without it (0 = first, rows' size = last). */
internal fun carryInsertRow(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    carried: HomeSection,
    gap: Int,
): CarryArrangement {
    val rest = ids - carried
    val unpaired = carryUnpair(pairs, carried)
    val rows = carryRows(rest, unpaired)
    val at = rows.getOrNull(gap)?.let { rest.indexOf(it.first) } ?: rest.size
    val order = rest.toMutableList().apply { add(at, carried) }
    return CarryArrangement(order, unpaired)
}

/**
 * Between rows, the carried strip's own row swapped one row [dir] (±1): it
 * jumps the whole neighbouring row, a pair included.
 */
internal fun carrySwapRow(
    ids: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    carried: HomeSection,
    dir: Int,
): List<HomeSection> {
    val rows = carryRows(ids, pairs)
    val k = carryRowIndexOf(carried, rows)
    val neighbour = rows.getOrNull(k + dir) ?: return ids
    val rest = ids - carried
    val at = if (dir > 0) rest.indexOf(neighbour.sections().last()) + 1 else rest.indexOf(neighbour.first)
    return rest.toMutableList().apply { add(at, carried) }
}
