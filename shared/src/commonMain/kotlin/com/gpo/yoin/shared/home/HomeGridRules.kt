package com.gpo.yoin.shared.home

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

// Two-column Home's grid (owner 2026-11-16, plan 「首页格高方案」; Android PR #250 / #292), moved unchanged
// from Android's HomeGridUnits.kt, HomeFeedRows.kt and HomeShelfPager.kt for parity phase 4. In two
// columns every section is a whole number of grid units tall, so the two halves of a row always line up:
// height = T + n·U + (n − 1)·G. Lengths are dp unless a name says px. The golden vectors are
// shared/src/commonTest/resources/golden/home-grid.json.

/** Numbers of the two-column grid, dp. */
object HomeGridTokens {
    /** One unit. 150 fits a 106dp cover and two lines; 120 is the denser option (open question 1). */
    const val Unit = 150f

    /** Between two units of one section. */
    const val Gap = 14f

    /** The title row every section has in two columns. */
    const val TitleRow = 40f

    /** A unit drag can pull this far past the smallest or largest count (a rubber band past it). */
    const val Overdrag = 24f

    /** What a cover card's two caption lines (and the gap above them) take out of a unit: cover = U − 44. */
    const val CaptionBand = 44f

    /** The narrowest gap between two cover cards in a row; a page's spare width widens it. */
    const val ColumnGap = 12f

    /** Between two of Recently Added's track rows; the rows themselves fill the rest of the box. */
    const val TrackRowGap = 6f

    /** Recently Added's narrowest track column on the grid. */
    const val TrackColumnMin = 280f

    /** The narrowest one-column Activities card on the grid. */
    const val ActivityColumnMin = 160f
}

// ── The two-column tier ──────────────────────────────────────────────────

/** Numbers of the two-column tier, dp. */
object HomeTwoColumnTokens {
    /** The narrowest feed container that lays out in two columns (WindowSizeClass Expanded). */
    const val MinWidth = 840f

    /** The shortest one (WindowSizeClass Medium height): a landscape handset never pairs. */
    const val MinHeight = 480f

    /** The narrowest content a two-column frame has (840 − 2 × 32). */
    const val MinContent = 776f
}

/**
 * The feed lays out in two columns: its container (the feed's own width and height, not the window's: a
 * side panel or a detail column that narrows it counts) is at least 840 × 480dp, and the device isn't in
 * a tabletop posture (a half-open foldable keeps one capped column). Android's `homeTwoColumn(info)`;
 * iOS's `HomeDensity.wide`; HarmonyOS's `FeedFrameClass.wide`. Pure.
 */
fun homeTwoColumn(containerWidthDp: Float, containerHeightDp: Float, tabletop: Boolean): Boolean =
    !tabletop && containerWidthDp >= HomeTwoColumnTokens.MinWidth && containerHeightDp >= HomeTwoColumnTokens.MinHeight

// ── Heights and columns ──────────────────────────────────────────────────

/** A cover shelf's cover side at [unit] dp: U − 44 (106 at 150). */
fun homeGridCoverDp(unit: Float = HomeGridTokens.Unit): Float = (unit - HomeGridTokens.CaptionBand).coerceAtLeast(1f)

/** Cover columns a [widthDp] page seats: ⌊(W + gap) / (cover + gap)⌋, at least 1. */
fun homeGridColumns(
    widthDp: Float,
    coverDp: Float = homeGridCoverDp(),
    gapDp: Float = HomeGridTokens.ColumnGap,
): Int = max(1, floor((widthDp + gapDp) / (coverDp + gapDp)).toInt())

/** A section's height in two columns at [units] units: T + n·U + (n − 1)·G, in dp. */
fun homeGridHeightDp(units: Int): Float {
    val n = units.coerceAtLeast(1)
    return HomeGridTokens.TitleRow + n * HomeGridTokens.Unit + (n - 1) * HomeGridTokens.Gap
}

/** The content height under the title row at [units] units, dp: n·U + (n − 1)·G. */
fun homeGridContentDp(units: Int): Float = homeGridHeightDp(units) - HomeGridTokens.TitleRow

/** Recently Added's track rows at [units] units: three a unit. */
fun homeGridTrackRows(units: Int): Int = 3 * units.coerceAtLeast(1)

/** Recently Added's track columns on the grid: ⌊W / 280⌋, at least 1 (a half 2, a full row 3–4). */
fun homeGridTrackColumns(widthDp: Float): Int = max(1, floor(widthDp / HomeGridTokens.TrackColumnMin).toInt())

/** One track row's height at [units] units, dp: the rows and their gaps fill the content box exactly. */
fun homeGridTrackRowDp(units: Int, gapDp: Float = HomeGridTokens.TrackRowGap): Float {
    val rows = homeGridTrackRows(units)
    return (homeGridContentDp(units) - (rows - 1) * gapDp) / rows
}

/** The count whose height ([homeGridHeightDp] × [density] px) is nearest [heightPx] within [range]. */
fun homeUnitsNearest(heightPx: Float, range: IntRange, density: Float): Int =
    range.minByOrNull { abs(homeGridHeightDp(it) * density - heightPx) } ?: range.first

// ── Packing cards into unit rows ─────────────────────────────────────────

/** A card placed on the grid: [index] into the caller's list, [row], first [column], [span] columns. */
data class HomeGridCell(val index: Int, val row: Int, val column: Int, val span: Int)

/**
 * Cards of [spans] columns each, row-major into [rows] rows of [columns]: a
 * card that doesn't fit what is left of a row starts the next; a span wider
 * than the row is one column. Cards past the last row are left out; no row
 * starts with a gap. Pure.
 */
fun homeGridPack(spans: List<Int>, columns: Int, rows: Int): List<HomeGridCell> {
    val cols = columns.coerceAtLeast(1)
    val out = ArrayList<HomeGridCell>()
    var row = 0
    var column = 0
    for ((index, raw) in spans.withIndex()) {
        val span = raw.coerceIn(1, cols)
        if (column + span > cols) {
            row++
            column = 0
        }
        if (row >= rows) break
        out += HomeGridCell(index, row, column, span)
        column += span
    }
    return out
}

/**
 * Activities on the grid: one row of cards a unit. The lead entry (the hero,
 * when there is one) spans two columns; after it the rows alternate a wide
 * card (two columns) and small ones (one), so a row reads as a bento, not a
 * strip of equal tiles. [columns] from [homeGridActivityColumns]. Pure.
 */
fun homeGridActivitySpans(count: Int, columns: Int): List<Int> {
    if (columns <= 1) return List(count) { 1 }
    val out = ArrayList<Int>(count)
    var column = 0
    var inRow = 0
    repeat(count) {
        val span = if (inRow % 2 == 0 && columns - column >= 2) 2 else 1
        out += span
        column += span
        inRow++
        if (column >= columns) {
            column = 0
            inRow = 0
        }
    }
    return out
}

/** Activities' columns on the grid: ⌊(W + gap) / (160 + gap)⌋, at least 2. */
fun homeGridActivityColumns(widthDp: Float, gapDp: Float = HomeGridTokens.ColumnGap): Int =
    max(2, floor((widthDp + gapDp) / (HomeGridTokens.ActivityColumnMin + gapDp)).toInt())

/**
 * A row's [columns] cells across [widthPx], [gapPx] apart, snapped to whole
 * px: (left, width) of a cell starting at [column] spanning [span]. Pure.
 */
fun homeGridCellX(column: Int, span: Int, columns: Int, widthPx: Int, gapPx: Int): Pair<Int, Int> {
    val cols = columns.coerceAtLeast(1)
    val cell = (widthPx - gapPx * (cols - 1)).toFloat() / cols
    val left = round(column * (cell + gapPx)).toInt()
    val right = round((column + span) * (cell + gapPx) - gapPx).toInt()
    return left to (right - left).coerceAtLeast(0)
}

/**
 * The grid moves from [last] (a width or column count it was seated at; 0 for none yet) to [now]: its
 * cells re-seat in place, at once, instead of springing from the old layout's places. Pure.
 */
fun homeGridReseats(last: Int, now: Int): Boolean = last != 0 && last != now

// ── Rows of two halves ───────────────────────────────────────────────────

/**
 * Where [section] sits in [rows] for a span drag ([HomeSpanInfo]); null when
 * it isn't laid out. [fullPx] is the content width. A full row can turn half
 * only with the next enabled section displayed right after it ([enabledOrder]).
 */
fun homeSpanInfoOf(
    section: HomeSection,
    rows: List<HomeFeedRow>,
    enabledOrder: List<HomeSection>,
    fullPx: Float,
    gapPx: Float,
): HomeSpanInfo? {
    val index = rows.indexOfFirst { row ->
        when (row) {
            is HomeFeedRow.Full -> row.section == section
            is HomeFeedRow.Halves -> row.left == section || row.right == section
        }
    }
    if (index < 0 || fullPx <= 0f) return null
    val halfPx = (fullPx - gapPx) / 2f
    return when (val row = rows[index]) {
        is HomeFeedRow.Full -> {
            val next = enabledOrder.getOrNull(enabledOrder.indexOf(section) + 1)
            HomeSpanInfo(
                span = HomeSpan.Full,
                currentPx = fullPx,
                halfPx = halfPx,
                fullPx = fullPx,
                hasNext = next != null && rows.getOrNull(index + 1)?.first == next,
            )
        }
        is HomeFeedRow.Halves -> HomeSpanInfo(
            span = if (row.left == section) HomeSpan.LeftHalf else HomeSpan.RightHalf,
            currentPx = halfPx,
            halfPx = halfPx,
            fullPx = fullPx,
            hasNext = true,
        )
    }
}

/**
 * Widths (px) of a row's cells: a full row its section; a pair two halves
 * [gapPx] apart. Under a span drag ([dragged], [dragPx]) the dragged cell is
 * [dragPx] wide and its partner takes what is left (never under half a half,
 * the dragged cell drawn over it). Pure.
 */
fun homeRowCellWidths(
    row: HomeFeedRow,
    widthPx: Int,
    gapPx: Int,
    dragged: HomeSection?,
    dragPx: Int,
): List<Int> {
    val half = (widthPx - gapPx) / 2
    return when (row) {
        is HomeFeedRow.Full -> listOf(if (dragged == row.section) dragPx.coerceIn(1, widthPx) else widthPx)
        is HomeFeedRow.Halves -> {
            val minPartner = half / 2
            when (dragged) {
                row.left -> {
                    val l = dragPx.coerceIn(1, widthPx)
                    listOf(l, (widthPx - gapPx - l).coerceAtLeast(minPartner))
                }
                row.right -> {
                    val r = dragPx.coerceIn(1, widthPx)
                    listOf((widthPx - gapPx - r).coerceAtLeast(minPartner), r)
                }
                else -> listOf(half, widthPx - gapPx - half)
            }
        }
    }
}

// ── The half-column shelf pager ──────────────────────────────────────────

/** Numbers of the half-column pager, dp. */
object HomeShelfPagerRules {
    /** The narrowest gap between two cards on a page; the page's spare width widens it. */
    const val MinGap = 14f

    /** A release this fast (dp/s) turns the page even short of halfway. */
    const val FlingVelocity = 400f
}

/** A page's layout: [perPage] cards of [cardPx], [gapPx] apart, one page every [stridePx]. */
data class ShelfPageGeometry(
    val pagePx: Float,
    val cardPx: Float,
    val perPage: Int,
    val gapPx: Float,
) {
    /** Scroll distance from one page to the next: a page plus the gap after it. */
    val stridePx: Float get() = perPage * (cardPx + gapPx)
}

/**
 * How [cardPx]-wide cards page across a [pagePx]-wide column: as many as fit
 * with at least [minGapPx] between them (at least one), the gaps stretched so
 * the first card's left and the last one's right are the column's edges. A
 * single card per page keeps [minGapPx] past the page. Pure.
 */
fun shelfPageGeometry(pagePx: Float, cardPx: Float, minGapPx: Float): ShelfPageGeometry {
    val page = max(pagePx, 1f)
    val card = cardPx.coerceIn(1f, page)
    val perPage = max(1, floor((page + minGapPx) / (card + minGapPx)).toInt())
    val gap = if (perPage > 1) (page - perPage * card) / (perPage - 1) else page - card + minGapPx
    return ShelfPageGeometry(pagePx = page, cardPx = card, perPage = perPage, gapPx = gap)
}

/** A card's width (dp) when [perPage] of them, [HomeShelfPagerRules.MinGap] apart, fill a [pageWidthDp] page; at least 1. */
fun homeShelfCardWidthDp(pageWidthDp: Float, perPage: Int): Float =
    ((pageWidthDp - HomeShelfPagerRules.MinGap * (perPage - 1)) / perPage).coerceAtLeast(1f)

/** Pages of [cardCount] cards after [leadPages] one-page lead items (Recently Added's track grid). Pure. */
fun shelfPageCount(cardCount: Int, perPage: Int, leadPages: Int = 0): Int =
    leadPages + if (cardCount <= 0) 0 else ceil(cardCount / perPage.toFloat()).toInt()

/**
 * The trailing filler that makes the last page whole, px (0 when it already is):
 * without it the list would stop short and the last scroll position cut the
 * page before it. The arrangement adds one gap ahead of it. Pure.
 */
fun shelfFillerPx(cardCount: Int, geometry: ShelfPageGeometry): Float {
    if (cardCount <= 0) return 0f
    val missing = (geometry.perPage - cardCount % geometry.perPage) % geometry.perPage
    return if (missing == 0) 0f else missing * (geometry.cardPx + geometry.gapPx) - geometry.gapPx
}

/**
 * The shelf's scroll from its start, px: item [index] [offsetPx] past the
 * viewport's start, every card one card-plus-gap, a lead item one stride. Pure.
 */
fun shelfScrollPx(index: Int, offsetPx: Int, geometry: ShelfPageGeometry, leadPages: Int = 0): Float {
    val before = if (index < leadPages) {
        index * geometry.stridePx
    } else {
        leadPages * geometry.stridePx + (index - leadPages) * (geometry.cardPx + geometry.gapPx)
    }
    return before + offsetPx
}

/** How far [scrollPx] is from the nearest page boundary, px (0 = settled on a page). Pure. */
fun shelfOffPagePx(scrollPx: Float, stridePx: Float): Float {
    if (stridePx <= 0f) return 0f
    val into = ((scrollPx % stridePx) + stridePx) % stridePx
    return min(into, stridePx - into)
}

/**
 * The page a release lands on: one page from [startPage] in the release's
 * direction — the swipe's own if it is past [flingPx]/s, else whichever page
 * [scrollPx] is closer to — never more than one page away, within the shelf. Pure.
 */
fun shelfReleasePage(
    scrollPx: Float,
    velocityPx: Float,
    stridePx: Float,
    startPage: Int,
    pageCount: Int,
    flingPx: Float,
): Int {
    if (pageCount <= 1 || stridePx <= 0f) return 0
    val position = scrollPx / stridePx
    val target = when {
        velocityPx > flingPx -> floor(position).toInt() + 1
        velocityPx < -flingPx -> ceil(position).toInt() - 1
        else -> position.roundToInt()
    }
    return target.coerceIn(startPage - 1, startPage + 1).coerceIn(0, pageCount - 1)
}

/** The tide's reveal at [offPagePx] from a settled page: 0 settled, 1 from [revealPx] on. Pure. */
fun shelfTideReveal(offPagePx: Float, revealPx: Float): Float =
    if (revealPx <= 0f) 0f else (offPagePx / revealPx).coerceIn(0f, 1f)
