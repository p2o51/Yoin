package com.gpo.yoin.ui.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Row presets (D1, owner 2026-10-05): Activities and Jump Back In each come in
// four presets — S, M, L (the composition from before presets existed) and XL —
// and every screen turns a preset into its own composition here. Everything is
// pure and pinned by HomeRowPresetsTest; the L of every screen is today's
// composition byte for byte. Two presets that resolve to the same composition
// on a screen (the landscape phone's S / M / L bento, a wide pane's S and M
// rows) are one stop of that screen's ladder: the handle has one detent there
// and the stored preset is kept.
//
// The presets of a section are nested: each one seats every card of the one
// below it (a card may move or change kind — a strip growing into a wide card
// — but never disappears on the way up). The edit-mode resize interpolates
// between neighbouring stops on exactly that property.

/**
 * One detent of a section's ladder on this screen: the [presets] that resolve
 * to [payload] (smallest first), how many rows it shows ([rowCount], for
 * TalkBack and the label) and the composition itself.
 */
@Immutable
internal data class HomeRowStop<T>(
    val presets: List<HomeRowPreset>,
    val rowCount: Int,
    val payload: T,
) {
    /** The preset to store for this stop, coming from [from] (see [presetFrom]). */
    fun presetFrom(from: HomeRowPreset): HomeRowPreset = presets.presetFrom(from)
}

/**
 * The preset to store for a stop standing for these presets, coming from
 * [from]: [from] itself when it is one of them (the stored choice is kept),
 * else L, else the one nearest [from].
 */
internal fun List<HomeRowPreset>.presetFrom(from: HomeRowPreset): HomeRowPreset = when {
    from in this -> from
    HomeRowPreset.Default in this -> HomeRowPreset.Default
    else -> minBy { kotlin.math.abs(it.ordinal - from.ordinal) }
}

/**
 * The ladder for one section on one screen: every preset resolved by
 * [resolve] (composition, row count), consecutive presets with an equal
 * composition merged into one stop.
 */
internal fun <T> homeRowLadder(resolve: (HomeRowPreset) -> Pair<T, Int>): List<HomeRowStop<T>> {
    val stops = mutableListOf<HomeRowStop<T>>()
    for (preset in HomeRowPreset.entries) {
        val (payload, rows) = resolve(preset)
        val last = stops.lastOrNull()
        if (last != null && last.payload == payload) {
            stops[stops.lastIndex] = last.copy(presets = last.presets + preset)
        } else {
            stops += HomeRowStop(listOf(preset), rows, payload)
        }
    }
    return stops
}

/** The stop of [ladder] that [preset] resolves to (the first, for a preset not on it). */
internal fun <T> List<HomeRowStop<T>>.stopIndexOf(preset: HomeRowPreset): Int =
    indexOfFirst { preset in it.presets }.coerceAtLeast(0)

// ---- Activities ----

/**
 * The XL bento's extra unit row (between row 2 and the strips) for [units]
 * columns under [row2]: of every way to split [units] into smalls (1) and
 * wides (2), the one whose interior seams meet row 2's the fewest times, then
 * the one with the fewest cards (wides first), then the one leading with the
 * widest card — so it reads as row 2's mirror: N = 3 `[2, 1]` under `[1, 2]`,
 * N = 4 `[2, 2]` under `[1, 2, 1]`, N = 5 `[2, 2, 1]`. Deterministic.
 */
internal fun activityExtraRow(units: Int, row2: List<Int>): List<Int> {
    if (units <= 1) return listOf(1)
    val below = interiorSeams(row2)
    return compositionsOf(units)
        .sortedWith(
            compareBy<List<Int>>(
                { row -> interiorSeams(row).count { it in below } },
                { row -> row.size },
            ).thenComparator { a, b -> compareLexicographicDescending(a, b) },
        )
        .first()
}

private fun interiorSeams(spans: List<Int>): Set<Int> = spans.runningReduce(Int::plus).dropLast(1).toSet()

/** Every ordered split of [n] into 1s and 2s. */
private fun compositionsOf(n: Int): List<List<Int>> = when {
    n == 0 -> listOf(emptyList())
    n < 0 -> emptyList()
    else -> compositionsOf(n - 2).map { listOf(2) + it } + compositionsOf(n - 1).map { listOf(1) + it }
}

private fun compareLexicographicDescending(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        if (a[i] != b[i]) return b[i] - a[i]
    }
    return a.size - b.size
}

/** How a row of the code-built bento (Phone, PhoneNarrow, Landscape) lays out. */
internal enum class ActivityRowShape {
    /** The hero alone, full width, at its own height. */
    Hero,

    /** Cards side by side by weight, at [ActivityCodeRow.height] × fontScale. */
    Cards,

    /** One strip, full width, at its own height. */
    Strip,
}

/**
 * One card of a code-built row: its [kind], its share of the row ([weight])
 * and its entry ([entryIndex] into the supporting list; -1 = the hero). An
 * entry past the supply is a spacer of the same weight.
 */
@Immutable
internal data class ActivityCodeSlot(val kind: SlotKind, val weight: Float, val entryIndex: Int)

/** One row of the code-built bento. [height] and [gap] only mean something for [ActivityRowShape.Cards]. */
@Immutable
internal data class ActivityCodeRow(
    val shape: ActivityRowShape,
    val slots: List<ActivityCodeSlot>,
    val height: Dp = 0.dp,
    val gap: Dp = 0.dp,
)

/** The phone rows' height (× fontScale) and gap; the landscape row's. */
internal val ActivityPhoneRowHeight = 118.dp
internal val ActivityPhoneRowGap = 10.dp
internal val ActivityLandscapeRowHeight = 124.dp
internal val ActivityLandscapeRowGap = 12.dp

/**
 * The Phone / PhoneNarrow / Landscape bento at [preset], with [supportingCount]
 * supporting entries on offer. Phone: S = the hero, M = + small | wide,
 * L = + one strip (today), XL = + a mirrored wide | small before the strip, so
 * L's strip grows into XL's wide card. PhoneNarrow: the same with two equal
 * smalls per row. Landscape: S = M = L = today's one row (hero 2 : small 1 :
 * wide 1.4 — the first screen keeps Recently Added in view), XL a second row
 * (wide 1.4 : small 1 : small 1). Without a hero, S is the first card row.
 * Entries go out in reading order; a row whose first entry is past the supply
 * is dropped, a later one becomes a spacer — today's degrade.
 */
internal fun activityCodeRows(
    recipe: BentoRecipe,
    preset: HomeRowPreset,
    hasHero: Boolean,
    supportingCount: Int,
): List<ActivityCodeRow> {
    var next = 0
    fun slot(kind: SlotKind, weight: Float) = ActivityCodeSlot(kind, weight, next++)
    val rows = mutableListOf<ActivityCodeRow>()
    when (recipe) {
        BentoRecipe.Landscape -> {
            val first = buildList {
                if (hasHero) add(ActivityCodeSlot(SlotKind.Hero, 2f, -1))
                add(slot(SlotKind.Small, 1f))
                add(slot(SlotKind.Wide, 1.4f))
            }
            rows += ActivityCodeRow(ActivityRowShape.Cards, first, ActivityLandscapeRowHeight, ActivityLandscapeRowGap)
            if (preset == HomeRowPreset.XL) {
                val second = listOf(slot(SlotKind.Wide, 1.4f), slot(SlotKind.Small, 1f), slot(SlotKind.Small, 1f))
                if (second.first().entryIndex < supportingCount) {
                    rows += ActivityCodeRow(
                        ActivityRowShape.Cards,
                        second,
                        ActivityLandscapeRowHeight,
                        ActivityLandscapeRowGap,
                    )
                }
            }
        }
        BentoRecipe.Phone, BentoRecipe.PhoneNarrow -> {
            val narrow = recipe == BentoRecipe.PhoneNarrow
            if (hasHero) rows += ActivityCodeRow(ActivityRowShape.Hero, listOf(ActivityCodeSlot(SlotKind.Hero, 1f, -1)))
            fun cardRow(slots: List<ActivityCodeSlot>) {
                if (slots.first().entryIndex < supportingCount) {
                    rows += ActivityCodeRow(ActivityRowShape.Cards, slots, ActivityPhoneRowHeight, ActivityPhoneRowGap)
                }
            }
            if (preset == HomeRowPreset.S && hasHero) return rows
            cardRow(
                if (narrow) {
                    listOf(slot(SlotKind.Small, 1f), slot(SlotKind.Small, 1f))
                } else {
                    listOf(slot(SlotKind.Small, 1f), slot(SlotKind.Wide, 2f))
                },
            )
            if (preset <= HomeRowPreset.M) return rows
            if (preset == HomeRowPreset.XL) {
                cardRow(
                    if (narrow) {
                        listOf(slot(SlotKind.Small, 1f), slot(SlotKind.Small, 1f))
                    } else {
                        listOf(slot(SlotKind.Wide, 2f), slot(SlotKind.Small, 1f))
                    },
                )
            }
            val strip = slot(SlotKind.Strip, 1f)
            if (strip.entryIndex < supportingCount) rows += ActivityCodeRow(ActivityRowShape.Strip, listOf(strip))
        }
        BentoRecipe.Units -> Unit
    }
    return rows
}

/** Supporting entries [preset] seats on [spec] (every slot but the hero's, or every slot without one). */
internal fun activityPresetSupportingCount(spec: ActivityBentoSpec, hasHero: Boolean, preset: HomeRowPreset): Int =
    when (spec.recipe) {
        BentoRecipe.Units -> activityUnitSlots(spec, hasHero, supportingCount = Int.MAX_VALUE, preset = preset)
            .count { it.kind != SlotKind.Hero }
        else -> activityCodeRows(spec.recipe, preset, hasHero, supportingCount = Int.MAX_VALUE)
            .sumOf { row -> row.slots.count { it.entryIndex >= 0 } }
    }

/**
 * One Activities composition: the code-built [rows] or the unit [slots]
 * (the other empty), and how many supporting entries it seats.
 */
@Immutable
internal data class ActivityPresetLayout(
    val rows: List<ActivityCodeRow>,
    val slots: List<ActivitySlot>,
    val seated: Int,
)

/**
 * The Activities ladder on one screen ([spec], from activityBentoSpec) with
 * [supportingCount] supporting entries on offer. Presets the supply can't
 * tell apart merge, so a short history gets fewer detents.
 */
internal fun activityRowLadder(
    spec: ActivityBentoSpec,
    hasHero: Boolean,
    supportingCount: Int,
): List<HomeRowStop<ActivityPresetLayout>> = homeRowLadder { preset ->
    val seated = minOf(supportingCount, activityPresetSupportingCount(spec, hasHero, preset))
    if (spec.recipe == BentoRecipe.Units) {
        val slots = activityUnitSlots(spec, hasHero, seated, preset)
        ActivityPresetLayout(emptyList(), slots, seated) to (slots.maxOfOrNull { it.row }?.plus(1) ?: 0)
    } else {
        val rows = activityCodeRows(spec.recipe, preset, hasHero, seated)
        ActivityPresetLayout(rows, emptyList(), seated) to rows.size
    }
}

// ---- Jump Back In ----

/**
 * Rows of Jump Back In at [preset] on [spec]. The phone's packed shelf
 * (3 columns): 2 / 3 / 4 (today) / 6. The landscape phone's (6 columns):
 * 1 / 1 / 2 (today) / 3. A templated pane of L = jbiTemplateRows rows:
 * L − 2, L − 1, L, L + 1, never under 1 (so a 2-row wide pane has S = M).
 * The pre-template column grid (a debug trial) has no presets.
 */
internal fun jbiPresetRows(spec: JbiGridSpec, preset: HomeRowPreset): Int = when {
    spec.templated -> when (preset) {
        HomeRowPreset.S -> spec.rows - 2
        HomeRowPreset.M -> spec.rows - 1
        HomeRowPreset.L -> spec.rows
        HomeRowPreset.XL -> spec.rows + 1
    }.coerceAtLeast(1)
    spec.followColumn -> JbiPhoneShelfRows
    spec.columns >= JbiLandscapeColumns -> when (preset) {
        HomeRowPreset.S, HomeRowPreset.M -> 1
        HomeRowPreset.L -> 2
        HomeRowPreset.XL -> 3
    }
    else -> when (preset) {
        HomeRowPreset.S -> 2
        HomeRowPreset.M -> 3
        HomeRowPreset.L -> JbiPhoneShelfRows
        HomeRowPreset.XL -> 6
    }
}

/**
 * Signal cards (rating / note) a [rows]-row grid of [cells] cells takes: one
 * on a single row or under nine cells — two would leave only a couple of
 * covers and turn the shelf into a memories corner — else two.
 */
internal fun jbiSignalLimit(rows: Int, cells: Int): Int = if (rows <= 1 || cells < JbiTwoSignalMinCells) 1 else 2

/**
 * The cells of the packed shelf at [preset]: rows × columns (12 on the phone
 * and the landscape phone at L, as before). The column grid a pane falls back
 * to (too few cards for its template, or the pre-template trial) keeps its 12
 * at every preset.
 */
internal fun jbiPackedCells(spec: JbiGridSpec, preset: HomeRowPreset): Int =
    if (spec.followColumn) JbiPhoneShelfCells else jbiPresetRows(spec, preset) * spec.columns

/** One Jump Back In composition: the packed shelf's rows, or a seated template. */
internal sealed interface JbiPresetLayout {
    /** The phone / landscape shelf ([packWidgetRows]), or the pre-template column grid. */
    @Immutable
    data class Packed(val rows: List<List<HomeWidgetCard>>) : JbiPresetLayout

    /** A templated pane (HomeJbiTemplate.kt). */
    @Immutable
    data class Template(val layout: JbiLayout) : JbiPresetLayout
}

/**
 * Jump Back In's ladder on one screen: [base] (the L template) when the pane
 * is templated and the shelf filled it, else the packed shelves.
 */
internal fun jbiRowLadder(
    spec: JbiGridSpec,
    cards: List<HomeWidgetCard>,
    base: JbiLayout?,
): List<HomeRowStop<JbiPresetLayout>> = if (base != null) {
    jbiTemplateLadder(spec, base, cards).map { stop ->
        HomeRowStop(stop.presets, stop.rowCount, JbiPresetLayout.Template(stop.payload))
    }
} else {
    jbiPackedLadder(spec, cards).map { stop ->
        HomeRowStop(stop.presets, stop.rowCount, JbiPresetLayout.Packed(stop.payload))
    }
}

/** The packed paths' ladder: the phone / landscape shelves at each preset, packed by [packWidgetRows]. */
internal fun jbiPackedLadder(
    spec: JbiGridSpec,
    cards: List<HomeWidgetCard>,
): List<HomeRowStop<List<List<HomeWidgetCard>>>> = homeRowLadder { preset ->
    val cells = jbiPackedCells(spec, preset)
    val rows = if (spec.followColumn) JbiPhoneShelfRows else jbiPresetRows(spec, preset)
    val shelf = trimToPhoneShelf(cards, cells = cells, maxSignals = jbiSignalLimit(rows, cells))
    val packed = packWidgetRows(shelf, spec.columns)
    packed to packed.size
}

/**
 * The templated ladder: [base] (the L template, jbiLayout as before) grown or
 * shrunk to each preset's rows by [jbiPresetLayout] — L → XL appends a row
 * of covers, L → M → S each trim the bottom row of the one above, so every
 * stop seats the cards of the one below it.
 */
internal fun jbiTemplateLadder(
    spec: JbiGridSpec,
    base: JbiLayout,
    cards: List<HomeWidgetCard>,
): List<HomeRowStop<JbiLayout>> {
    val l = base
    val m = jbiPresetLayout(l, cards, jbiPresetRows(spec, HomeRowPreset.M))
    val s = jbiPresetLayout(m, cards, jbiPresetRows(spec, HomeRowPreset.S))
    val xl = jbiPresetLayout(l, cards, jbiPresetRows(spec, HomeRowPreset.XL))
    val byPreset = mapOf(HomeRowPreset.S to s, HomeRowPreset.M to m, HomeRowPreset.L to l, HomeRowPreset.XL to xl)
    return homeRowLadder { preset ->
        val layout = byPreset.getValue(preset)
        layout to layout.template.rows
    }
}

/** The phone's packed shelf rows and cells at L — today's 3 × 4. */
internal const val JbiPhoneShelfRows = 4
internal const val JbiPhoneShelfCells = 12
private const val JbiLandscapeColumns = 6
private const val JbiTwoSignalMinCells = 9
