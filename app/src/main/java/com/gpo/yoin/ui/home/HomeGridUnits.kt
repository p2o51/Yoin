package com.gpo.yoin.ui.home

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
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

/** Numbers of the two-column grid. */
internal object HomeGridTokens {
    /** One unit. 150 fits a 106dp cover and two lines; 120 is the denser option (open question 1). */
    val Unit = 150.dp

    /** Between two units of one section. */
    val Gap = 14.dp

    /** The title row every section has in two columns. */
    val TitleRow = 40.dp

    /** A unit drag can pull this far past the smallest or largest count (a rubber band past it). */
    val Overdrag = 24.dp

    /** What a cover card's two caption lines (and the gap above them) take out of a unit: cover = U − 44. */
    val CaptionBand = 44.dp

    /** The narrowest gap between two cover cards in a row; a page's spare width widens it. */
    val ColumnGap = 12.dp

    /** Between two of Recently Added's track rows; the rows themselves fill the rest of the box. */
    val TrackRowGap = 6.dp
}

/** A cover shelf's cover side at [unit] dp: U − 44 (106 at 150). */
internal fun homeGridCoverDp(unit: Float = HomeGridTokens.Unit.value): Float =
    (unit - HomeGridTokens.CaptionBand.value).coerceAtLeast(1f)

/** Cover columns a [widthDp] page seats: ⌊(W + gap) / (cover + gap)⌋, at least 1. */
internal fun homeGridColumns(
    widthDp: Float,
    coverDp: Float = homeGridCoverDp(),
    gapDp: Float = HomeGridTokens.ColumnGap.value,
): Int = kotlin.math.max(1, kotlin.math.floor((widthDp + gapDp) / (coverDp + gapDp)).toInt())

/** The content height under the title row at [units] units, dp: n·U + (n − 1)·G. */
internal fun homeGridContentDp(units: Int): Float = homeGridHeightDp(units) - HomeGridTokens.TitleRow.value

/** Recently Added's track rows at [units] units: three a unit. */
internal fun homeGridTrackRows(units: Int): Int = 3 * units.coerceAtLeast(1)

/** One track row's height at [units] units, dp: the rows and their gaps fill the content box exactly. */
internal fun homeGridTrackRowDp(units: Int, gapDp: Float = HomeGridTokens.TrackRowGap.value): Float {
    val rows = homeGridTrackRows(units)
    return (homeGridContentDp(units) - (rows - 1) * gapDp) / rows
}

/** A section's height in two columns at [units] units: T + n·U + (n − 1)·G, in dp. */
internal fun homeGridHeightDp(units: Int): Float {
    val n = units.coerceAtLeast(1)
    return HomeGridTokens.TitleRow.value + n * HomeGridTokens.Unit.value + (n - 1) * HomeGridTokens.Gap.value
}

internal fun Density.homeGridHeightPx(units: Int): Float = homeGridHeightDp(units).dp.toPx()

/** The unit counts [this] section can take in two columns. */
internal val HomeSection.unitRange: IntRange
    get() = when (this) {
        HomeSection.Activities -> 1..3
        HomeSection.JumpBackIn -> 1..4
        HomeSection.LyricHighlight -> 1..2
        HomeSection.RecentlyAdded -> 1..2
        HomeSection.MemoryGrid -> 1..3
        HomeSection.RecentlyPlayed,
        HomeSection.YourPlaylists,
        HomeSection.MadeForYou,
        HomeSection.Rediscover,
        HomeSection.OtherProfiles,
        -> 1..2
    }

/** The count a section with nothing stored takes: 2 for the two big default rows' sections, 1 for the rest. */
internal val HomeSection.defaultUnits: Int
    get() = when (this) {
        HomeSection.Activities,
        HomeSection.JumpBackIn,
        HomeSection.LyricHighlight,
        HomeSection.RecentlyAdded,
        -> 2
        else -> 1
    }

/** A stored row preset as units: S 1, M 2, L 3, XL 4. */
internal val HomeRowPreset.units: Int
    get() = when (this) {
        HomeRowPreset.S -> 1
        HomeRowPreset.M -> 2
        HomeRowPreset.L -> 3
        HomeRowPreset.XL -> 4
    }

/** [units] as the preset that stores it (counts past 4 store as XL). */
internal fun homeUnitsPreset(units: Int): HomeRowPreset = when {
    units <= 1 -> HomeRowPreset.S
    units == 2 -> HomeRowPreset.M
    units == 3 -> HomeRowPreset.L
    else -> HomeRowPreset.XL
}

/** Two ranges' overlap; the first range's lower end alone when they don't meet. */
internal fun IntRange.intersectUnits(other: IntRange): IntRange {
    val lo = maxOf(first, other.first)
    val hi = minOf(last, other.last)
    return if (lo <= hi) lo..hi else lo..lo
}

/**
 * [state]'s own unit count in two columns: its chosen row preset as units, or
 * its default when it never chose one ([HomeSectionState.rowsSet]), held in its range.
 */
internal fun homeSectionUnits(state: HomeSectionState): Int {
    val stored = if (state.rowsSet) state.rows.units else state.section.defaultUnits
    return stored.coerceIn(state.section.unitRange)
}

/**
 * The count a row of [left] (and [right], when paired) shows: a pair takes
 * the left one's count, held in the overlap of both ranges.
 */
internal fun homeRowUnits(left: HomeSectionState, right: HomeSectionState?): Int {
    val own = homeSectionUnits(left)
    if (right == null) return own
    return own.coerceIn(left.section.unitRange.intersectUnits(right.section.unitRange))
}

/** The range a row's handle can drag over: the section's own, or the overlap with its partner. */
internal fun homeRowUnitRange(left: HomeSection, right: HomeSection?): IntRange =
    if (right == null) left.unitRange else left.unitRange.intersectUnits(right.unitRange)

/** The count whose height is nearest [heightPx] within [range]. */
internal fun Density.homeUnitsNearest(heightPx: Float, range: IntRange): Int =
    range.minByOrNull { abs(homeGridHeightPx(it) - heightPx) } ?: range.first

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
        Column(verticalArrangement = Arrangement.spacedBy(HomeGridTokens.Gap)) {
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
