package com.gpo.yoin.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「歌词一句」 on the two-column grid: lines per unit, paging, and its unit count kept apart from its card count. */
class HomeLyricUnitsTest {

    private val wide = HomeLayout.WideDefault

    @Test
    fun should_fitLyricLinesToTheUnits() {
        assertEquals(3, lyricUnitLines(units = 1, hasTag = false))
        assertEquals(2, lyricUnitLines(units = 1, hasTag = true))
        assertEquals(6, lyricUnitLines(units = 2, hasTag = false))
        assertEquals(6, lyricUnitLines(units = 2, hasTag = true))
    }

    @Test
    fun should_pageOneCardInAHalf_twoInAFullRow() {
        assertEquals(1, lyricUnitPerPage(half = true))
        assertEquals(2, lyricUnitPerPage(half = false))
        assertEquals(4, lyricUnitPages(cards = 4, perPage = 1))
        assertEquals(2, lyricUnitPages(cards = 4, perPage = 2))
        assertEquals(2, lyricUnitPages(cards = 3, perPage = 2))
        assertEquals(0, lyricUnitPages(cards = 0, perPage = 2))
    }

    @Test
    fun should_keepUnitsApartFromTheCardCount() {
        // Two cards (S) on the phone doesn't make the grid's row 1 unit.
        val twoCards = wide.withRows(HomeSection.LyricHighlight, HomeRowPreset.S)
        assertEquals(2, twoCards.unitsOf(HomeSection.LyricHighlight))
        // Setting units leaves the card count alone, and is read back.
        val oneUnit = twoCards.withUnits(HomeSection.LyricHighlight, 1)
        assertEquals(1, oneUnit.unitsOf(HomeSection.LyricHighlight))
        assertEquals(HomeRowPreset.S, oneUnit.rowsOf(HomeSection.LyricHighlight))
        // Its partner (Recently Added) follows, through its own preset.
        assertEquals(1, oneUnit.unitsOf(HomeSection.RecentlyAdded))
        val reread = HomeLayout.reconcile(oneUnit.toPrefs())
        assertEquals(1, reread.unitsOf(HomeSection.LyricHighlight))
        assertEquals(HomeRowPreset.S, reread.rowsOf(HomeSection.LyricHighlight))
    }
}
