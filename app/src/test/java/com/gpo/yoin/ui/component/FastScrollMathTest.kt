package com.gpo.yoin.ui.component

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fast scroller's track mappings ([FastScrollMath]) — pure, no Android. */
class FastScrollMathTest {

    // An 11-column grid (tablet landscape), ~6 rows per screen.
    private val items = 1_000
    private val perLine = 11
    private val lines = FastScrollMath.lineCount(items, perLine) // 91
    private val linesPerScreen = 6.2f

    @Test
    fun should_landOnTheSameFraction_when_restAndDragMappingsRoundTrip() {
        for (step in 0..100) {
            val f = step / 100f
            val position = FastScrollMath.positionFor(f, lines, linesPerScreen)
            assertEquals("f=$f", f, FastScrollMath.handleFraction(position, lines, linesPerScreen), 1e-4f)
        }
    }

    @Test
    fun should_meetTheEnds_when_listIsAtTopOrBottom() {
        val range = lines - linesPerScreen
        assertEquals(0f, FastScrollMath.handleFraction(0f, lines, linesPerScreen), 0f)
        assertEquals(1f, FastScrollMath.handleFraction(range, lines, linesPerScreen), 1e-5f)
        assertEquals(range, FastScrollMath.positionFor(1f, lines, linesPerScreen), 1e-4f)
        // Beyond either end clamps.
        assertEquals(1f, FastScrollMath.handleFraction(range + 5f, lines, linesPerScreen), 1e-5f)
        assertEquals(0f, FastScrollMath.positionFor(-0.3f, lines, linesPerScreen), 0f)
    }

    @Test
    fun should_moveMonotonically_when_handleTravelsDown() {
        var previous = -1f
        for (step in 0..200) {
            val position = FastScrollMath.positionFor(step / 200f, lines, linesPerScreen)
            assertTrue(position >= previous)
            previous = position
        }
    }

    @Test
    fun should_putTheSectionRowOnTop_when_handleIsDroppedOnItsTick() {
        // Sections starting mid-row: the jump stops on that row (owner, Q13).
        val sections = listOf(
            FastScrollSection("A", 0),
            FastScrollSection("B", 16),
            FastScrollSection("K", 300),
            FastScrollSection("S", 523),
            FastScrollSection("#", 990)
        )
        val tail = lines - 1 - (lines - linesPerScreen)
        val knee = (lines - linesPerScreen - tail) / (lines - 1)
        for ((index, section) in sections.withIndex()) {
            val f = FastScrollMath.tickFraction(section.startIndex, items, perLine)
            val label = FastScrollMath.sectionAt(sections, FastScrollMath.labelIndex(f, items, perLine))
            assertEquals(section.label, sections[label].label)
            if (f <= knee) {
                val row = section.startIndex / perLine
                assertEquals(section.label, row * perLine, FastScrollMath.jumpIndex(f, items, perLine, linesPerScreen))
            } else {
                assertEquals("tail sections are the last ones", sections.lastIndex, index)
            }
        }
    }

    @Test
    fun should_keepTheNamedItemOnScreen_when_handleIsInTheLastStretch() {
        for (step in 0..100) {
            val f = step / 100f
            val top = FastScrollMath.positionFor(f, lines, linesPerScreen)
            val labelLine = FastScrollMath.labelIndex(f, items, perLine) / perLine
            assertTrue("f=$f label $labelLine above $top", labelLine + 0.5f >= top)
            assertTrue("f=$f label $labelLine below the screen", labelLine <= top + linesPerScreen)
        }
    }

    @Test
    fun should_nameTheLastSectionAndClampTheJump_when_handleIsAtTheBottom() {
        assertEquals(items - 1, FastScrollMath.labelIndex(1f, items, perLine))
        val jump = FastScrollMath.jumpIndex(1f, items, perLine, linesPerScreen)
        assertTrue(jump in 0 until items)
        assertEquals(0, jump % perLine)
        // Never short of the end: a line the list can't bring to the top, so it clamps to its end.
        assertTrue(jump / perLine >= lines - linesPerScreen)
        assertEquals(0, FastScrollMath.jumpIndex(0f, items, perLine, linesPerScreen))
        assertEquals(0, FastScrollMath.labelIndex(0f, 3_000, 1))
    }

    @Test
    fun should_reachTheListsEnd_when_handleIsAtTheBottomWhateverTheScreenHolds() {
        // A screen's lines end in any fraction of a line: below a half the
        // old rounding put the line before the end on top, leaving part of the
        // last row under the floating bar (device QA: 85px on the tablet in
        // portrait at 5.65 lines, 187px on a phone at 3.51).
        for (screen in floatArrayOf(3.5075f, 5.65f, 5.756f, 6.2f, 6.7f, 13.617f, 9f)) {
            val jump = FastScrollMath.jumpIndex(1f, items, perLine, screen)
            assertEquals("screen $screen", 0, jump % perLine)
            val line = jump / perLine
            assertTrue("screen $screen: line $line short of ${lines - screen}", line >= lines - screen)
            assertTrue("screen $screen", jump < items)
            // Monotonic all the way down, and still a row start everywhere.
            var previous = 0
            for (step in 0..400) {
                val index = FastScrollMath.jumpIndex(step / 400f, items, perLine, screen)
                assertTrue("screen $screen step $step", index >= previous)
                assertEquals(0, index % perLine)
                previous = index
            }
        }
    }

    @Test
    fun should_countTheLastLinesMissingSpacing_when_measuringAScreen() {
        // The tablet in portrait: a 2244px grid inside 16px and 280px of
        // padding, 312px cells 32px apart.
        val screen = FastScrollMath.screenLines(
            viewportPx = 2_244,
            beforePaddingPx = 16,
            afterPaddingPx = 280,
            spacingPx = 32,
            linePx = 344f
        )
        assertEquals((1_948f + 32f) / 344f, screen, 1e-4f)
        // So the scroll range is exactly how far the grid scrolls: 224 lines
        // end 312px above the padding, after 223 gaps.
        val contentPx = 224 * 344f - 32f
        assertEquals((contentPx - 1_948f) / 344f, 224 - screen, 1e-3f)
        assertEquals(0f, FastScrollMath.screenLines(100, 60, 60, 16, 344f), 0f)
    }

    @Test
    fun should_jumpItemByItem_when_theListIsASingleColumn() {
        val songs = 3_000
        val f = 0.25f
        val expected = FastScrollMath.positionFor(f, songs, 9f).let { Math.round(it) }
        assertEquals(expected, FastScrollMath.jumpIndex(f, songs, 1, 9f))
        assertEquals(Math.round(f * (songs - 1)), FastScrollMath.labelIndex(f, songs, 1))
    }

    @Test
    fun should_showOnlyForContentOverThreeScreens_when_checkingTheLength() {
        assertFalse(FastScrollMath.isWorthShowing(itemCount = 30, itemsPerLine = 1, linesPerScreen = 10f))
        assertTrue(FastScrollMath.isWorthShowing(itemCount = 31, itemsPerLine = 1, linesPerScreen = 10f))
        assertFalse(FastScrollMath.isWorthShowing(itemCount = 500, itemsPerLine = 1, linesPerScreen = 0f))
        // 3 columns × 10 rows per screen: 90 items is exactly three screens.
        assertFalse(FastScrollMath.isWorthShowing(itemCount = 90, itemsPerLine = 3, linesPerScreen = 10f))
        assertTrue(FastScrollMath.isWorthShowing(itemCount = 91, itemsPerLine = 3, linesPerScreen = 10f))
    }

    @Test
    fun should_findTheHoldingSection_when_lookingUpAnIndex() {
        val sections = listOf(FastScrollSection("A", 0), FastScrollSection("B", 10), FastScrollSection("C", 25))

        assertEquals(0, FastScrollMath.sectionAt(sections, 0))
        assertEquals(0, FastScrollMath.sectionAt(sections, 9))
        assertEquals(1, FastScrollMath.sectionAt(sections, 10))
        assertEquals(2, FastScrollMath.sectionAt(sections, 999))
        assertEquals(-1, FastScrollMath.sectionAt(emptyList(), 3))
    }

    @Test
    fun should_shareOneTick_when_adjacentSectionsHaveTheSameTickLabel() {
        val sections = listOf(
            FastScrollSection("Mar 2024", 0, "2024"),
            FastScrollSection("Jan 2024", 4, "2024"),
            FastScrollSection("Jul 2023", 9, "2023"),
            FastScrollSection("Mar 2024", 12, "2024")
        )

        assertEquals(
            listOf(FastScrollTick("2024", 0), FastScrollTick("2023", 9), FastScrollTick("2024", 12)),
            FastScrollMath.ticks(sections)
        )
    }

    @Test
    fun should_giveTheLineToItsLastRun_when_sectionsStartOnTheSameRow() {
        // 11 columns: Q (112) and R (115) both start on row 10, P on row 9.
        val sections = listOf(
            FastScrollSection("A", 0),
            FastScrollSection("P", 100),
            FastScrollSection("Q", 112),
            FastScrollSection("R", 115),
            FastScrollSection("S", 200)
        )

        val ticks = FastScrollMath.ticks(sections, perLine)

        assertEquals(listOf("A", "P", "R", "S"), ticks.map { it.label })
        assertEquals(115, ticks[2].startIndex)
        // Dropped on R's tick, the bubble names R.
        val f = FastScrollMath.tickFraction(115, items, perLine)
        val named = FastScrollMath.sectionAt(sections, FastScrollMath.labelIndex(f, items, perLine))
        assertEquals("R", sections[named].label)
    }

    @Test
    fun should_nameEveryTicksOwnRun_when_theHandleSitsOnIt() {
        // Many short sections on an 11-column grid: letters of 1–30 items,
        // and a timeline whose months run under their years.
        val letters = ArrayList<FastScrollSection>()
        var start = 0
        var size = 1
        while (start < items) {
            letters += FastScrollSection("L${letters.size}", start)
            start += size
            size = size % 30 + 7
        }
        val months = (0 until 80).map { m ->
            FastScrollSection("M$m", startIndex = m * 12 + m % 5, tickLabel = "Y${m / 12}")
        }
        for (sections in listOf(letters, months)) {
            val runs = FastScrollMath.tickRuns(sections)
            for (tick in FastScrollMath.ticks(sections, perLine)) {
                val f = FastScrollMath.tickFraction(tick.startIndex, items, perLine)
                val named = FastScrollMath.sectionAt(sections, FastScrollMath.labelIndex(f, items, perLine))
                val owner = FastScrollMath.sectionAt(sections, tick.startIndex)
                assertEquals("tick ${tick.label} names ${sections[named].label}", runs[owner], runs[named])
            }
        }
    }

    @Test
    fun should_numberRunsOfTheSameTickLabel_when_mappingSectionsToTicks() {
        val sections = listOf(
            FastScrollSection("Mar 2024", 0, "2024"),
            FastScrollSection("Jan 2024", 4, "2024"),
            FastScrollSection("Jul 2023", 9, "2023"),
            FastScrollSection("Mar 2024", 12, "2024")
        )

        assertArrayEquals(intArrayOf(0, 0, 1, 2), FastScrollMath.tickRuns(sections))
        assertEquals(0, FastScrollMath.tickRuns(emptyList()).size)
    }

    @Test
    fun should_keepBothEndsAndTheMinimumGap_when_thinningTicks() {
        val centers = FloatArray(21) { it * 5f } // 0, 5, …, 100
        val places = FastScrollMath.placeTicks(centers, minGap = 18f)

        assertPlacedOnTheirOwnRuns(centers, places, minGap = 18f)
        // A crowded label moves down to the gap while that is still on its
        // own run, else it goes: ticks 0, 15, 35, 50, 70 and 100 are drawn.
        assertArrayEquals(
            floatArrayOf(0f, 18f, 36f, 54f, 72f, 100f),
            places.filter { !it.isNaN() }.toFloatArray(),
            1e-4f
        )
        assertEquals(listOf(0, 3, 7, 10, 14, 20), places.indices.filter { !places[it].isNaN() })

        // The last tick keeps its place; the crowding neighbour gives way.
        val crowded = FastScrollMath.placeTicks(floatArrayOf(0f, 20f, 30f), minGap = 18f)
        assertEquals(0f, crowded[0], 0f)
        assertTrue(crowded[1].isNaN())
        assertEquals(30f, crowded[2], 0f)
    }

    @Test
    fun should_keepALongRunsLabel_when_aShortRunCrowdsItFromAbove() {
        // Albums › Recently added on the device QA library (portrait): 2026,
        // 2025, 2024 (20 albums), 2023 (351 albums, a quarter of the
        // library), 2022, 2021, 2020 — 1,343 albums, 6 a row. 2023's tick sat
        // 12dp under 2024's, and the greedy thinning dropped it.
        val starts = intArrayOf(0, 31, 72, 92, 443, 743, 1_043)
        val years = (2026 downTo 2020).map { it.toString() }
        val sections = starts.zip(years) { start, year -> FastScrollSection("Jan $year", start, year) }
        val ticks = FastScrollMath.ticks(sections, itemsPerLine = 6)
        val centers = FastScrollMath.tickCenters(ticks, 1_343, 6, extentPx = 905f, offsetPx = 32f)
        assertEquals(12.2f, centers[3] - centers[2], 0.1f) // the crowding

        val places = FastScrollMath.placeTicks(centers, minGap = 18f)

        assertEquals("every year labelled", years, ticks.filterIndexed { i, _ -> !places[i].isNaN() }.map { it.label })
        assertPlacedOnTheirOwnRuns(centers, places, minGap = 18f)
        assertEquals("2023 under 2024, at the gap", centers[2] + 18f, places[3], 1e-3f)
        // Held anywhere in 2023, the lit tick is 2023's: the last drawn one at or before the bubble's item.
        val lit = ticks.indices.last { !places[it].isNaN() && ticks[it].startIndex <= 300 }
        assertEquals("2023", ticks[lit].label)
    }

    /** Both ends at their own places; every drawn label on its own run and [minGap] from the one above. */
    private fun assertPlacedOnTheirOwnRuns(centers: FloatArray, places: FloatArray, minGap: Float) {
        assertEquals(centers.first(), places.first(), 0f)
        assertEquals(centers.last(), places.last(), 0f)
        var above = Float.NaN
        for (i in centers.indices) {
            val y = places[i]
            if (y.isNaN()) continue
            assertTrue("tick $i drawn at $y above its centre ${centers[i]}", y >= centers[i])
            if (i < centers.lastIndex) {
                assertTrue("tick $i drawn at $y past the next run at ${centers[i + 1]}", y < centers[i + 1])
            }
            if (!above.isNaN()) assertTrue("tick $i at $y crowds $above", y - above >= minGap - 1e-4f)
            above = y
        }
    }
}
