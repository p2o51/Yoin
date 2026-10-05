package com.gpo.yoin.ui.home

import com.gpo.yoin.ui.memories.MemoryEntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row presets resolved per section × screen (HomeRowPresets.kt): L is today's
 * composition on every screen, the ladder table matches d1-rows.md §2, and
 * neighbouring presets nest (the larger seats every card of the smaller).
 */
class HomeRowPresetsTest {

    // ---- fixtures ----

    private fun cover(index: Int) = HomeWidgetCard(
        stableId = "c$index",
        entityType = listOf(MemoryEntityType.ALBUM, MemoryEntityType.SONG, MemoryEntityType.PLAYLIST)[index % 3],
        title = "Cover $index",
        subtitle = "Artist",
        coverArtUrl = "https://x/$index",
        target = HomeWidgetTarget.AlbumDetail("c$index"),
    )

    private fun signal(index: Int) = HomeWidgetCard(
        stableId = "s$index",
        entityType = MemoryEntityType.ALBUM,
        title = "Signal $index",
        subtitle = "Artist",
        coverArtUrl = "https://x/s$index",
        ratingText = "8.0",
        comment = "Note $index",
        expanded = true,
        target = HomeWidgetTarget.MemoryFocus(index.toLong()),
    )

    private fun shelf(signals: Int = 2, covers: Int = 28): List<HomeWidgetCard> =
        (1..signals).map(::signal) + (1..covers).map(::cover)

    private val phoneJbi = jbiGridSpec(feedUnits = 2, coverColumns = 3, isCompactHeight = false)
    private val landscapeJbi = jbiGridSpec(feedUnits = 4, coverColumns = 6, isCompactHeight = true)

    private fun ids(layout: JbiLayout): Set<String> = layout.cells.mapTo(HashSet()) { it.card.stableId }

    private fun ids(rows: List<List<HomeWidgetCard>>): Set<String> = rows.flatten().mapTo(HashSet()) { it.stableId }

    // ---- Activities ----

    @Test
    fun should_reproduceTodaysPhoneBento_when_presetIsDefault() {
        for (narrow in listOf(false, true)) {
            val recipe = if (narrow) BentoRecipe.PhoneNarrow else BentoRecipe.Phone
            val rows = activityCodeRows(recipe, HomeRowPreset.L, hasHero = true, supportingCount = 5)
            assertEquals(
                listOf(ActivityRowShape.Hero, ActivityRowShape.Cards, ActivityRowShape.Strip),
                rows.map { it.shape },
            )
            val card = rows[1]
            assertEquals(ActivityPhoneRowHeight, card.height)
            assertEquals(ActivityPhoneRowGap, card.gap)
            assertEquals(
                if (narrow) {
                    listOf(SlotKind.Small to 1f, SlotKind.Small to 1f)
                } else {
                    listOf(SlotKind.Small to 1f, SlotKind.Wide to 2f)
                },
                card.slots.map { it.kind to it.weight },
            )
            assertEquals(listOf(0, 1), card.slots.map { it.entryIndex })
            assertEquals(2, rows[2].slots.single().entryIndex)
        }
    }

    @Test
    fun should_degradeFromTheBottom_when_phoneSupplyIsShort() {
        // Today's degrade: the strip goes first, then the wide becomes a spacer, then the row.
        val two = activityCodeRows(BentoRecipe.Phone, HomeRowPreset.L, hasHero = true, supportingCount = 2)
        assertEquals(listOf(ActivityRowShape.Hero, ActivityRowShape.Cards), two.map { it.shape })
        val none = activityCodeRows(BentoRecipe.Phone, HomeRowPreset.L, hasHero = true, supportingCount = 0)
        assertEquals(listOf(ActivityRowShape.Hero), none.map { it.shape })
    }

    @Test
    fun should_matchThePhoneTable_when_resolvingEachPreset() {
        // d1-rows.md §2.1: S hero · M 3 cards · L 4 · XL 6 (the mirrored row before the strip).
        val counts = HomeRowPreset.entries.map { preset ->
            activityCodeRows(BentoRecipe.Phone, preset, hasHero = true, supportingCount = 10)
                .sumOf { it.slots.size }
        }
        assertEquals(listOf(1, 3, 4, 6), counts)
        val xl = activityCodeRows(BentoRecipe.Phone, HomeRowPreset.XL, hasHero = true, supportingCount = 10)
        assertEquals(listOf(SlotKind.Wide, SlotKind.Small), xl[2].slots.map { it.kind })
        // L's strip entry is XL's wide card: it grows into it.
        assertEquals(2, xl[2].slots.first().entryIndex)
        assertEquals(4, xl[3].slots.single().entryIndex)
    }

    @Test
    fun should_keepOneRow_when_landscapeBelowXl() {
        val l = activityCodeRows(BentoRecipe.Landscape, HomeRowPreset.L, hasHero = true, supportingCount = 10)
        assertEquals(
            listOf(SlotKind.Hero to 2f, SlotKind.Small to 1f, SlotKind.Wide to 1.4f),
            l.single().slots.map { it.kind to it.weight },
        )
        assertEquals(ActivityLandscapeRowHeight, l.single().height)
        assertEquals(ActivityLandscapeRowGap, l.single().gap)
        for (preset in listOf(HomeRowPreset.S, HomeRowPreset.M)) {
            assertEquals(l, activityCodeRows(BentoRecipe.Landscape, preset, hasHero = true, supportingCount = 10))
        }
        val xl = activityCodeRows(BentoRecipe.Landscape, HomeRowPreset.XL, hasHero = true, supportingCount = 10)
        assertEquals(
            listOf(SlotKind.Wide to 1.4f, SlotKind.Small to 1f, SlotKind.Small to 1f),
            xl[1].slots.map { it.kind to it.weight },
        )

        val spec = activityBentoSpec(2, isCompactHeight = true, seed = 0)
        val ladder = activityRowLadder(spec, hasHero = true, supportingCount = 10)
        assertEquals(
            listOf(listOf(HomeRowPreset.S, HomeRowPreset.M, HomeRowPreset.L), listOf(HomeRowPreset.XL)),
            ladder.map { it.presets },
        )
        assertEquals(listOf(1, 2), ladder.map { it.rowCount })
    }

    @Test
    fun should_reproduceTodaysUnitSlots_when_presetIsDefault() {
        for (units in 3..8) for (seed in 0..5) for (hasHero in listOf(true, false)) {
            val spec = activityBentoSpec(units, isCompactHeight = false, seed = seed, hasHero = hasHero)
            for (supply in listOf(0, 3, 7, 12, 30)) {
                assertEquals(
                    activityUnitSlots(spec, hasHero, supply),
                    activityUnitSlots(spec, hasHero, supply, HomeRowPreset.L),
                )
            }
            assertEquals(
                seatedSupportingCount(spec, hasHero),
                activityPresetSupportingCount(spec, hasHero, HomeRowPreset.L),
            )
        }
    }

    @Test
    fun should_addOneUnitRowBeforeTheStrips_when_unitsAtXl() {
        val spec = activityBentoSpec(4, isCompactHeight = false, seed = 0)
        val l = activityUnitSlots(spec, hasHero = true, supportingCount = 30)
        val xl = activityUnitSlots(spec, hasHero = true, supportingCount = 30, preset = HomeRowPreset.XL)
        // N = 4 (tablet portrait): 7 cards at L, 9 at XL — the new row [2, 2] avoids [1, 2, 1]'s seams.
        assertEquals(7, l.size)
        assertEquals(9, xl.size)
        assertEquals(listOf(2, 2), xl.filter { it.row == 2 }.map { it.span })
        assertTrue(xl.filter { it.row == 3 }.all { it.kind == SlotKind.Strip })
        // L's strips are XL's third row: the same entries, a new kind.
        val lStrips = l.filter { it.kind == SlotKind.Strip }.map { it.entryIndex }
        assertEquals(lStrips, xl.filter { it.row == 2 }.map { it.entryIndex })
        // S keeps row 1, M rows 1–2.
        assertEquals(setOf(0), activityUnitSlots(spec, true, 30, HomeRowPreset.S).map { it.row }.toSet())
        assertEquals(setOf(0, 1), activityUnitSlots(spec, true, 30, HomeRowPreset.M).map { it.row }.toSet())
    }

    @Test
    fun should_mirrorRowTwo_when_choosingTheExtraRow() {
        assertEquals(listOf(2, 1), activityExtraRow(3, listOf(1, 2)))
        assertEquals(listOf(2, 2), activityExtraRow(4, listOf(1, 2, 1)))
        assertEquals(listOf(2, 2, 1), activityExtraRow(5, listOf(1, 2, 2)))
        assertEquals(listOf(2, 2, 1), activityExtraRow(5, listOf(2, 1, 2)))
        for (units in 3..8) for (seed in 0..5) {
            val spec = activityBentoSpec(units, isCompactHeight = false, seed = seed)
            val extra = activityExtraRow(units, spec.row2)
            assertEquals(units, extra.sum())
            assertTrue(extra.all { it == 1 || it == 2 })
        }
    }

    @Test
    fun should_nestEveryPreset_when_climbingTheActivitiesLadder() {
        val specs = listOf(
            activityBentoSpec(2, false, 0),
            activityBentoSpec(1, false, 0),
            activityBentoSpec(2, true, 0),
        ) + (3..8).map { activityBentoSpec(it, false, it) }
        for (spec in specs) for (hasHero in listOf(true, false)) {
            val ladder = activityRowLadder(spec, hasHero, supportingCount = 30)
            ladder.zipWithNext().forEach { (lower, upper) ->
                assertTrue("${spec.recipe} ${spec.units}", lower.payload.seated <= upper.payload.seated)
                assertTrue(lower.rowCount <= upper.rowCount)
            }
            assertEquals(HomeRowPreset.entries, ladder.flatMap { it.presets })
        }
    }

    // ---- Jump Back In ----

    @Test
    fun should_matchTheJbiRowTable_when_resolvingPresets() {
        assertEquals(listOf(2, 3, 4, 6), HomeRowPreset.entries.map { jbiPresetRows(phoneJbi, it) })
        assertEquals(listOf(1, 1, 2, 3), HomeRowPreset.entries.map { jbiPresetRows(landscapeJbi, it) })
        // Templated: 3 lanes 2/3/4/5, 5 lanes 1/2/3/4, 9 lanes 1/1/2/3.
        for ((columns, expected) in listOf(3 to listOf(2, 3, 4, 5), 5 to listOf(1, 2, 3, 4), 9 to listOf(1, 1, 2, 3))) {
            val spec = jbiGridSpec(feedUnits = 4, coverColumns = columns, isCompactHeight = false)
            assertEquals("K=$columns", expected, HomeRowPreset.entries.map { jbiPresetRows(spec, it) })
        }
    }

    @Test
    fun should_keepOneSignal_when_oneRowOrUnderNineCells() {
        assertEquals(1, jbiSignalLimit(rows = 1, cells = 10))
        assertEquals(1, jbiSignalLimit(rows = 2, cells = 6))
        assertEquals(2, jbiSignalLimit(rows = 3, cells = 9))
        assertEquals(2, jbiSignalLimit(rows = 4, cells = 12))
    }

    @Test
    fun should_reproduceTodaysPhoneShelf_when_presetIsDefault() {
        for (spec in listOf(phoneJbi, landscapeJbi)) for (signals in 0..2) {
            val cards = shelf(signals)
            val ladder = jbiPackedLadder(spec, cards)
            val l = ladder[ladder.stopIndexOf(HomeRowPreset.L)].payload
            assertEquals(packWidgetRows(trimToPhoneShelf(cards), spec.columns), l)
        }
    }

    @Test
    fun should_matchThePhoneShelfCounts_when_resolvingPresets() {
        val ladder = jbiPackedLadder(phoneJbi, shelf())
        // d1-rows.md §2.2: S 1 + 4 · M 2 + 5 · L 2 + 8 · XL 2 + 14.
        val counts = ladder.map { stop ->
            val cards = stop.payload.flatten()
            cards.count { it.expanded } to cards.count { !it.expanded }
        }
        assertEquals(listOf(1 to 4, 2 to 5, 2 to 8, 2 to 14), counts)
        assertEquals(listOf(2, 3, 4, 6), ladder.map { it.rowCount })
        ladder.zipWithNext().forEach { (lower, upper) ->
            assertTrue(ids(upper.payload).containsAll(ids(lower.payload)))
        }
    }

    @Test
    fun should_mergeStops_when_landscapeShelfRowsMatch() {
        val ladder = jbiPackedLadder(landscapeJbi, shelf())
        assertEquals(
            listOf(listOf(HomeRowPreset.S, HomeRowPreset.M), listOf(HomeRowPreset.L), listOf(HomeRowPreset.XL)),
            ladder.map { it.presets },
        )
        val s = ladder[0].payload.flatten()
        assertEquals(1 to 4, s.count { it.expanded } to s.count { !it.expanded })
    }

    @Test
    fun should_keepTheLTemplate_when_presetIsDefault() {
        for (columns in 3..10) for (seed in 0 until 20) {
            val spec = jbiGridSpec(feedUnits = 4, coverColumns = columns, isCompactHeight = false)
            val cards = shelf()
            val base = jbiLayout(cards, spec.columns, spec.rows, seed)!!
            val ladder = jbiTemplateLadder(spec, base, cards)
            assertSame(base, ladder[ladder.stopIndexOf(HomeRowPreset.L)].payload)
        }
    }

    @Test
    fun should_growFromTheBottomAndNest_when_templatePresetsChange() {
        for (columns in 3..10) for (signals in 0..2) for (seed in 0 until 40) {
            val spec = jbiGridSpec(feedUnits = 4, coverColumns = columns, isCompactHeight = false)
            val cards = shelf(signals, covers = 40)
            val base = jbiLayout(cards, spec.columns, spec.rows, seed)!!
            val ladder = jbiTemplateLadder(spec, base, cards)
            val at = "K=$columns s=$signals seed=$seed"
            ladder.forEach { stop ->
                val layout = stop.payload
                assertEquals(at, layout.template.pieces, layout.cells.map { it.piece })
                assertEquals(at, layout.cells.size, ids(layout).size)
                // Tiles once, inside its rows.
                val seen = Array(layout.template.rows) { IntArray(columns) }
                layout.template.pieces.forEach { p ->
                    for (r in p.row until p.endRow) for (c in p.column until p.endColumn) seen[r][c]++
                }
                assertTrue(at, seen.all { row -> row.all { it <= 1 } })
                assertTrue(at, layout.cells.filter { it.piece.kind.isSignal }.all { it.card.expanded })
            }
            ladder.zipWithNext().forEach { (lower, upper) ->
                assertTrue(at, ids(upper.payload).containsAll(ids(lower.payload)))
            }
            // XL: L's pieces where they were, then a row of covers.
            val xl = ladder.last().payload
            if (xl !== base) {
                assertEquals(at, base.cells, xl.cells.take(base.cells.size))
                assertEquals(at, base.template.rows + 1, xl.template.rows)
                assertTrue(at, xl.cells.drop(base.cells.size).all { it.piece.row == base.template.rows })
            }
        }
    }

    @Test
    fun should_matchTheTabletTable_when_templatedPresetsResolve() {
        // 800dp portrait, 5 lanes: S 1 + 3 · M 2 + 6 · L 2 + 11 · XL 2 + 16.
        val spec = jbiGridSpec(feedUnits = 4, coverColumns = 5, isCompactHeight = false)
        val cards = shelf()
        val base = jbiLayout(cards, spec.columns, spec.rows, seed = 3)!!
        val counts = jbiTemplateLadder(spec, base, cards).map { stop ->
            val cells = stop.payload.cells
            cells.count { it.piece.kind.isSignal } to cells.count { !it.piece.kind.isSignal }
        }
        assertEquals(listOf(1 to 3, 2 to 6, 2 to 11, 2 to 16), counts)
    }

    @Test
    fun should_stopGrowing_when_theShelfRunsOut() {
        val spec = jbiGridSpec(feedUnits = 4, coverColumns = 5, isCompactHeight = false)
        val cards = shelf(signals = 2, covers = 13)
        val base = jbiLayout(cards, spec.columns, spec.rows, seed = 1)!!
        val ladder = jbiTemplateLadder(spec, base, cards)
        // 11 covers seat L; two spare can't fill a row of five, so XL is L: one stop.
        assertEquals(listOf(HomeRowPreset.L, HomeRowPreset.XL), ladder.last().presets)
    }

    @Test
    fun should_storeTheKeptOrNearestPreset_when_landingOnAMergedStop() {
        val merged = listOf(HomeRowPreset.S, HomeRowPreset.M, HomeRowPreset.L)
        assertEquals(HomeRowPreset.M, merged.presetFrom(HomeRowPreset.M))
        assertEquals(HomeRowPreset.L, merged.presetFrom(HomeRowPreset.XL))
        assertEquals(HomeRowPreset.M, listOf(HomeRowPreset.S, HomeRowPreset.M).presetFrom(HomeRowPreset.L))
        assertEquals(HomeRowPreset.XL, listOf(HomeRowPreset.XL).presetFrom(HomeRowPreset.S))
    }
}
