package com.gpo.yoin.ui.library

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [librarySparseColumns]: a short Library on a wide screen grows its cells. */
class LibrarySparseGridTest {

    @Test
    fun should_growCells_when_fewItemsOnALandscapeTablet() {
        // 1280dp landscape tablet: 11 columns of ~100dp; 8 albums sat in one row.
        val columns = librarySparseColumns(itemCount = 8, baseColumns = 11, width = 1248.dp, height = 640.dp)
        assertEquals(6, columns)
    }

    @Test
    fun should_keepUsualCells_when_theShelfFillsThePage() {
        assertEquals(11, librarySparseColumns(itemCount = 200, baseColumns = 11, width = 1248.dp, height = 640.dp))
    }

    @Test
    fun should_takeFewestColumnsThatStillFit_when_inBetween() {
        // 40 items: half the columns would scroll, so the count rises until the rows fit.
        val columns = librarySparseColumns(itemCount = 40, baseColumns = 11, width = 1248.dp, height = 1000.dp)
        assertTrue(columns in 7..11)
        val cell = (1248.dp - 12.dp * (columns - 1)) / columns
        val rows = (40 + columns - 1) / columns
        assertTrue((cell + 44.dp) * rows + 16.dp * (rows - 1) <= 1000.dp)
    }

    @Test
    fun should_neverGoBelowThreeOrHalf_when_veryFewItems() {
        assertEquals(3, librarySparseColumns(itemCount = 1, baseColumns = 5, width = 568.dp, height = 900.dp))
        assertEquals(6, librarySparseColumns(itemCount = 1, baseColumns = 11, width = 1248.dp, height = 640.dp))
        // Phone-sized grids are left alone.
        assertEquals(3, librarySparseColumns(itemCount = 1, baseColumns = 3, width = 360.dp, height = 640.dp))
    }
}
