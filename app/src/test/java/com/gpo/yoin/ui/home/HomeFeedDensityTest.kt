package com.gpo.yoin.ui.home

import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.feedUnitsFor
import org.junit.Test

/**
 * Pure-function tests for Home feed density ([activityBentoSpec],
 * [activityUnitSlots], [activityLayoutSeed], [jbiGridSpec],
 * [recentlyAddedGridWidth], [rediscoverVisibleCount], [rediscoverCardWidth]).
 * `feedUnits` itself (width → N) is pinned by FeedUnitsTest; here N is an input.
 */
class HomeFeedDensityTest {

    // The Units palette, copied by hand so a silent edit to the table fails
    // here: option order is part of the contract (`options[seed % size]`).
    private val palette: Map<Int, List<Pair<List<Int>, List<Int>>>> = mapOf(
        3 to listOf(listOf(2, 1) to listOf(1, 2)),
        4 to listOf(listOf(2, 2) to listOf(1, 2, 1)),
        5 to listOf(
            listOf(2, 2, 1) to listOf(1, 2, 2),
            listOf(2, 2, 1) to listOf(2, 1, 2),
        ),
        6 to listOf(
            listOf(2, 2, 1, 1) to listOf(1, 2, 2, 1),
            listOf(2, 2, 2) to listOf(1, 1, 1, 2, 1),
            listOf(2, 2, 2) to listOf(1, 2, 1, 1, 1),
        ),
        7 to listOf(
            listOf(2, 2, 2, 1) to listOf(1, 2, 1, 1, 2),
            listOf(2, 2, 2, 1) to listOf(1, 2, 2, 1, 1),
            listOf(3, 2, 1, 1) to listOf(1, 1, 2, 1, 2),
            listOf(3, 2, 1, 1) to listOf(1, 1, 2, 2, 1),
        ),
        8 to listOf(
            listOf(2, 2, 2, 1, 1) to listOf(1, 2, 2, 1, 2),
            listOf(2, 2, 2, 2) to listOf(1, 2, 1, 1, 2, 1),
            listOf(3, 2, 2, 1) to listOf(1, 1, 2, 1, 1, 2),
            listOf(3, 2, 2, 1) to listOf(1, 1, 2, 2, 1, 1),
        ),
    )
    private val strips = mapOf(3 to 1, 4 to 2, 5 to 2, 6 to 2, 7 to 3, 8 to 3)

    /** The owner's recommended item counts per N (hero included). */
    private val ownerItemCounts = mapOf(3 to 5, 4 to 7, 5 to 8, 6 to 10, 7 to 12, 8 to 13)

    /** The bento's gap between unit columns (and between strips). */
    private val unitGap = 10f

    /** Every palette option for [units], resolved through the real spec, in palette order. */
    private fun everyOption(units: Int): List<ActivityBentoSpec> =
        palette.getValue(units).indices.map { seed -> activityBentoSpec(units, isCompactHeight = false, seed = seed) }

    /** Every option for every Units N. */
    private fun everyUnitsSpec(): List<ActivityBentoSpec> = (3..8).flatMap(::everyOption)

    /** Interior seams of a row: the unit lines between its cards, excluding 0 and N. */
    private fun seams(spans: List<Int>): Set<Int> = spans.runningReduce(Int::plus).dropLast(1).toSet()

    // ---- Activities: recipes ----

    @Test
    fun should_keepPhoneRecipe_when_unitsTwo() {
        for (seed in 0..20) {
            for (hasHero in listOf(true, false)) {
                val spec = activityBentoSpec(2, isCompactHeight = false, seed = seed, hasHero = hasHero)
                assertEquals(BentoRecipe.Phone, spec.recipe)
                assertEquals(4, spec.totalSlots)
                assertEquals(3, spec.supportingSlots)
                assertEquals(0f, spec.row1Height.value, 0f)
                assertTrue(spec.row1.isEmpty() && spec.row2.isEmpty() && spec.strips == 0)
                assertTrue(activityUnitSlots(spec, hasHero, supportingCount = 12).isEmpty())
            }
        }
    }

    @Test
    fun should_useLandscape_when_compactHeight() {
        for (units in 1..8) {
            for (seed in 0..5) {
                val spec = activityBentoSpec(units, isCompactHeight = true, seed = seed)
                assertEquals(BentoRecipe.Landscape, spec.recipe)
                // Units-free: the same row at any N, so no crossfade key change.
                assertEquals(0, spec.units)
                assertEquals(3, spec.totalSlots)
                assertEquals(2, spec.supportingSlots)
                assertEquals(124f, spec.row1Height.value, 0f)
                assertTrue(activityUnitSlots(spec, hasHero = true, supportingCount = 12).isEmpty())
            }
        }
    }

    @Test
    fun should_useTwoSmalls_when_unitsOne() {
        val spec = activityBentoSpec(1, isCompactHeight = false, seed = 7)
        assertEquals(BentoRecipe.PhoneNarrow, spec.recipe)
        // hero row + small | small + one strip.
        assertEquals(4, spec.totalSlots)
        assertEquals(3, spec.supportingSlots)
        assertEquals(0f, spec.row1Height.value, 0f)
        assertTrue(activityUnitSlots(spec, hasHero = true, supportingCount = 12).isEmpty())
        // Anything below 1 clamps to 1.
        assertEquals(spec, activityBentoSpec(0, isCompactHeight = false, seed = 7))
        assertEquals(spec, activityBentoSpec(-3, isCompactHeight = false, seed = 7))
    }

    @Test
    fun should_clampToEight_when_unitsAboveRange() {
        for (seed in 0..7) {
            val spec = activityBentoSpec(12, isCompactHeight = false, seed = seed)
            assertEquals(8, spec.units)
            assertEquals(activityBentoSpec(8, isCompactHeight = false, seed = seed), spec)
        }
    }

    @Test
    fun should_pickPaletteOptionBySeed_when_units() {
        for ((units, options) in palette) {
            for (seed in 0 until options.size * 3) {
                val spec = activityBentoSpec(units, isCompactHeight = false, seed = seed)
                val (row1, row2) = options[seed % options.size]
                assertEquals(BentoRecipe.Units, spec.recipe)
                assertEquals(units, spec.units)
                assertEquals("N=$units seed=$seed row1", row1, spec.row1)
                assertEquals("N=$units seed=$seed row2", row2, spec.row2)
                assertEquals(strips.getValue(units), spec.strips)
            }
        }
    }

    @Test
    fun should_raiseFirstRow_when_unitsAtLeastFive() {
        for (units in 3..4) assertEquals(124f, everyOption(units).first().row1Height.value, 0f)
        for (units in 5..8) {
            everyOption(units).forEach { assertEquals(128f, it.row1Height.value, 0f) }
        }
    }

    // ---- Activities: palette invariants ----

    @Test
    fun should_matchOwnerItemCounts_when_unitsThreeToEight() {
        for (units in 3..8) {
            for (spec in everyOption(units)) {
                assertEquals("N=$units ${spec.row1}/${spec.row2}", ownerItemCounts.getValue(units), spec.totalSlots)
                assertEquals(spec.totalSlots - 1, spec.supportingSlots)
            }
        }
    }

    @Test
    fun should_fillEveryRowExactly_when_units() {
        for (spec in everyUnitsSpec()) {
            val label = "N=${spec.units} ${spec.row1}/${spec.row2}"
            assertEquals(label, spec.units, spec.row1.sum())
            assertEquals(label, spec.units, spec.row2.sum())
            // Row 1 tapers: hero ≥ wide ≥ … ≥ small, never growing again.
            assertTrue(label, spec.row1.zipWithNext().all { (a, b) -> a >= b })
            assertTrue(label, spec.row1.first() in 2..3)
            assertTrue(label, spec.row1.drop(1).all { it in 1..2 })
            assertTrue(label, spec.row2.all { it in 1..2 })
        }
    }

    @Test
    fun should_keepRowsStaggered_when_units() {
        for (spec in everyUnitsSpec()) {
            val label = "N=${spec.units} ${spec.row1}/${spec.row2}"
            val shared = seams(spec.row1) intersect seams(spec.row2)
            assertTrue(label, shared.size <= 1)
            if (spec.units <= 5) {
                if (spec.row1 == listOf(2, 2, 1) && spec.row2 == listOf(2, 1, 2)) {
                    // The documented exception: both rows break at unit 2.
                    assertEquals(label, setOf(2), shared)
                } else {
                    assertTrue(label, shared.isEmpty())
                }
            }
        }
    }

    @Test
    fun should_beDeterministic_when_sameSeed() {
        // String.hashCode's formula is fixed by the Java spec: "abc" is 96354 everywhere.
        assertEquals(96354, activityLayoutSeed("abc"))
        assertEquals(0, activityLayoutSeed(null))
        // A key whose hashCode is Int.MIN_VALUE still yields a usable, non-negative seed.
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        assertEquals(0, activityLayoutSeed("polygenelubricants"))
        val keys = listOf("album:al-42", "playlist:pl-7", "song:tr-9001", "", "活动", null)
        for (key in keys) {
            val seed = activityLayoutSeed(key)
            assertTrue(seed >= 0)
            assertEquals(seed, activityLayoutSeed(key))
            for (units in 1..8) {
                for (hasHero in listOf(true, false)) {
                    val a = activityBentoSpec(units, isCompactHeight = false, seed = seed, hasHero = hasHero)
                    val b = activityBentoSpec(units, isCompactHeight = false, seed = seed, hasHero = hasHero)
                    assertEquals(a, b)
                    assertEquals(activityUnitSlots(a, hasHero, 12), activityUnitSlots(b, hasHero, 12))
                }
            }
        }
        // A negative seed (caller skipped activityLayoutSeed) still lands on an option.
        assertEquals(BentoRecipe.Units, activityBentoSpec(8, isCompactHeight = false, seed = -1).recipe)
    }

    @Test
    fun should_varyComposition_when_seedsDiffer() {
        for (units in 5..8) {
            val compositions = (0 until 12).map { seed ->
                activityBentoSpec(units, isCompactHeight = false, seed = seed).let { it.row1 to it.row2 }
            }.toSet()
            assertEquals("N=$units", palette.getValue(units).size, compositions.size)
            assertTrue("N=$units", compositions.size > 1)
        }
        // From N = 7 the hero's own width varies: 2 or 3 units.
        for (units in 7..8) {
            val heroSpans = (0 until 12).map { activityBentoSpec(units, false, seed = it).row1.first() }.toSet()
            assertEquals(setOf(2, 3), heroSpans)
        }
    }

    @Test
    fun should_neverGiveWideThreeUnits_when_heroMissing() {
        for (units in 3..8) {
            val eligible = palette.getValue(units).filter { (row1, _) -> row1.first() == 2 }
            for (seed in 0..23) {
                val spec = activityBentoSpec(units, isCompactHeight = false, seed = seed, hasHero = false)
                assertEquals(2, spec.row1.first())
                // The seed picks among the eligible options only, in palette order.
                assertEquals(eligible[seed % eligible.size], spec.row1 to spec.row2)
                val slots = activityUnitSlots(spec, hasHero = false, supportingCount = spec.totalSlots)
                assertTrue(slots.none { it.kind == SlotKind.Wide && it.span >= 3 })
                assertTrue(slots.none { it.kind == SlotKind.Hero })
            }
        }
        // With a hero, N = 7 / 8 do reach the 3-unit options.
        assertEquals(3, activityBentoSpec(7, isCompactHeight = false, seed = 2, hasHero = true).row1.first())
        assertEquals(2, activityBentoSpec(7, isCompactHeight = false, seed = 2, hasHero = false).row1.first())
    }

    @Test
    fun should_keepCellsInBand_when_measuredForms() {
        // Content width C of each measured form, N derived the way the runtime
        // does it (feedUnitsFor): Compact 480, Compact 599 / detail column 600,
        // foldable 690, 1129 + NP, 753 / 800 portrait, 1280 + NP, desktop 1000,
        // 1129 landscape, 1280 landscape, and a 1440dp desktop window.
        val forms = listOf(
            448 to LayoutMode.Compact, 567 to LayoutMode.Compact, 568 to LayoutMode.Medium,
            658 to LayoutMode.Medium, 677 to LayoutMode.Medium, 688 to LayoutMode.Medium,
            796 to LayoutMode.Wide, 936 to LayoutMode.Wide, 1065 to LayoutMode.Wide,
            1216 to LayoutMode.Wide, 1376 to LayoutMode.Wide,
        )
        for ((content, mode) in forms) {
            val units = feedUnitsFor(content.dp, mode)
            val unit = (content - unitGap * (units - 1)) / units
            assertTrue("C=$content N=$units u=$unit", unit in 130f..185f)
            for (spec in everyOption(units)) {
                for (row in listOf(spec.row1, spec.row2)) {
                    val widths = row.map { span -> span * unit + (span - 1) * unitGap }
                    // The row fills C exactly, gaps included.
                    assertEquals(content.toFloat(), widths.sum() + unitGap * (row.size - 1), 0.01f)
                    row.zip(widths).forEach { (span, width) ->
                        val label = "C=$content ${spec.row1}/${spec.row2} span=$span width=$width"
                        when (span) {
                            1 -> assertTrue(label, width >= 120f)
                            2 -> assertTrue(label, width <= 380f)
                            // The owner took the wider 3-unit hero for the stagger:
                            // ~450 on the Pixel Tablet, ~510 on a 1440dp desktop window.
                            else -> assertTrue(label, width <= 520f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun should_seatEverySlot_when_heroMissing() {
        for (units in 3..8) {
            val spec = activityBentoSpec(units, isCompactHeight = false, seed = 0, hasHero = false)
            val seated = seatedSupportingCount(spec, hasHero = false)
            assertEquals(spec.totalSlots, seated)
            // What the UI hands the walk fills the whole composition.
            assertEquals(spec.totalSlots, activityUnitSlots(spec, hasHero = false, supportingCount = seated).size)
        }
        val withHero = activityBentoSpec(4, isCompactHeight = false, seed = 0, hasHero = true)
        assertEquals(withHero.supportingSlots, seatedSupportingCount(withHero, hasHero = true))
    }

    @Test
    fun should_keepLandscapeSpecStable_when_unitsChange() {
        // The landscape row renders the same at any N; an unchanged spec means
        // no crossfade when a short window crosses a feed breakpoint.
        val specs = (1..8).map { activityBentoSpec(it, isCompactHeight = true, seed = it) }
        assertEquals(1, specs.toSet().size)
    }

    // ---- Activities: slot walk ----

    @Test
    fun should_placeOnUnitLines_when_walkingSlots() {
        for (spec in everyUnitsSpec()) {
            val label = "N=${spec.units} ${spec.row1}/${spec.row2}"
            val slots = activityUnitSlots(spec, hasHero = true, supportingCount = spec.supportingSlots)
            assertEquals(label, spec.totalSlots, slots.size)
            assertEquals(label, ActivitySlot(0, 0, spec.row1.first(), SlotKind.Hero, -1), slots.first())
            // Supporting entries are handed out in walk order, none skipped.
            assertEquals(label, (0 until spec.supportingSlots).toList(), slots.drop(1).map { it.entryIndex })
            for ((rowIndex, spans) in listOf(spec.row1, spec.row2).withIndex()) {
                val row = slots.filter { it.row == rowIndex }
                assertEquals(label, spans, row.map { it.span })
                // Each card starts on the unit line where the previous one ended; the last ends at N.
                assertEquals(label, listOf(0) + spans.runningReduce(Int::plus).dropLast(1), row.map { it.startUnit })
                assertEquals(label, spec.units, row.last().startUnit + row.last().span)
                row.drop(if (rowIndex == 0) 1 else 0).forEach { slot ->
                    assertEquals(label, if (slot.span == 1) SlotKind.Small else SlotKind.Wide, slot.kind)
                }
            }
            val stripRow = slots.filter { it.row == 2 }
            assertEquals(label, (0 until spec.strips).toList(), stripRow.map { it.startUnit })
            assertTrue(label, stripRow.all { it.span == 1 && it.kind == SlotKind.Strip })
        }
    }

    @Test
    fun should_degradeFromBottom_when_fewEntries() {
        // [2,2,2,1,1] / [1,2,2,1,2] + 3 strips: 12 supporting entries fill it.
        val spec = activityBentoSpec(8, isCompactHeight = false, seed = 0)
        assertEquals(listOf(2, 2, 2, 1, 1), spec.row1)
        val full = activityUnitSlots(spec, hasHero = true, supportingCount = 12)
        assertEquals(13, full.size)
        // More entries than slots change nothing.
        assertEquals(full, activityUnitSlots(spec, hasHero = true, supportingCount = 40))
        // Fewer entries only ever cut the tail: every card keeps its span and unit line.
        for (count in 0..12) {
            assertEquals("count=$count", full.take(count + 1), activityUnitSlots(spec, true, count))
        }
        // 11 → the last strip goes first.
        assertEquals(2, activityUnitSlots(spec, true, 11).count { it.row == 2 })
        // 9 → no strip row at all; rows 1 and 2 stay whole.
        activityUnitSlots(spec, true, 9).let { slots ->
            assertTrue(slots.none { it.row == 2 })
            assertEquals(spec.row2, slots.filter { it.row == 1 }.map { it.span })
        }
        // 6 → row 2 keeps its head [1,2] on units 0 and 1, the tail is gone (not stretched).
        activityUnitSlots(spec, true, 6).filter { it.row == 1 }.let { row2 ->
            assertEquals(listOf(0, 1), row2.map { it.startUnit })
            assertEquals(listOf(1, 2), row2.map { it.span })
        }
        // 4 → row 2 disappears; 0 → only the hero.
        assertTrue(activityUnitSlots(spec, true, 4).none { it.row == 1 })
        assertEquals(listOf(full.first()), activityUnitSlots(spec, true, 0))
        // No hero and nothing to show → no slots.
        val noHero = activityBentoSpec(8, isCompactHeight = false, seed = 0, hasHero = false)
        assertTrue(activityUnitSlots(noHero, hasHero = false, supportingCount = 0).isEmpty())
    }

    @Test
    fun should_startWithWideAtUnitZero_when_heroMissing() {
        for (units in 3..8) {
            for (seed in 0..11) {
                val spec = activityBentoSpec(units, isCompactHeight = false, seed = seed, hasHero = false)
                val slots = activityUnitSlots(spec, hasHero = false, supportingCount = spec.totalSlots)
                assertEquals(ActivitySlot(0, 0, 2, SlotKind.Wide, 0), slots.first())
                assertEquals(spec.totalSlots, slots.size)
                assertEquals((0 until spec.totalSlots).toList(), slots.map { it.entryIndex })
                // Same unit lines as with a hero — only the first card's kind and entry change.
                val withHero = activityUnitSlots(spec, hasHero = true, supportingCount = spec.supportingSlots)
                assertEquals(withHero.map { it.row to it.startUnit }, slots.map { it.row to it.startUnit })
            }
        }
    }

    // ---- Jump Back In ----

    @Test
    fun should_keepPhoneRowPath_when_unitsAtMostTwo() {
        for (units in listOf(0, 1, 2)) {
            assertEquals(
                JbiGridSpec(columns = 3, followColumn = false),
                jbiGridSpec(units, coverColumns = 3, isCompactHeight = false),
            )
        }
    }

    @Test
    fun should_keepSixColumnRowPath_when_compactHeight() {
        for (units in 1..8) {
            assertEquals(
                JbiGridSpec(columns = 6, followColumn = false),
                jbiGridSpec(units, coverColumns = 9, isCompactHeight = true),
            )
        }
    }

    @Test
    fun should_templateOnCoverColumns_when_unitsAtLeastThree() {
        for (units in 3..8) {
            for (columns in 3..10) {
                assertEquals(
                    JbiGridSpec(
                        columns = columns,
                        followColumn = true,
                        rows = jbiTemplateRows(columns),
                        templated = true,
                    ),
                    jbiGridSpec(units, coverColumns = columns, isCompactHeight = false),
                )
            }
        }
        assertEquals(4, jbiTemplateRows(3))
        assertEquals(3, jbiTemplateRows(5))
        assertEquals(2, jbiTemplateRows(6))
    }

    @Test
    fun should_keepPreTemplateColumns_when_legacySpec() {
        assertEquals(JbiGridSpec(columns = 3, followColumn = true), legacyJbiGridSpec(3, isCompactHeight = false))
        assertEquals(JbiGridSpec(columns = 4, followColumn = true), legacyJbiGridSpec(4, isCompactHeight = false))
        for (units in 5..8) {
            assertEquals(JbiGridSpec(columns = 6, followColumn = true), legacyJbiGridSpec(units, isCompactHeight = false))
        }
    }

    // ---- Recently Added ----

    @Test
    fun should_matchOldPhoneFormula_when_content328to484() {
        var content = 328f
        while (content <= 484f) {
            val old = (content.dp - 14.dp) * (2.6f / 3.6f)
            val new = recentlyAddedGridWidth(content.dp, isCompactHeight = false)
            assertTrue("C=$content old=$old new=$new", abs(new.value - old.value) < 0.01f)
            content += 0.25f
        }
        // Real phones (C = W − 32 for 360 / 376 / 393 / 400 / 411 / 412 / 430 / 479) are bit-identical.
        for (content in listOf(328, 344, 361, 368, 379, 380, 398, 447)) {
            val old = (content.dp - 14.dp) * (2.6f / 3.6f)
            assertEquals(old.value, recentlyAddedGridWidth(content.dp, isCompactHeight = false).value, 0f)
        }
    }

    @Test
    fun should_capAt340_when_contentAtLeast485() {
        for (content in listOf(485, 568, 688, 796, 1065, 1216, 2000)) {
            assertEquals(340f, recentlyAddedGridWidth(content.dp, isCompactHeight = false).value, 0f)
        }
        // 484 still sits just under the cap.
        assertTrue(recentlyAddedGridWidth(484.dp, isCompactHeight = false) < RecentlyAddedGridMax)
    }

    @Test
    fun should_floorAt360PhoneGrid_when_contentBelow328() {
        // (360 − 32 − 14) × 2.6 / 3.6 ≈ 226.78: the 360dp phone's grid.
        assertEquals(226.78f, RecentlyAddedGridMin.value, 0.01f)
        assertEquals(
            recentlyAddedGridWidth(328.dp, isCompactHeight = false).value,
            RecentlyAddedGridMin.value,
            0f,
        )
        for (content in listOf(0f, 200f, 298f, 327.9f)) {
            assertEquals(
                RecentlyAddedGridMin.value,
                recentlyAddedGridWidth(content.dp, isCompactHeight = false).value,
                0f,
            )
        }
    }

    @Test
    fun should_keepLandscapeFormula_when_compactHeight() {
        for (content in listOf(300f, 500f, 700f, 755f, 782f, 900f, 1400f)) {
            val old = minOf((content.dp - 14.dp) * 0.45f, 340.dp)
            assertEquals(old.value, recentlyAddedGridWidth(content.dp, isCompactHeight = true).value, 0f)
        }
        // No phone floor in landscape: a 300dp row gives 128.7dp, below RecentlyAddedGridMin.
        assertEquals(128.7f, recentlyAddedGridWidth(300.dp, isCompactHeight = true).value, 0.01f)
    }

    @Test
    fun should_beContinuous_when_contentSweeps() {
        val step = 0.5f
        val maxSlope = step * (2.6f / 3.6f) + 0.001f
        var content = 200f
        var previous = recentlyAddedGridWidth(content.dp, isCompactHeight = false).value
        while (content < 2000f) {
            content += step
            val current = recentlyAddedGridWidth(content.dp, isCompactHeight = false).value
            // Never jumps, never shrinks as the pane widens.
            assertTrue("C=$content", current - previous in 0f..maxSlope)
            previous = current
        }
    }

    // ---- Rediscover ----

    @Test
    fun should_showSixInShelf_when_compact() {
        for (units in 1..2) {
            assertEquals(6, rediscoverVisibleCount(units, landscapePhone = false))
            assertTrue(isRediscoverShelf(rediscoverVisibleCount(units, landscapePhone = false)))
        }
    }

    @Test
    fun should_showTwo_when_threeUnits() {
        assertEquals(2, rediscoverVisibleCount(3, landscapePhone = false))
    }

    @Test
    fun should_showTwo_when_medium() {
        // Medium / Tabletop panes run N 3–4 (the tablet portrait is 4).
        for (units in 3..4) {
            assertEquals(2, rediscoverVisibleCount(units, landscapePhone = false))
            assertFalse(isRediscoverShelf(rediscoverVisibleCount(units, landscapePhone = false)))
        }
    }

    @Test
    fun should_showThree_when_wide() {
        for (units in 5..8) {
            assertEquals(3, rediscoverVisibleCount(units, landscapePhone = false))
        }
    }

    @Test
    fun should_showTwo_when_landscapePhone() {
        // A short window side by side, whatever its width reads as.
        for (units in 1..8) {
            assertEquals(2, rediscoverVisibleCount(units, landscapePhone = true))
        }
    }

    @Test
    fun should_peekNextCard_when_shelfHoldsSeveral() {
        val width = rediscoverCardWidth(328.dp, visibleCount = 6, itemCount = 3, gap = 12.dp)
        assertEquals(328f * 0.86f, width.value, 0.001f)
    }

    @Test
    fun should_fillContent_when_shelfHoldsOne() {
        assertEquals(328f, rediscoverCardWidth(328.dp, visibleCount = 6, itemCount = 1, gap = 12.dp).value, 0f)
    }

    @Test
    fun should_splitContentEvenly_when_sideBySide() {
        assertEquals(338f, rediscoverCardWidth(688.dp, visibleCount = 2, itemCount = 2, gap = 12.dp).value, 0.001f)
        assertEquals(400f, rediscoverCardWidth(1224.dp, visibleCount = 3, itemCount = 3, gap = 12.dp).value, 0.001f)
        // Fewer items never widen a card.
        assertEquals(338f, rediscoverCardWidth(688.dp, visibleCount = 2, itemCount = 1, gap = 12.dp).value, 0.001f)
    }

    @Test
    fun should_neverGoNegative_when_contentCollapses() {
        assertEquals(0f, rediscoverCardWidth(0.dp, visibleCount = 3, itemCount = 3, gap = 12.dp).value, 0f)
    }
}
