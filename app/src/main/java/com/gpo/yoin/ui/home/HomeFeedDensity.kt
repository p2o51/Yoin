package com.gpo.yoin.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.memory.REDISCOVER_LIMIT

// Home feed density: a wider container gets MORE cards and columns, never
// bigger ones — every card's insides (Activities 96/80/48 covers, the JBI 1×1
// copy block, Recently Added's 52/82 covers, every type size) stay the same on
// every screen shape. The only inputs are `feedUnits` (N, the container's
// resting width folded into a discrete integer by ui/experience/FeedUnits.kt)
// and the landscape-phone height gate, plus — for the Activities bento — a
// seed taken from the data, so the same feed always lays out the same way.
// Everything here is pure (no Compose runtime beyond Dp) and pinned by
// HomeFeedDensityTest. Phones (N ≤ 2) and the landscape phone keep today's
// compositions byte for byte. New Home density decisions belong in this file
// or FeedUnits.kt, nowhere else.

/** N is clamped to this range before any recipe is picked. */
private const val MinFeedUnits = 1
private const val MaxFeedUnits = 8

// ---- Activities bento ----

/** How the Activities bento composes. Only [Units] is described by data; the other three stay code. */
internal enum class BentoRecipe {
    /** Landscape phone (height < 480): one row, hero 2 : small 1 : wide 1.4. Unchanged. */
    Landscape,

    /** N = 1 (a foldable beside the NP panel): the hero row, then two equal smalls, then one strip. */
    PhoneNarrow,

    /** N = 2: today's phone composition — hero row, small | wide 1:2, one strip. Unchanged. */
    Phone,

    /** N ≥ 3: every card on N true unit columns, rows drawn from the staggered palette below. */
    Units,
}

/**
 * One resolved Activities bento. [row1], [row2] and [strips] describe the
 * [BentoRecipe.Units] grid only and are empty / 0 for the other recipes, whose
 * composition stays as code.
 *
 * @property units N, clamped to 1..8.
 * @property row1 spans in units; `row1[0]` is the hero slot (or the first wide when there is no hero).
 * @property row2 spans in units; 1 = small, 2 = wide.
 * @property strips how many equal-width strips close the bento.
 * @property row1Height before × fontScale: 124dp (the hero card's own height, 96dp cover + 14dp × 2)
 *   for Landscape and N ≤ 4, 128dp for N ≥ 5 (Wide's taller first row); 0dp (unused) for Phone / PhoneNarrow.
 */
internal data class ActivityBentoSpec(
    val recipe: BentoRecipe,
    val units: Int,
    val row1: List<Int>,
    val row2: List<Int>,
    val strips: Int,
    val row1Height: Dp,
) {
    /** Every card the bento shows, the hero slot included. */
    val totalSlots: Int
        get() = when (recipe) {
            BentoRecipe.Units -> row1.size + row2.size + strips
            BentoRecipe.Phone, BentoRecipe.PhoneNarrow -> 4
            BentoRecipe.Landscape -> 3
        }

    /** The cards after the hero slot — how many supporting entries to take. */
    val supportingSlots: Int get() = totalSlots - 1
}

private val BentoRow1Height = 124.dp
private val BentoRow1HeightWide = 128.dp

/** One row-1 / row-2 pair of the palette. */
private class UnitBentoRows(val row1: List<Int>, val row2: List<Int>)

/** Every option for one N, sharing its strip count. */
private class UnitBentoPalette(val strips: Int, val options: List<UnitBentoRows>)

// The Units palette (owner 2026-10-03: 「总之要保留错落感和一些随机性」 — rows
// vary and stay staggered instead of one regular alternating table). Item
// counts per N are 5 / 7 / 8 / 10 / 12 / 13. Every option keeps row 1
// tapering after the hero (hero → wide → small); row 2's interior seams avoid
// row 1's as far as spans of 1 and 2 allow — none for N ≤ 5 except
// [2,2,1] / [2,1,2], at most one from N = 6 on. From N = 7 half the options
// give the hero 3 units, so its width itself varies. Option order is part of
// the contract: a seed picks `options[seed % size]`.
private val ActivityUnitPalette: Map<Int, UnitBentoPalette> = mapOf(
    3 to UnitBentoPalette(
        strips = 1,
        options = listOf(
            UnitBentoRows(listOf(2, 1), listOf(1, 2)),
        ),
    ),
    4 to UnitBentoPalette(
        strips = 2,
        options = listOf(
            UnitBentoRows(listOf(2, 2), listOf(1, 2, 1)),
        ),
    ),
    5 to UnitBentoPalette(
        strips = 2,
        options = listOf(
            UnitBentoRows(listOf(2, 2, 1), listOf(1, 2, 2)),
            UnitBentoRows(listOf(2, 2, 1), listOf(2, 1, 2)),
        ),
    ),
    6 to UnitBentoPalette(
        strips = 2,
        options = listOf(
            UnitBentoRows(listOf(2, 2, 1, 1), listOf(1, 2, 2, 1)),
            UnitBentoRows(listOf(2, 2, 2), listOf(1, 1, 1, 2, 1)),
            UnitBentoRows(listOf(2, 2, 2), listOf(1, 2, 1, 1, 1)),
        ),
    ),
    7 to UnitBentoPalette(
        strips = 3,
        options = listOf(
            UnitBentoRows(listOf(2, 2, 2, 1), listOf(1, 2, 1, 1, 2)),
            UnitBentoRows(listOf(2, 2, 2, 1), listOf(1, 2, 2, 1, 1)),
            UnitBentoRows(listOf(3, 2, 1, 1), listOf(1, 1, 2, 1, 2)),
            UnitBentoRows(listOf(3, 2, 1, 1), listOf(1, 1, 2, 2, 1)),
        ),
    ),
    8 to UnitBentoPalette(
        strips = 3,
        options = listOf(
            UnitBentoRows(listOf(2, 2, 2, 1, 1), listOf(1, 2, 2, 1, 2)),
            UnitBentoRows(listOf(2, 2, 2, 2), listOf(1, 2, 1, 1, 2, 1)),
            UnitBentoRows(listOf(3, 2, 2, 1), listOf(1, 1, 2, 1, 1, 2)),
            UnitBentoRows(listOf(3, 2, 2, 1), listOf(1, 1, 2, 2, 1, 1)),
        ),
    ),
)

/**
 * The Activities bento for one container: compact height → [BentoRecipe.Landscape]
 * (any N); N ≤ 1 → [BentoRecipe.PhoneNarrow]; N = 2 → [BentoRecipe.Phone];
 * N ≥ 3 → [BentoRecipe.Units] with `options[seed % options.size]` from the
 * palette, so the same [seed] (see [activityLayoutSeed]) always gives the same
 * rows. Without a hero only options whose `row1[0]` is 2 are eligible — a
 * 3-unit WIDE card would read hollow — and the seed picks among those.
 */
internal fun activityBentoSpec(
    feedUnits: Int,
    isCompactHeight: Boolean,
    seed: Int,
    hasHero: Boolean = true,
): ActivityBentoSpec {
    val units = feedUnits.coerceIn(MinFeedUnits, MaxFeedUnits)
    return when {
        // The landscape row renders the same at any N: carry no units, so a
        // short window crossing a feed breakpoint doesn't crossfade an
        // identical row (the spec is the crossfade's key).
        isCompactHeight -> codeRecipe(BentoRecipe.Landscape, 0, BentoRow1Height)
        units <= 1 -> codeRecipe(BentoRecipe.PhoneNarrow, units, 0.dp)
        units == 2 -> codeRecipe(BentoRecipe.Phone, units, 0.dp)
        else -> {
            val palette = ActivityUnitPalette.getValue(units)
            val eligible = if (hasHero) palette.options else palette.options.filter { it.row1.first() == 2 }
            // mod, not %: a negative seed from a caller that skipped activityLayoutSeed still lands in range.
            val rows = eligible[seed.mod(eligible.size)]
            ActivityBentoSpec(
                recipe = BentoRecipe.Units,
                units = units,
                row1 = rows.row1,
                row2 = rows.row2,
                strips = palette.strips,
                row1Height = if (units <= 4) BentoRow1Height else BentoRow1HeightWide,
            )
        }
    }
}

private fun codeRecipe(recipe: BentoRecipe, units: Int, row1Height: Dp) = ActivityBentoSpec(
    recipe = recipe,
    units = units,
    row1 = emptyList(),
    row2 = emptyList(),
    strips = 0,
    row1Height = row1Height,
)

/** What a [ActivitySlot] renders. */
internal enum class SlotKind { Hero, Wide, Small, Strip }

/**
 * One card of the [BentoRecipe.Units] grid.
 *
 * @property row 0 = row 1, 1 = row 2, 2 = the strip row.
 * @property startUnit rows 0/1: the first unit column the card covers. Strip row: the strip's index.
 * @property span rows 0/1: unit columns covered (`span × u + (span − 1) × gap`). Strip row: always 1,
 *   meaning one equal share of the row split into [ActivityBentoSpec.strips] — strips are not on unit lines.
 * @property entryIndex -1 for the [SlotKind.Hero] slot (the hero entry), otherwise the index into the
 *   SUPPORTING list.
 */
internal data class ActivitySlot(
    val row: Int,
    val startUnit: Int,
    val span: Int,
    val kind: SlotKind,
    val entryIndex: Int,
)

/**
 * How many SUPPORTING entries a composition seats: every slot but the hero's —
 * or every slot, when there is no hero and row 1's lead slot takes a
 * supporting entry as a wide card.
 */
internal fun seatedSupportingCount(spec: ActivityBentoSpec, hasHero: Boolean): Int =
    if (hasHero) spec.supportingSlots else spec.totalSlots

/**
 * Walks a [BentoRecipe.Units] spec — row 1, then row 2, then the strips — and
 * hands out supporting entries in that order. `row1[0]` is the [SlotKind.Hero]
 * when [hasHero] (entry -1), otherwise a [SlotKind.Wide] taking `supporting[0]`
 * at the same span; build the spec with the same [hasHero] so that span is
 * never 3. Span 2 or 3 → Wide, span 1 → Small.
 *
 * Emission stops when [supportingCount] runs out, so the bento degrades from
 * the bottom: strips go first, then row 2's tail. Remaining cards keep their
 * spans and unit lines (nothing stretches into the gap), and a row with no
 * emitted slot simply disappears. Phone / PhoneNarrow / Landscape return an
 * empty list — the UI keeps those branches as code.
 */
internal fun activityUnitSlots(
    spec: ActivityBentoSpec,
    hasHero: Boolean,
    supportingCount: Int,
): List<ActivitySlot> {
    if (spec.recipe != BentoRecipe.Units) return emptyList()
    val slots = mutableListOf<ActivitySlot>()
    var nextSupporting = 0
    for ((rowIndex, spans) in listOf(spec.row1, spec.row2).withIndex()) {
        var start = 0
        for ((index, span) in spans.withIndex()) {
            if (rowIndex == 0 && index == 0 && hasHero) {
                slots += ActivitySlot(row = 0, startUnit = 0, span = span, kind = SlotKind.Hero, entryIndex = -1)
            } else {
                if (nextSupporting >= supportingCount) return slots
                val kind = if (span >= 2) SlotKind.Wide else SlotKind.Small
                slots += ActivitySlot(rowIndex, start, span, kind, entryIndex = nextSupporting++)
            }
            start += span
        }
    }
    for (strip in 0 until spec.strips) {
        if (nextSupporting >= supportingCount) return slots
        // span 1 = one equal share of the strip row, not one unit column.
        slots += ActivitySlot(
            row = 2,
            startUnit = strip,
            span = 1,
            kind = SlotKind.Strip,
            entryIndex = nextSupporting++,
        )
    }
    return slots
}

/**
 * The bento's layout seed from a stable key of its data (null → 0). Uses
 * [String.hashCode], whose formula (`s[0]·31^(n−1) + … + s[n−1]`) is fixed by
 * the Java spec and therefore identical across processes, launches and
 * devices; masked non-negative so `seed % size` is a valid index.
 */
internal fun activityLayoutSeed(key: String?): Int = (key?.hashCode() ?: 0) and Int.MAX_VALUE

// ---- Jump Back In ----

/**
 * Jump Back In's grid for one container.
 *
 * @property columns the grid's column count.
 * @property followColumn true → covers follow the column (`jbiCoverSide`);
 *   false → the phone Row + weights + fixed 100dp cover path, byte for byte.
 * @property rows the templated grid's row count (HomeJbiTemplate.kt); 0 on
 *   the phone paths, which pack the 12-cell shelf row by row.
 * @property templated true → a seeded 2×2 / 1×2 / 2×1 / 1×1 template
 *   (`jbiLayout`); false → `packWidgetRows`.
 */
internal data class JbiGridSpec(
    val columns: Int,
    val followColumn: Boolean,
    val rows: Int = 0,
    val templated: Boolean = false,
)

/**
 * Compact height → (6, Row path); N ≤ 2 → (3, Row path) — both the phone's
 * approved compositions, unchanged. N ≥ 3 → templated on
 * [coverColumns] (`YoinWindowInfo.feedCoverColumns`, 3–10 phone-sized
 * columns) × [jbiTemplateRows] rows.
 */
internal fun jbiGridSpec(feedUnits: Int, coverColumns: Int, isCompactHeight: Boolean): JbiGridSpec = when {
    isCompactHeight -> JbiGridSpec(columns = 6, followColumn = false)
    feedUnits <= 2 -> JbiGridSpec(columns = 3, followColumn = false)
    else -> JbiGridSpec(
        columns = coverColumns,
        followColumn = true,
        rows = jbiTemplateRows(coverColumns),
        templated = true,
    )
}

/**
 * The pre-template mapping, kept for the trial's before shot: N = 3 → 3
 * columns, 4 → 4, ≥ 5 → 6, covers following the column up to 160dp.
 */
internal fun legacyJbiGridSpec(feedUnits: Int, isCompactHeight: Boolean): JbiGridSpec = when {
    isCompactHeight -> JbiGridSpec(columns = 6, followColumn = false)
    feedUnits <= 2 -> JbiGridSpec(columns = 3, followColumn = false)
    feedUnits == 3 -> JbiGridSpec(columns = 3, followColumn = true)
    feedUnits == 4 -> JbiGridSpec(columns = 4, followColumn = true)
    else -> JbiGridSpec(columns = 6, followColumn = true)
}

/**
 * Cells per lane of a K-lane template: 3 lanes stack four (the phone's
 * shelf), 4–5 lanes three, wider panes two — about 12–20 covers either way,
 * the offsets adding up to half a row of height.
 */
internal fun jbiTemplateRows(columns: Int): Int = when {
    columns <= JBI_FOUR_ROW_MAX_COLUMNS -> 4
    columns <= JBI_THREE_ROW_MAX_COLUMNS -> 3
    else -> 2
}

private const val JBI_FOUR_ROW_MAX_COLUMNS = 3
private const val JBI_THREE_ROW_MAX_COLUMNS = 5

// ---- Recently Added ----

/** The track grid's ceiling: the landscape phone's already-approved width. */
internal val RecentlyAddedGridMax = 340.dp

/** The track grid's floor: exactly the 360dp phone's grid (C = 328), so narrower panes never squeeze below it. */
internal val RecentlyAddedGridMin = ((360.dp - 32.dp) - 14.dp) * (2.6f / 3.6f)

/**
 * The Recently Added track grid's width for content width [contentWidth]
 * (C). Compact height (landscape phone): `min((C − 14) × 0.45, 340)`, today's
 * formula verbatim. Otherwise `clamp((C − 14) × 2.6/3.6, Min, Max)` — only C,
 * never N, so it is continuous across every breakpoint. The `2.6f / 3.6f`
 * literal is kept so phones (C 328–484) are bit-identical to today.
 */
internal fun recentlyAddedGridWidth(contentWidth: Dp, isCompactHeight: Boolean): Dp = if (isCompactHeight) {
    minOf((contentWidth - 14.dp) * 0.45f, RecentlyAddedGridMax)
} else {
    ((contentWidth - 14.dp) * (2.6f / 3.6f)).coerceIn(RecentlyAddedGridMin, RecentlyAddedGridMax)
}

// ---- Rediscover ----

/** The phone shelf's next card peeks past the edge: each card is this share of the content width. */
private const val RediscoverShelfCardFraction = 0.86f

/** The most Rediscover cards that sit side by side; only the phone shelf shows more. */
private const val RediscoverMaxSideBySide = 3

/**
 * How many Rediscover cards show: the phone (N ≤ 2) scrolls a shelf of the
 * whole selection ([REDISCOVER_LIMIT]); the landscape phone and N 3–4 seat 2
 * side by side, N ≥ 5 seats 3.
 */
internal fun rediscoverVisibleCount(units: Int, landscapePhone: Boolean): Int = when {
    landscapePhone -> 2
    units <= 2 -> REDISCOVER_LIMIT
    units <= 4 -> 2
    else -> RediscoverMaxSideBySide
}

/** True for the phone's scrolling shelf (more cards than ever sit side by side). */
internal fun isRediscoverShelf(visibleCount: Int): Boolean = visibleCount > RediscoverMaxSideBySide

/**
 * One Rediscover card's width for content width [contentWidth]. The phone
 * shelf peeks the next card (0.86 × content); a lone card there fills the
 * content and can't scroll. Side by side, [visibleCount] cards split the
 * content evenly whatever [itemCount] is, so a card never grows when there
 * are fewer of them.
 */
internal fun rediscoverCardWidth(contentWidth: Dp, visibleCount: Int, itemCount: Int, gap: Dp): Dp = when {
    !isRediscoverShelf(visibleCount) -> (contentWidth - gap * (visibleCount - 1)) / visibleCount
    itemCount <= 1 -> contentWidth
    else -> contentWidth * RediscoverShelfCardFraction
}.coerceAtLeast(0.dp)
