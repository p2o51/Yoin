package com.gpo.yoin.ui.home

import androidx.compose.runtime.Immutable
import kotlin.random.Random

// Jump Back In, templated (owner 2026-10-04: 「错落感不够（要考虑到横向和纵向）」;
// a 130–160dp cover reads too big on a foldable / landscape tablet; and then
// 「2×2 的感觉……好奇怪太大了」 — no cover may be bigger than its neighbours).
// On panes of 3+ feed units the grid is K lanes of phone-sized cover columns
// (YoinWindowInfo.feedCoverColumns), every cover the same size:
//
//   Cover        1×1  a cover, as on the phone
//   WideSignal   2×1  a rating / note card: cover beside its copy, as on the
//                     phone (its two columns form one lane). The standing 1×2
//                     (cover over its copy) is gone — owner 2026-11-14: a
//                     note under the album's title didn't read as one block.
//
// Each lane starts at its own height — a fraction of a row lower than its
// neighbour — so no row line runs across the grid (vertical stagger), and a
// lying card breaks a column line (horizontal stagger). A lying card's right
// neighbour drops half a row, so its copy ends the card instead of reading
// on into the next lane. A seed from the data picks the offsets and where
// the cards go, so the same shelf always lands the same way. Nothing grows:
// a wider pane gets more lanes.

/** One piece kind and the cells it spans. */
internal enum class JbiPieceKind(val columnSpan: Int, val rowSpan: Int) {
    Cover(1, 1),
    WideSignal(2, 1),
    ;

    val isSignal: Boolean get() = this != Cover
}

/** A piece at its top-left cell; [row] counts from its lane's own top. */
@Immutable
internal data class JbiPiece(val kind: JbiPieceKind, val column: Int, val row: Int) {
    val endColumn: Int get() = column + kind.columnSpan
    val endRow: Int get() = row + kind.rowSpan
}

/**
 * [columns] lanes of [rows] cells each, every cell tiled exactly once.
 * [offsets] (one per column, in row pitches) is how far each column starts
 * below the grid's top; a lying card's two columns share theirs. [pieces] are
 * in reading order: by where they sit on screen (row + offset), then column.
 */
@Immutable
internal data class JbiTemplate(
    val columns: Int,
    val rows: Int,
    val offsets: List<Float>,
    val pieces: List<JbiPiece>,
) {
    val covers: Int get() = pieces.count { it.kind == JbiPieceKind.Cover }

    /** Where [piece] sits, in row pitches from the grid's top. */
    fun topOf(piece: JbiPiece): Float = piece.row + offsets[piece.column]
}

/** A card seated on its piece. */
@Immutable
internal data class JbiCell(val card: HomeWidgetCard, val piece: JbiPiece)

/** A [template] with its [cells] (same order as the template's pieces). */
@Immutable
internal data class JbiLayout(val template: JbiTemplate, val cells: List<JbiCell>)

/**
 * Seats [cards] (signal cards = `expanded`, at most two; the rest are covers
 * in ranking order) on [columns] lanes of up to [rows] cells, staggered and
 * picked by [seed]. When the shelf is too short for [rows], one
 * row fewer, down to one; null = too few covers even for that (the caller
 * falls back to the plain column grid).
 */
internal fun jbiLayout(
    cards: List<HomeWidgetCard>,
    columns: Int,
    rows: Int,
    seed: Int,
): JbiLayout? {
    // Re-entry (the section scrolling back in, a rotation back and forth, a
    // split handle crossing a column breakpoint) reuses the last results.
    val key = JbiLayoutKey(cards, columns, rows, seed)
    synchronized(jbiLayoutCache) {
        jbiLayoutCache[key]?.let { return it.layout }
    }
    val layout = computeJbiLayout(cards, columns, rows, seed)
    synchronized(jbiLayoutCache) {
        jbiLayoutCache[key] = JbiCachedLayout(layout)
    }
    return layout
}

private data class JbiLayoutKey(
    val cards: List<HomeWidgetCard>,
    val columns: Int,
    val rows: Int,
    val seed: Int,
)

private class JbiCachedLayout(val layout: JbiLayout?)

/** A few recent shelves × widths (portrait / landscape / a column): access-ordered, oldest evicted. */
private val jbiLayoutCache = object : LinkedHashMap<JbiLayoutKey, JbiCachedLayout>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<JbiLayoutKey, JbiCachedLayout>?): Boolean =
        size > JBI_LAYOUT_CACHE_SIZE
}

private fun computeJbiLayout(
    cards: List<HomeWidgetCard>,
    columns: Int,
    rows: Int,
    seed: Int,
): JbiLayout? {
    if (columns < 2 || rows < 1) return null
    val compacts = cards.filterNot { it.expanded }
    val signals = cards.filter { it.expanded }.take(jbiSparseSignalLimit(compacts.size, columns))
    for (r in rows downTo 1) {
        val template = jbiTemplate(columns, r, signals.size, seed)
        if (template.covers > compacts.size) continue
        return seat(template, signals, compacts)
    }
    return null
}

private fun seat(
    template: JbiTemplate,
    signals: List<HomeWidgetCard>,
    compacts: List<HomeWidgetCard>,
): JbiLayout {
    val covers = compacts.iterator()
    val signalCards = signals.iterator()
    val cells = template.pieces.map { piece ->
        JbiCell(if (piece.kind.isSignal) signalCards.next() else covers.next(), piece)
    }
    return JbiLayout(template, cells)
}

/**
 * The template for [columns] lanes of [rows] cells with [signals] signal
 * cards. Seeded, so the same arguments always return the same template.
 * Every card lies (2×1, its cover beside its copy): owner 2026-11-14 ruled the
 * standing 1×2 out — a note under its album's title doesn't read as one block
 * with the picture. The cards never overlap, stand a lane apart when there
 * is room, and when the width can't hold them side by
 * side (three lanes) they stack in the right-hand lane with a row between.
 * Lane 0 always starts at the top, under the section title.
 */
internal fun jbiTemplate(
    columns: Int,
    rows: Int,
    signals: Int,
    seed: Int,
): JbiTemplate {
    val random = Random(seed * 1_000_003 + columns * 101 + rows * 13)
    val wanted = signals.coerceIn(0, JBI_MAX_SIGNALS)

    // Where the cards go. A lying card's copy is on its right (owner:
    // 「默认文本在视觉右边……右边是一个 1×1 的封面，这时候右边就应该往下半格，
    // 以保证与左边区分开」): its right neighbour starts half a row lower, so the
    // copy's top lines up with the card's own cover and nothing else. It sits
    // on the top row, or the neighbour's cover above would put its title
    // beside the copy; a card in the right-hand lane has no neighbour there
    // and takes any row. Of every placement, the ones keeping the cards a lane
    // apart win, then those with a card in the right-hand lane (the lane a
    // longer preset can seat another card in, jbiPresetLayout); the seed picks
    // among them.
    val spanning: List<JbiPiece> = when {
        wanted == 0 || columns < JBI_SIGNAL_SPAN -> emptyList()
        wanted * JBI_SIGNAL_SPAN <= columns -> bestPlacement(columns, wanted, random).map { column ->
            val row = if (column + JBI_SIGNAL_SPAN < columns) 0 else rightLaneRow(rows, random)
            JbiPiece(JbiPieceKind.WideSignal, column, row)
        }
        // Too narrow for two side by side: both in the right-hand lane, a row of covers between them.
        rows >= 3 -> {
            val column = columns - JBI_SIGNAL_SPAN
            val first = random.nextInt(rows - 2)
            val second = first + 2 + random.nextInt(rows - first - 2)
            listOf(JbiPiece(JbiPieceKind.WideSignal, column, first), JbiPiece(JbiPieceKind.WideSignal, column, second))
        }
        // Too short to stack: only the first card goes in.
        else -> bestPlacement(columns, 1, random).map { column ->
            val row = if (column + JBI_SIGNAL_SPAN < columns) 0 else rightLaneRow(rows, random)
            JbiPiece(JbiPieceKind.WideSignal, column, row)
        }
    }
    val lanes = lanesOf(columns, spanning.map { it.column }.toSet())
    // A lying card's lane at 0 (unless it is itself a right neighbour), the
    // lane on its right half a row below it.
    val required = arrayOfNulls<Float>(lanes.size)
    spanning.distinctBy { it.column }.sortedBy { it.column }.forEach { card ->
        val laneIndex = lanes.indexOfFirst { card.column in it }
        val rightLane = laneIndex + 1
        if (rightLane !in lanes.indices) return@forEach
        val top = required[laneIndex] ?: 0f
        required[laneIndex] = top
        required[rightLane] = top + JBI_HALF_ROW
    }
    val laneOffsets = laneOffsets(required, random)
    val offsets = FloatArray(columns)
    lanes.forEachIndexed { i, lane -> lane.forEach { offsets[it] = laneOffsets[i] } }

    val taken = Array(rows) { BooleanArray(columns) }
    spanning.forEach { piece ->
        for (r in piece.row until piece.endRow) for (c in piece.column until piece.endColumn) taken[r][c] = true
    }
    val covers = buildList {
        for (r in 0 until rows) for (c in 0 until columns) {
            if (!taken[r][c]) add(JbiPiece(JbiPieceKind.Cover, c, r))
        }
    }
    val pieces = (spanning + covers).sortedWith(
        compareBy<JbiPiece>({ it.row + offsets[it.column] }, { it.column }),
    )
    return JbiTemplate(columns, rows, offsets.toList(), pieces)
}

/**
 * A right-hand card's row: any but the bottom one (when there are two or
 * more), so a longer preset can lay another card a row of covers below it.
 */
private fun rightLaneRow(rows: Int, random: Random): Int = random.nextInt(maxOf(1, rows - 1))

/** Lanes left to right: a lying card's two columns (from [pairStarts]) are one. */
private fun lanesOf(columns: Int, pairStarts: Set<Int>): List<IntRange> = buildList {
    var c = 0
    while (c < columns) {
        if (c in pairStarts && c + 1 < columns) {
            add(c until c + 2)
            c += 2
        } else {
            add(c until c + 1)
            c += 1
        }
    }
}

/**
 * The first columns of [count] lying cards side by side: of every placement
 * where they don't overlap, those keeping them a lane apart (two copies side
 * by side read as one block), then those with a card in the right-hand lane,
 * the seed picking among them.
 */
private fun bestPlacement(columns: Int, count: Int, random: Random): List<Int> {
    if (count <= 0) return emptyList()
    val spots = 0..(columns - JBI_SIGNAL_SPAN)
    val candidates = mutableListOf<List<Int>>()
    if (count == 1) {
        spots.forEach { candidates += listOf(it) }
    } else {
        for (a in spots) for (b in spots) {
            if (a < b + JBI_SIGNAL_SPAN && b < a + JBI_SIGNAL_SPAN) continue
            candidates += listOf(a, b)
        }
    }
    val scored = candidates.map { placement ->
        val apart = placement.size < 2 ||
            kotlin.math.abs(placement[0] - placement[1]) > JBI_SIGNAL_SPAN
        val rightLane = placement.any { it + JBI_SIGNAL_SPAN == columns }
        placement to (if (apart) 2 else 0) + (if (rightLane) 1 else 0)
    }
    val best = scored.maxOf { it.second }
    val top = scored.filter { it.second == best }.map { it.first }
    return top[random.nextInt(top.size)]
}

/**
 * One offset per lane, in row pitches; a [required] one always wins (the
 * lying cards' lanes and their right neighbours). Otherwise the first is at 0
 * and each next one drifts to one of {0, ¼, ½}, never its left neighbour's
 * nor a required right neighbour's.
 */
private fun laneOffsets(required: Array<Float?>, random: Random): List<Float> = buildList {
    required.indices.forEach { i ->
        val fixed = required[i]
        val offset = when {
            fixed != null -> fixed
            i == 0 -> 0f
            else -> {
                val next = required.getOrNull(i + 1)
                val choices = JBI_DRIFT_STEPS.filter { it != last() && it != next }
                    .ifEmpty { JBI_DRIFT_STEPS.filter { it != last() } }
                choices[random.nextInt(choices.size)]
            }
        }
        add(offset)
    }
}

/**
 * Signal cards a shelf of [covers] covers takes on [columns] lanes (owner
 * 2026-11-12: a small library's two rating cards took the whole row and left
 * no cover): while the covers can't fill one row, one card; else the usual two.
 * The iPad seats Jump Back In by the same rule.
 */
internal fun jbiSparseSignalLimit(covers: Int, columns: Int): Int =
    if (covers < columns) 1 else JBI_MAX_SIGNALS

/** The seed for a shelf: its leading cover, so a signal card arriving doesn't re-roll the layout. */
internal fun jbiLayoutSeed(cards: List<HomeWidgetCard>): Int =
    activityLayoutSeed(cards.firstOrNull { !it.expanded }?.stableId)

/**
 * The packed shelf of [cells] cells (the phone's 3 × 4 = 12 by default): up
 * to [maxSignals] wide cards first (two at the phone's twelve; a longer shelf
 * takes more, see jbiSignalLimit), then covers for the cells left.
 */
internal fun trimToPhoneShelf(
    cards: List<HomeWidgetCard>,
    cells: Int = JBI_PHONE_CELLS,
    maxSignals: Int = JBI_MAX_SIGNALS,
): List<HomeWidgetCard> {
    val wide = cards.filter { it.expanded }.take(maxSignals.coerceAtLeast(0))
    val compacts = cards.filterNot { it.expanded }
    return wide + compacts.take((cells - wide.size * JBI_SIGNAL_CELLS).coerceAtLeast(0))
}

/**
 * [layout] at [rows] rows (D1 row presets), keeping every card it can where it
 * is, so neighbouring presets interpolate card by card.
 *
 * More rows: whole rows are appended at the bottom of every lane, seated
 * with the next covers in [cards] in reading order (a row the shelf can't
 * fill is left off); the template's own rows and offsets stay. The rows
 * added bring signal cards of their own when [cards] has more than the
 * template seated ([jbiGrowSignals]): each lies in the right-hand lane when
 * that lane already holds a lying card (no neighbour on its copy's side, so
 * any row reads right), a row of covers away from the card above it. Every
 * card the smaller layout seated keeps its cell.
 *
 * Fewer rows: the bottom rows are cut. A signal card past the new bottom
 * moves up in its own lane; past [jbiSignalLimit], or with no free row a
 * cover apart from the card above, the lower-ranked one leaves. Covers above
 * the cut keep their cards; a cell a moved or departed signal leaves to a
 * cover takes the first of the displaced covers in the old reading order, so
 * the result seats only cards [layout] seated. Offsets stay.
 */
internal fun jbiPresetLayout(layout: JbiLayout, cards: List<HomeWidgetCard>, rows: Int): JbiLayout {
    val template = layout.template
    return when {
        rows == template.rows -> layout
        rows > template.rows -> growJbiLayout(layout, cards, rows)
        else -> shrinkJbiLayout(layout, rows.coerceAtLeast(1))
    }
}

private fun growJbiLayout(layout: JbiLayout, cards: List<HomeWidgetCard>, rows: Int): JbiLayout {
    val template = layout.template
    val columns = template.columns
    val oldRows = template.rows
    val seated = layout.cells.mapTo(HashSet()) { it.card.stableId }
    val spare = cards.filter { !it.expanded && it.stableId !in seated }
    val spareSignals = cards.filter { it.expanded && it.stableId !in seated }
    // As many whole rows as the shelf fills; each new card takes two cells.
    var addedRows = rows - oldRows
    var added = emptyList<JbiPiece>()
    while (addedRows > 0) {
        val wanted = minOf(spareSignals.size, jbiGrowSignals(addedRows, columns))
        added = growSignalPieces(layout, addedRows, wanted)
        if (spare.size >= addedRows * columns - JBI_SIGNAL_CELLS * added.size) break
        addedRows--
    }
    if (addedRows <= 0) return layout
    val offsets = template.offsets
    val reading = compareBy<JbiPiece>({ it.row + offsets[it.column] }, { it.column })
    val signalCells = added.sortedWith(reading).mapIndexed { i, piece -> JbiCell(spareSignals[i], piece) }
    val newCovers = buildList {
        for (r in oldRows until oldRows + addedRows) for (c in 0 until columns) {
            if (added.any { it.row == r && c in it.column until it.endColumn }) continue
            add(JbiPiece(JbiPieceKind.Cover, c, r))
        }
    }.sortedWith(reading)
    val next = spare.iterator()
    val cells = (layout.cells + signalCells + newCovers.map { JbiCell(next.next(), it) })
        .sortedWith(compareBy(reading) { it.piece })
    return JbiLayout(
        template = template.copy(rows = oldRows + addedRows, pieces = cells.map { it.piece }),
        cells = cells,
    )
}

/**
 * Signal cards [addedRows] rows of [columns] lanes bring along: one for every
 * [JbiCellsPerExtraSignal] cells they add, rounded to the nearest, at least
 * one (the same rate as the packed shelves past twelve cells).
 */
internal fun jbiGrowSignals(addedRows: Int, columns: Int): Int =
    maxOf(1, (addedRows * columns + JbiCellsPerExtraSignal / 2) / JbiCellsPerExtraSignal)

/**
 * Up to [count] new lying cards in the [addedRows] rows under [layout]: only
 * in the right-hand lane, and only when a lying card already makes it one
 * lane (two columns of different lanes never merge — their offsets differ).
 * Each takes the first new row with a row of covers between it and the card
 * above it in that lane. Fewer when the rows run out.
 */
private fun growSignalPieces(layout: JbiLayout, addedRows: Int, count: Int): List<JbiPiece> {
    if (count <= 0) return emptyList()
    val template = layout.template
    val column = template.columns - JBI_SIGNAL_SPAN
    val laneRows = layout.cells
        .filter { it.piece.kind == JbiPieceKind.WideSignal && it.piece.column == column }
        .mapTo(HashSet()) { it.piece.row }
    if (laneRows.isEmpty()) return emptyList()
    val picked = mutableListOf<JbiPiece>()
    for (r in template.rows until template.rows + addedRows) {
        if (picked.size == count) break
        if ((r - 1) in laneRows) continue
        laneRows += r
        picked += JbiPiece(JbiPieceKind.WideSignal, column, r)
    }
    return picked
}

private fun shrinkJbiLayout(layout: JbiLayout, rows: Int): JbiLayout {
    val template = layout.template
    val columns = template.columns
    val taken = Array(rows) { BooleanArray(columns) }
    fun fits(piece: JbiPiece): Boolean = piece.column >= 0 && piece.endColumn <= columns &&
        piece.row >= 0 && piece.endRow <= rows &&
        (piece.row until piece.endRow).none { r -> (piece.column until piece.endColumn).any { c -> taken[r][c] } }
    fun take(piece: JbiPiece) {
        for (r in piece.row until piece.endRow) for (c in piece.column until piece.endColumn) taken[r][c] = true
    }

    // Signals by card rank (the incoming order), at most the limit for this size.
    val signals = layout.cells.filter { it.piece.kind.isSignal }
    val limit = jbiSignalLimit(rows, rows * columns)
    val kept = mutableListOf<JbiCell>()
    for (cell in signals.take(limit)) {
        val piece = cell.piece
        val placed = if (piece.endRow <= rows) piece else piece.copy(row = rows - 1)
        // Two cards stacked in one lane keep a row of covers between them.
        val touching = kept.any { other ->
            other.piece.column == placed.column && kotlin.math.abs(other.piece.row - placed.row) == 1
        }
        if (!fits(placed) || touching) continue
        take(placed)
        kept += JbiCell(cell.card, placed)
    }

    val oldCovers = layout.cells.filter { it.piece.kind == JbiPieceKind.Cover }
    val coverAt = oldCovers.associateBy { it.piece.column to it.piece.row }
    val keptCovers = mutableListOf<JbiCell>()
    val open = mutableListOf<JbiPiece>()
    for (r in 0 until rows) for (c in 0 until columns) {
        if (taken[r][c]) continue
        val old = coverAt[c to r]
        if (old != null) keptCovers += old else open += JbiPiece(JbiPieceKind.Cover, c, r)
    }
    val keptIds = keptCovers.mapTo(HashSet()) { it.card.stableId }
    // Displaced covers, best first (the old reading order), fill the open cells in the new one.
    val displaced = oldCovers.filter { it.card.stableId !in keptIds }.iterator()
    val offsets = template.offsets
    val reading = compareBy<JbiPiece>({ it.row + offsets[it.column] }, { it.column })
    val refilled = open.sortedWith(reading).mapNotNull { piece ->
        if (displaced.hasNext()) JbiCell(displaced.next().card, piece) else null
    }
    val cells = (kept + keptCovers + refilled).sortedWith(compareBy(reading) { it.piece })
    return JbiLayout(
        template = JbiTemplate(columns, rows, offsets, cells.map { it.piece }),
        cells = cells,
    )
}

private const val JBI_MAX_SIGNALS = 2
private const val JBI_SIGNAL_CELLS = 2
private const val JBI_SIGNAL_SPAN = 2
private const val JBI_PHONE_CELLS = JbiPhoneShelfCells
private const val JBI_HALF_ROW = 0.5f
private val JBI_DRIFT_STEPS = listOf(0f, 0.25f, 0.5f)
private const val JBI_LAYOUT_CACHE_SIZE = 8
