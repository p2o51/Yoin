package com.gpo.yoin.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.shared.home.HomeGridTokens as SharedGrid
import com.gpo.yoin.shared.home.intersectUnits as sharedIntersectUnits
import com.gpo.yoin.shared.home.meetsUnits as sharedMeetsUnits
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Large-screen Home, step 2 groundwork (owner 2026-11-16, plan 「首页格高方案」):
// in two columns every section is a whole number of grid units tall, so the
// two halves of a row always line up. Height = T + n·U + (n − 1)·G. The
// stored row preset S/M/L/XL reads as 1/2/3/4 units here; phones keep reading
// it as rows. A pair shares one count. The sections still lay out their old
// content inside that box for now (clipped at the bottom); their own
// unit-based layouts come next.

/** Numbers of the two-column grid ([com.gpo.yoin.shared.home.HomeGridTokens], as Dp). */
internal object HomeGridTokens {
    /** One unit. 150 fits a 106dp cover and two lines; 120 is the denser option (open question 1). */
    val Unit = SharedGrid.Unit.dp

    /** Between two units of one section. */
    val Gap = SharedGrid.Gap.dp

    /** The title row every section has in two columns. */
    val TitleRow = SharedGrid.TitleRow.dp

    /** A unit drag can pull this far past the smallest or largest count (a rubber band past it). */
    val Overdrag = SharedGrid.Overdrag.dp

    /** What a cover card's two caption lines (and the gap above them) take out of a unit: cover = U − 44. */
    val CaptionBand = SharedGrid.CaptionBand.dp

    /** The narrowest gap between two cover cards in a row; a page's spare width widens it. */
    val ColumnGap = SharedGrid.ColumnGap.dp

    /** Between two of Recently Added's track rows; the rows themselves fill the rest of the box. */
    val TrackRowGap = SharedGrid.TrackRowGap.dp
}

// The grid's arithmetic is :shared's (com.gpo.yoin.shared.home, golden home-grid.json); these keep the app's names.

/** A cover shelf's cover side at [unit] dp: U − 44 (106 at 150). */
internal fun homeGridCoverDp(unit: Float = HomeGridTokens.Unit.value): Float = com.gpo.yoin.shared.home.homeGridCoverDp(unit)

/** Cover columns a [widthDp] page seats: ⌊(W + gap) / (cover + gap)⌋, at least 1. */
internal fun homeGridColumns(
    widthDp: Float,
    coverDp: Float = homeGridCoverDp(),
    gapDp: Float = HomeGridTokens.ColumnGap.value,
): Int = com.gpo.yoin.shared.home.homeGridColumns(widthDp, coverDp, gapDp)

/** The content height under the title row at [units] units, dp: n·U + (n − 1)·G. */
internal fun homeGridContentDp(units: Int): Float = com.gpo.yoin.shared.home.homeGridContentDp(units)

/** Recently Added's track rows at [units] units: three a unit. */
internal fun homeGridTrackRows(units: Int): Int = com.gpo.yoin.shared.home.homeGridTrackRows(units)

/** Recently Added's narrowest track column on the grid. */
internal const val HomeGridTrackColumnMinDp = SharedGrid.TrackColumnMin

/** Recently Added's track columns on the grid: ⌊W / 280⌋, at least 1 (a half 2, a full row 3–4). */
internal fun homeGridTrackColumns(widthDp: Float): Int = com.gpo.yoin.shared.home.homeGridTrackColumns(widthDp)

/** One track row's height at [units] units, dp: the rows and their gaps fill the content box exactly. */
internal fun homeGridTrackRowDp(units: Int, gapDp: Float = HomeGridTokens.TrackRowGap.value): Float =
    com.gpo.yoin.shared.home.homeGridTrackRowDp(units, gapDp)

/** A section's height in two columns at [units] units: T + n·U + (n − 1)·G, in dp. */
internal fun homeGridHeightDp(units: Int): Float = com.gpo.yoin.shared.home.homeGridHeightDp(units)

internal fun Density.homeGridHeightPx(units: Int): Float = homeGridHeightDp(units).dp.toPx()

/** [units] as the preset that stores it (counts past 4 store as XL). */
internal fun homeUnitsPreset(units: Int): HomeRowPreset = com.gpo.yoin.shared.home.homeUnitsPreset(units)

/** Two ranges' overlap; the first range's lower end alone when they don't meet. */
internal fun IntRange.intersectUnits(other: IntRange): IntRange = sharedIntersectUnits(other)

/** [state]'s own unit count in two columns ([com.gpo.yoin.shared.home.homeSectionUnits]). */
internal fun homeSectionUnits(state: HomeSectionState): Int = com.gpo.yoin.shared.home.homeSectionUnits(state)

/** The count a row of [left] (and [right], when paired) shows ([com.gpo.yoin.shared.home.homeRowUnits]). */
internal fun homeRowUnits(left: HomeSectionState, right: HomeSectionState?): Int = com.gpo.yoin.shared.home.homeRowUnits(left, right)

/** Two ranges share a count: a pair of sections with them can be one row height. */
internal fun IntRange.meetsUnits(other: IntRange): Boolean = sharedMeetsUnits(other)

/** [left] and [right] can be the two halves of a row ([com.gpo.yoin.shared.home.homeCanPair]). */
internal fun homeCanPair(left: HomeSection, right: HomeSection): Boolean = com.gpo.yoin.shared.home.homeCanPair(left, right)

/** The range a row's handle can drag over: the section's own, or the overlap with its partner. */
internal fun homeRowUnitRange(left: HomeSection, right: HomeSection?): IntRange = com.gpo.yoin.shared.home.homeRowUnitRange(left, right)

/** The count whose height is nearest [heightPx] within [range]. */
internal fun Density.homeUnitsNearest(heightPx: Float, range: IntRange): Int = com.gpo.yoin.shared.home.homeUnitsNearest(heightPx, range, density)

// ── The box a section lives in ──────────────────────────────────────────────

/**
 * A two-column cell: its row's [units] (the content lays out for these) and
 * its height in px, read in the layout pass (a unit drag's live height). Null: no grid (one column).
 */
@Stable
internal class HomeGridHeight(val units: Int, val px: () -> Int)

internal val LocalHomeGridHeight = compositionLocalOf<HomeGridHeight?> { null }

/**
 * Two columns: exactly [grid] tall, the content top-aligned in it and cut
 * off at the bottom (sideways it may still bleed, as shelves do). Without a
 * grid it is the content's own height.
 */
internal fun Modifier.homeGridBox(grid: HomeGridHeight?): Modifier {
    if (grid == null) return this
    return this
        .layout { measurable, constraints ->
            val h = grid.px().coerceAtLeast(0)
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = h))
            layout(placeable.width, h) { placeable.place(0, 0) }
        }
        .drawWithContent {
            val bleed = size.width
            clipRect(left = -bleed, top = 0f, right = size.width + bleed, bottom = size.height) {
                this@drawWithContent.drawContent()
            }
        }
}

// ── The handle's vertical axis in two columns ───────────────────────────────

/**
 * The one unit drag in flight. While the finger is down the row's height
 * follows it 1:1 (held a little past the smallest and largest counts); on
 * release it springs to the nearest count and writes it through [commit]
 * (one Undo step, both halves of a pair). ↑ / ↓ step a count.
 */
@Stable
internal class HomeUnitDrag(
    private val scope: CoroutineScope,
    private val density: Density,
    private val spec: () -> AnimationSpec<Float>,
    private val commit: (HomeSection, Int) -> Boolean,
    private val tick: () -> Unit,
) {
    /** The dragged section (either half of its row); null when nothing is dragged. */
    var section: HomeSection? by mutableStateOf(null)
        private set

    /** The dragged row's sections. */
    var row: Set<HomeSection> by mutableStateOf(emptySet())
        private set

    /** Live height of the dragged row, px. Layout only. */
    val height = Animatable(0f)

    /** The count the row will settle on. */
    var target: Int by mutableStateOf(1)
        private set

    var dragging: Boolean by mutableStateOf(false)
        private set

    /** The feed's answer for a section's row: its sections, count and range; null in one column. Set by the feed. */
    var info: (HomeSection) -> HomeUnitInfo? = { null }

    private var startY = 0f
    private var startPx = 0f
    private var range: IntRange = 1..1
    private var settle: Job? = null

    /** Starts dragging [section]'s row at finger y [fingerY] (root px). False in one column. */
    fun grab(section: HomeSection, fingerY: Float): Boolean {
        val place = info(section) ?: return false
        settle?.cancel()
        this.section = section
        row = place.row
        range = place.range
        target = place.units
        startY = fingerY
        startPx = density.homeGridHeightPx(place.units)
        dragging = true
        scope.launch { height.snapTo(startPx) }
        return true
    }

    fun dragTo(fingerY: Float) {
        if (section == null || !dragging) return
        val over = with(density) { HomeGridTokens.Overdrag.toPx() }
        val min = density.homeGridHeightPx(range.first) - over
        val max = density.homeGridHeightPx(range.last) + over
        val h = (startPx + fingerY - startY).coerceIn(min, max)
        scope.launch { height.snapTo(h) }
        val next = density.homeUnitsNearest(h, range)
        if (next != target) {
            target = next
            tick()
        }
    }

    /** Springs to the target count (or back) and writes it once it lands. */
    fun release(cancelled: Boolean) {
        val dragged = section ?: return
        val start = info(dragged)?.units ?: target
        dragging = false
        val units = if (cancelled) start else target
        settle = scope.launch {
            try {
                height.animateTo(density.homeGridHeightPx(units), spec())
                if (units != start) commit(dragged, units)
            } finally {
                if (section == dragged && !dragging) {
                    section = null
                    row = emptySet()
                }
            }
        }
    }

    /** ↑ / ↓ (or TalkBack): one count fewer or more, written at once. False when it can't. */
    fun step(section: HomeSection, delta: Int): Boolean {
        val place = info(section) ?: return false
        val next = place.units + delta
        if (next !in place.range) return false
        return commit(section, next)
    }

    fun canStep(section: HomeSection, delta: Int): Boolean {
        val place = info(section) ?: return false
        return place.units + delta in place.range
    }

    /** Drops any drag at once (edit mode left, a carry started). */
    fun snap() {
        settle?.cancel()
        dragging = false
        section = null
        row = emptySet()
    }

    /** The live height for a row of [sections], or null when it isn't the dragged row. */
    fun liveHeightPx(sections: Collection<HomeSection>): Int? {
        val dragged = section ?: return null
        if (dragged !in sections) return null
        return height.value.roundToInt()
    }
}

/** Where a section's row sits on the grid: its sections, current count and allowed range. */
internal data class HomeUnitInfo(val row: Set<HomeSection>, val units: Int, val range: IntRange)

// ── Shared pieces of a section on the grid ─────────────────────────────────

/** The phone's (and one column's) shelf cover. */
internal val HomeShelfCoverPhone = 82.dp

/** The cover a shelf card shows: U − 44 on the grid, the phone's 82 elsewhere. */
@Composable
internal fun homeShelfCoverSize(): Dp =
    if (LocalHomeGridHeight.current != null) homeGridCoverDp().dp else HomeShelfCoverPhone

/**
 * The 40dp title row every section has on the grid: the title on the row's
 * centre line, then [trailing] (a shelf's page dots and ‹ ›) at its end.
 */
@Composable
internal fun HomeGridTitleRow(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HomeGridTokens.TitleRow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeSectionTitle(text = title, modifier = Modifier.weight(1f))
        trailing()
    }
}

/**
 * A shelf's cards on the grid: [rows] to a column (stacked [HomeGridTokens.Gap]
 * apart, each exactly [cellHeight] tall), one list item per column. Off the
 * grid ([cellHeight] null, one row) the cards are the items, as before.
 */
internal fun <T> LazyListScope.homeShelfCards(
    items: List<T>,
    rows: Int,
    cellHeight: Dp?,
    key: (T) -> Any,
    card: @Composable (index: Int, item: T) -> Unit,
) {
    if (cellHeight == null && rows <= 1) {
        items(count = items.size, key = { key(items[it]) }) { index -> card(index, items[index]) }
        return
    }
    val perColumn = rows.coerceAtLeast(1)
    val stacks = items.chunked(perColumn)
    items(count = stacks.size, key = { key(stacks[it].first()) }) { stackIndex ->
        // A unit count changing re-stacks the cards: columns slide to their places, new ones fade in.
        Column(
            modifier = Modifier.animateItem(
                fadeInSpec = com.gpo.yoin.ui.theme.YoinMotion.effectsSpring(),
                placementSpec = com.gpo.yoin.ui.theme.YoinMotion.spatialSpring(),
                fadeOutSpec = com.gpo.yoin.ui.theme.YoinMotion.effectsSpring(),
            ),
            verticalArrangement = Arrangement.spacedBy(HomeGridTokens.Gap),
        ) {
            stacks[stackIndex].forEachIndexed { inStack, item ->
                Box(if (cellHeight != null) Modifier.height(cellHeight) else Modifier) {
                    card(stackIndex * perColumn + inStack, item)
                }
            }
        }
    }
}

/** A cover shelf's rows on the grid: its row's units (1 or 2); 1 off the grid. */
@Composable
internal fun homeShelfRows(): Int = LocalHomeGridHeight.current?.units?.coerceIn(1, 2) ?: 1

/** A grid shelf card's height: exactly one unit; null off the grid (the card's own). */
@Composable
internal fun homeShelfCellHeight(): Dp? = if (LocalHomeGridHeight.current != null) HomeGridTokens.Unit else null

/** How many list columns [cards] make on the grid ([homeShelfRows] to a column); [cards] off it. */
@Composable
internal fun homeShelfColumnCount(cards: Int): Int {
    val rows = homeShelfRows()
    return if (cards <= 0) 0 else (cards + rows - 1) / rows
}

// ── Packing cards into unit rows ──────────────────────────────────────────

/** A card placed on the grid ([com.gpo.yoin.shared.home.HomeGridCell]). */
internal typealias HomeGridCell = com.gpo.yoin.shared.home.HomeGridCell

/** Cards of [spans] columns each, row-major into [rows] rows of [columns] ([com.gpo.yoin.shared.home.homeGridPack]). Pure. */
internal fun homeGridPack(spans: List<Int>, columns: Int, rows: Int): List<HomeGridCell> = com.gpo.yoin.shared.home.homeGridPack(spans, columns, rows)

/** Activities' spans on the grid ([com.gpo.yoin.shared.home.homeGridActivitySpans]). Pure. */
internal fun homeGridActivitySpans(count: Int, columns: Int): List<Int> = com.gpo.yoin.shared.home.homeGridActivitySpans(count, columns)

/** The narrowest one-column Activities card on the grid, dp. */
internal const val HomeGridActivityColumnMinDp = SharedGrid.ActivityColumnMin

/** Activities' columns on the grid: ⌊(W + gap) / (160 + gap)⌋, at least 2. */
internal fun homeGridActivityColumns(widthDp: Float, gapDp: Float = HomeGridTokens.ColumnGap.value): Int =
    com.gpo.yoin.shared.home.homeGridActivityColumns(widthDp, gapDp)

/** A row's cells snapped to whole px ([com.gpo.yoin.shared.home.homeGridCellX]). Pure. */
internal fun homeGridCellX(column: Int, span: Int, columns: Int, widthPx: Int, gapPx: Int): Pair<Int, Int> =
    com.gpo.yoin.shared.home.homeGridCellX(column, span, columns, widthPx, gapPx)

/**
 * [cells] laid out on the grid: every cell exactly [rowHeight] tall (a unit), rows [rowGap] apart,
 * [columns] equal columns [gap] apart across the width. [stretchLast]: the last cell takes what is left
 * of its row. A unit count changing at the same width moves each cell to its new place on the spatial
 * spring; one that appears fades in on the effects spring, one that goes fades out where it was.
 *
 * A width (or column count) change re-seats every cell in place, at once: no cell springs from where
 * the old width had it, none fades in or out. Springing there, a block turning half ⇄ full drew its
 * cards at the old layout's places over the new one's (Jump Back In's covers and signal cards sliding
 * across each other while the new row faded in over them, and the going ones fading out past the
 * narrowed block, 2026-11-23), and kept the old width's cells composed beside the new ones.
 */
@Composable
internal fun HomeGridCells(
    cells: List<HomeGridCell>,
    columns: Int,
    gap: Dp,
    modifier: Modifier = Modifier,
    stretchLast: Boolean = false,
    rowHeight: Dp = HomeGridTokens.Unit,
    rowGap: Dp = HomeGridTokens.Gap,
    key: (HomeGridCell) -> Any = { it.index },
    content: @Composable (HomeGridCell) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val spec = com.gpo.yoin.ui.theme.YoinMotion.spatialSpring<androidx.compose.ui.unit.IntOffset>()
    val fadeOut = com.gpo.yoin.ui.theme.YoinMotion.effectsSpring<Float>()
    val reduced = com.gpo.yoin.ui.experience.LocalMotionProfile.current ==
        com.gpo.yoin.ui.experience.MotionProfile.AdaptiveReduced
    // Each cell's place, by key: where it sits now and where it is going. Not state: read in placement.
    val places = androidx.compose.runtime.remember { HashMap<Any, HomeGridPlace>() }
    // What each key last drew (its cell and the content lambda of that composition, which still holds
    // the data it was drawn from), so a cell that leaves can fade out as itself.
    val lastSeen = androidx.compose.runtime.remember { LinkedHashMap<Any, HomeGridDrawn>() }
    val leaving = androidx.compose.runtime.remember { LinkedHashMap<Any, HomeGridLeaving>() }
    var leavingVersion by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    leavingVersion // read: a finished fade recomposes the leaving cell away
    // The column count and width the cells were last seated at: a change of either re-seats them at once.
    val seat = androidx.compose.runtime.remember { HomeGridSeat() }
    val reseat = homeGridReseats(seat.composedColumns, columns)
    seat.composedColumns = columns
    val currentKeys = cells.map(key)
    val currentSet = currentKeys.toHashSet()
    if (reseat) {
        // The width moved: the cells the old width had and this one hasn't go now, with any still fading.
        leaving.keys.forEach { places.remove(it) }
        leaving.clear()
    } else if (!reduced) {
        lastSeen.forEach { (k, drawn) ->
            if (k !in currentSet && k !in leaving) {
                val leave = HomeGridLeaving(drawn)
                leaving[k] = leave
            }
        }
    }
    currentSet.forEach { leaving.remove(it) }
    lastSeen.clear()
    cells.forEachIndexed { i, cell -> lastSeen[currentKeys[i]] = HomeGridDrawn(cell, content) }
    val leavingNow = leaving.entries.toList()
    androidx.compose.ui.layout.Layout(
        content = {
            cells.forEachIndexed { i, cell ->
                androidx.compose.runtime.key(currentKeys[i]) {
                    // A cell the new width brings in is simply there, like the rest of the new layout.
                    HomeGridFadeIn(reduced || reseat) { content(cell) }
                }
            }
            leavingNow.forEach { (k, leave) ->
                androidx.compose.runtime.key(k) {
                    // The fade runs as an effect of the leaving cell, never launched during composition;
                    // a cell that comes back leaves composition here, which cancels its fade.
                    androidx.compose.runtime.LaunchedEffect(leave) {
                        leave.alpha.animateTo(0f, fadeOut)
                        if (leaving[k] === leave) {
                            leaving.remove(k)
                            places.remove(k)
                            leavingVersion++
                        }
                    }
                    Box(Modifier.graphicsLayer { alpha = leave.alpha.value }) { leave.drawn.content(leave.drawn.cell) }
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val unit = rowHeight.roundToPx()
        val rowGapPx = rowGap.roundToPx()
        val all = cells.mapIndexed { i, cell -> Triple(cell, currentKeys[i], i == cells.lastIndex) } +
            leavingNow.map { (k, leave) -> Triple(leave.drawn.cell, k, false) }
        val placed = measurables.mapIndexed { i, measurable ->
            val (cell, k, last) = all[i]
            val span = if (stretchLast && last) columns - cell.column else cell.span
            val (x, w) = homeGridCellX(cell.column, span.coerceAtMost(columns - cell.column).coerceAtLeast(1), columns, width, gapPx)
            Triple(
                measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, unit)),
                androidx.compose.ui.unit.IntOffset(x, cell.row * (unit + rowGapPx)),
                k,
            )
        }
        val rows = (cells.maxOfOrNull { it.row } ?: -1) + 1
        val height = if (rows <= 0) 0 else rows * unit + (rows - 1) * rowGapPx
        // Measured at another width (or column count) than last time: every cell is placed where this
        // width puts it, this frame (a snap launched would land a frame late, drawn at the old place).
        val snap = reduced || homeGridReseats(seat.width, width) || homeGridReseats(seat.columns, columns)
        seat.width = width
        seat.columns = columns
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placed.forEach { (placeable, target, k) ->
                var place = places.getOrPut(k) { HomeGridPlace(target) }
                if (k in currentSet) {
                    if (snap && (place.target != target || place.offset.value != target)) {
                        place = HomeGridPlace(target)
                        places[k] = place
                    } else if (place.target != target) {
                        place.target = target
                        val moving = place
                        scope.launch { moving.offset.animateTo(target, spec) }
                    }
                }
                placeable.placeRelative(place.offset.value)
            }
        }
    }
}

/** What [HomeGridCells] was last seated at: the column count it composed, the width and columns it measured (0: not yet). */
private class HomeGridSeat {
    var composedColumns = 0
    var width = 0
    var columns = 0
}

/** The grid moves from [last] to [now]: its cells re-seat in place ([com.gpo.yoin.shared.home.homeGridReseats]). Pure. */
internal fun homeGridReseats(last: Int, now: Int): Boolean = com.gpo.yoin.shared.home.homeGridReseats(last, now)

/** A key's cell and the content lambda it was last drawn with. */
private class HomeGridDrawn(val cell: HomeGridCell, val content: @Composable (HomeGridCell) -> Unit)

/** A cell fading out where it last was. */
private class HomeGridLeaving(val drawn: HomeGridDrawn) {
    val alpha = Animatable(1f)
}

/** Where a grid cell is and is going. */
private class HomeGridPlace(start: androidx.compose.ui.unit.IntOffset) {
    var target = start
    val offset = Animatable(start, androidx.compose.ui.unit.IntOffset.VectorConverter)
}

/** A cell that appears fades in on the effects spring (at once with reduced motion). */
@Composable
private fun HomeGridFadeIn(reduced: Boolean, content: @Composable () -> Unit) {
    val alpha = androidx.compose.runtime.remember { Animatable(if (reduced) 1f else 0f) }
    val spec = com.gpo.yoin.ui.theme.YoinMotion.effectsSpring<Float>()
    androidx.compose.runtime.LaunchedEffect(Unit) { alpha.animateTo(1f, spec) }
    Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}
