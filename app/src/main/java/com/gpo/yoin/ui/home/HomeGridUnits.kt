package com.gpo.yoin.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
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

/** A two-column cell's height in px, read in the layout pass. Null: no grid (one column). */
@Stable
internal class HomeGridHeight(val px: () -> Int)

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
