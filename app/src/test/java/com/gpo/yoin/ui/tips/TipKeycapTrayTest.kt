package com.gpo.yoin.ui.tips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard shortcuts tip's keycap tray: its top edge never crosses a row of the album grid. */
class TipKeycapTrayTest {
    // Three rows 16 apart; the grid ends at 700.
    private val rows = listOf(0..200, 216..416, 432..632)

    private fun tray(keysPx: Int) = keycapTrayHeightPx(
        rows = rows,
        gridEndPx = 700,
        keysPx = keysPx,
        roomPx = 16,
        gapPx = 8,
    )

    @Test
    fun should_keepItsOwnHeight_when_itsEdgeFallsBetweenRows() {
        // 50 + 16: its edge at 634, under the last row.
        assertEquals(66, tray(keysPx = 50))
    }

    @Test
    fun should_coverTheWholeRow_when_itsEdgeWouldCrossTheSubtitleLine() {
        // 60 + 16: its edge would be at 624, across the last row's foot.
        val height = tray(keysPx = 60)
        assertEquals(700 - 432 + 8, height)
        // Its edge sits in the gap above that row.
        val edge = 700 - height
        assertTrue(edge in 417..431)
    }

    @Test
    fun should_waitForTheKeys_when_notMeasuredYet() {
        assertEquals(0, tray(keysPx = 0))
    }
}
