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
//   TallSignal   1×2  a rating / note card standing: cover over its copy
//   WideSignal   2×1  the phone's 1×2 lying down: cover beside its copy (its
//                     two columns form one lane)
//
// Each lane starts at its own height — a fraction of a row lower than its
// neighbour — so no row line runs across the grid (vertical stagger), and a
// lying card breaks a column line (horizontal stagger). A standing card's
// neighbours keep its height, so its copy sits beside their next covers; a
// lying card's right neighbour drops half a row, so its copy ends the card
// instead of reading on into the next lane. A seed from the data picks the offsets and where
// the two cards go, so the same shelf always lands the same way. Nothing
// grows: a wider pane gets more lanes.

/** One piece kind and the cells it spans. */
internal enum class JbiPieceKind(val columnSpan: Int, val rowSpan: Int) {
    Cover(1, 1),
    TallSignal(1, 2),
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
    val signals = cards.filter { it.expanded }.take(JBI_MAX_SIGNALS)
    val compacts = cards.filterNot { it.expanded }
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
 * cards. Seeded, so the same arguments always return
 * the same template. Each card stands (1×2) or lies (2×1) by the seed — a
 * standing card needs two rows, so on one row both lie down; the two never
 * share a lane, and stand apart when there is room. Lane 0 always starts at
 * the top, under the section title.
 */
internal fun jbiTemplate(
    columns: Int,
    rows: Int,
    signals: Int,
    seed: Int,
): JbiTemplate {
    val random = Random(seed * 1_000_003 + columns * 101 + rows * 13)
    val wanted = List(signals.coerceIn(0, JBI_MAX_SIGNALS)) {
        if (rows >= 2 && random.nextBoolean()) JbiPieceKind.TallSignal else JbiPieceKind.WideSignal
    }

    // Where the cards go. A standing card's copy, under its own title, sits
    // beside its neighbours' next covers, so those lanes start level with it
    // and move as one run. A lying card's copy is on its right (owner:
    // 「默认文本在视觉右边……右边是一个 1×1 的封面，这时候右边就应该往下半格，
    // 以保证与左边区分开」): its right neighbour starts half a row lower, so the
    // copy's top lines up with the card's own cover and nothing else. It sits
    // on the top row, or the neighbour's cover above would put its title
    // beside the copy. Of every placement the cards could take, the ones that
    // leave the most runs to stagger win (the cards drift to the sides), and
    // the seed picks among them. A lying card with no pair of columns free
    // stands up.
    val standing = wanted.map { kind ->
        if (kind == JbiPieceKind.WideSignal && columns < 2 * wanted.count { it == JbiPieceKind.WideSignal } &&
            rows >= 2
        ) {
            JbiPieceKind.TallSignal
        } else {
            kind
        }
    }
    // On one row with too few columns for both lying cards, only the first goes in.
    val kinds = if (standing.sumOf { it.columnSpan } > columns) standing.take(1) else standing
    val placement = bestPlacement(columns, kinds, random)
    val pairStarts = placement.filterIndexed { i, _ -> kinds[i] == JbiPieceKind.WideSignal }
    val lanes = lanesOf(columns, pairStarts)
    val spanning = placement.mapIndexed { i, column ->
        val kind = kinds[i]
        val row = when {
            kind == JbiPieceKind.TallSignal -> random.nextInt(rows - 1)
            column + kind.columnSpan < columns -> 0
            else -> random.nextInt(rows)
        }
        JbiPiece(kind, column, row)
    }
    val group = runsOf(lanes, spanning)
    // Runs in left-to-right order (they are contiguous).
    val runs = lanes.indices.groupBy { group[it] }.values.sortedBy { it.first() }
    val runOfLane = IntArray(lanes.size).also { runOf ->
        runs.forEachIndexed { r, run -> run.forEach { runOf[it] = r } }
    }
    // A lying card's run at 0 (unless it is itself a right neighbour), the run
    // on its right half a row below it.
    val required = arrayOfNulls<Float>(runs.size)
    spanning.filter { it.kind == JbiPieceKind.WideSignal }.sortedBy { it.column }.forEach { card ->
        val laneIndex = lanes.indexOfFirst { card.column in it }
        val cardRun = runOfLane[laneIndex]
        val rightRun = runOfLane.getOrNull(laneIndex + 1) ?: return@forEach
        if (rightRun == cardRun) return@forEach
        val top = required[cardRun] ?: 0f
        required[cardRun] = top
        required[rightRun] = top + JBI_HALF_ROW
    }
    val runOffsets = laneOffsets(required, random)
    val offsets = FloatArray(columns)
    runs.forEachIndexed { r, run ->
        run.forEach { laneIndex -> lanes[laneIndex].forEach { offsets[it] = runOffsets[r] } }
    }

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

/** Lanes left to right: a lying card's two columns (from [pairStarts]) are one. */
private fun lanesOf(columns: Int, pairStarts: List<Int>): List<IntRange> = buildList {
    var c = 0
    while (c < columns) {
        if (c in pairStarts) {
            add(c until c + 2)
            c += 2
        } else {
            add(c until c + 1)
            c += 1
        }
    }
}

/**
 * Run id per lane: a standing card's lane joins both neighbours (the lanes
 * beside its copy); every other lane is a run of its own.
 */
private fun runsOf(lanes: List<IntRange>, cards: List<JbiPiece>): IntArray {
    val group = IntArray(lanes.size) { it }
    fun join(a: Int, b: Int) {
        if (a !in lanes.indices || b !in lanes.indices) return
        val from = group[b]
        val to = group[a]
        for (i in group.indices) if (group[i] == from) group[i] = to
    }
    cards.forEach { card ->
        val laneIndex = lanes.indexOfFirst { card.column in it }
        when (card.kind) {
            JbiPieceKind.TallSignal -> {
                join(laneIndex, laneIndex - 1)
                join(laneIndex, laneIndex + 1)
            }
            JbiPieceKind.WideSignal, JbiPieceKind.Cover -> Unit
        }
    }
    return group
}

/**
 * The cards' columns ([kinds] in order): of every placement where they don't
 * overlap, those keeping the two a lane apart and then leaving the most runs
 * of lanes to stagger, the seed picking among them.
 */
private fun bestPlacement(columns: Int, kinds: List<JbiPieceKind>, random: Random): List<Int> {
    if (kinds.isEmpty()) return emptyList()
    fun spots(kind: JbiPieceKind) = 0..(columns - kind.columnSpan)
    val candidates = mutableListOf<List<Int>>()
    if (kinds.size == 1) {
        spots(kinds[0]).forEach { candidates += listOf(it) }
    } else {
        for (a in spots(kinds[0])) for (b in spots(kinds[1])) {
            val aEnd = a + kinds[0].columnSpan
            val bEnd = b + kinds[1].columnSpan
            if (a < bEnd && b < aEnd) continue
            candidates += listOf(a, b)
        }
    }
    // Score: the two cards a lane apart first (two copies side by side read
    // as one block), then the most runs left to stagger.
    val scored = candidates.map { placement ->
        val pairs = placement.filterIndexed { i, _ -> kinds[i] == JbiPieceKind.WideSignal }
        val lanes = lanesOf(columns, pairs)
        val cards = placement.mapIndexed { i, c -> JbiPiece(kinds[i], c, 0) }
        val runs = runsOf(lanes, cards).toSet().size
        val apart = cards.size < 2 ||
            kotlin.math.abs(
                lanes.indexOfFirst { cards[0].column in it } - lanes.indexOfFirst { cards[1].column in it },
            ) >= 2
        placement to (if (apart) 1_000 else 0) + runs
    }
    val best = scored.maxOf { it.second }
    val top = scored.filter { it.second == best }.map { it.first }
    return top[random.nextInt(top.size)]
}

/**
 * One offset per run of lanes, in row pitches; a [required] one always wins
 * (the lying cards' runs and their right neighbours). Otherwise the first is
 * at 0 and each next one drifts to one of {0, ¼, ½}, never its left
 * neighbour's nor a required right neighbour's.
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

/** The seed for a shelf: its leading cover, so a signal card arriving doesn't re-roll the layout. */
internal fun jbiLayoutSeed(cards: List<HomeWidgetCard>): Int =
    activityLayoutSeed(cards.firstOrNull { !it.expanded }?.stableId)

/** The phone's 3 × 4 shelf: wide cards first, then covers up to 12 cells. */
internal fun trimToPhoneShelf(cards: List<HomeWidgetCard>): List<HomeWidgetCard> {
    val wide = cards.filter { it.expanded }.take(JBI_MAX_SIGNALS)
    val compacts = cards.filterNot { it.expanded }
    return wide + compacts.take((JBI_PHONE_CELLS - wide.size * JBI_SIGNAL_CELLS).coerceAtLeast(0))
}

private const val JBI_MAX_SIGNALS = 2
private const val JBI_SIGNAL_CELLS = 2
private const val JBI_PHONE_CELLS = 12
private const val JBI_HALF_ROW = 0.5f
private val JBI_DRIFT_STEPS = listOf(0f, 0.25f, 0.5f)
private const val JBI_LAYOUT_CACHE_SIZE = 8
