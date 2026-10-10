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
        assertEquals(0, FastScrollMath.jumpIndex(0f, items, perLine, linesPerScreen))
        assertEquals(0, FastScrollMath.labelIndex(0f, 3_000, 1))
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
        val keep = FastScrollMath.visibleTicks(centers, minGap = 18f)

        assertTrue(keep.first())
        assertTrue(keep.last())
        val kept = centers.filterIndexed { i, _ -> keep[i] }
        kept.zipWithNext().forEach { (a, b) -> assertTrue("$a→$b", b - a >= 18f) }
        assertArrayEquals(floatArrayOf(0f, 20f, 40f, 60f, 80f, 100f), kept.toFloatArray(), 0f)

        // The last tick takes its place back from a crowding neighbour.
        val crowded = FastScrollMath.visibleTicks(floatArrayOf(0f, 20f, 30f), minGap = 18f)
        assertArrayEquals(booleanArrayOf(true, false, true), crowded)
    }
}
