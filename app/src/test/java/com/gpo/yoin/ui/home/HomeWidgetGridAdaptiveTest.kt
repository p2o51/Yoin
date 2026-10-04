package com.gpo.yoin.ui.home

import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.ShellChromeForm
import com.gpo.yoin.ui.experience.YoinWindowInfo
import com.gpo.yoin.ui.experience.previewYoinWindowInfo
import com.gpo.yoin.ui.memories.MemoryEntityType
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for the Medium / Tabletop / Wide Jump Back In grid
 * ([jbiCoverSide], [jbiCellPlacements] and the true column geometry; the gate
 * is `contentFollowsColumns`, pinned by ContentFollowsColumnsTest). Compact's Row path is pinned by `HomeWidgetGridPackTest`.
 */
class HomeWidgetGridAdaptiveTest {

    // ---- cover side ----

    @Test
    fun should_size137dp_when_mediumPortraitColumn() {
        // 688dp of feed across 4 columns: (688 − 3 × 12) / 4 = 163dp.
        val column = 163.dp
        assertEquals(136.92f, jbiCoverSide(column).value, 0.01f)
        assertEquals(137, jbiCoverSide(column).value.roundToInt())
    }

    @Test
    fun should_clampTo160dp_when_wideColumn() {
        // 1216dp across 6 columns: (1216 − 5 × 12) / 6 ≈ 192.7dp → 0.84 × ≈ 161.8.
        val column = ((1216f - 5 * 12f) / 6f).dp
        assertEquals(160f, jbiCoverSide(column).value, 0.001f)
        assertEquals(160f, jbiCoverSide(400.dp).value, 0.001f)
    }

    @Test
    fun should_clampTo128dp_when_hierarchyCapVariant() {
        assertEquals(128f, jbiCoverSide(163.dp, JbiCoverFit.Capped128).value, 0.001f)
        assertEquals(128f, jbiCoverSide(192.7.dp, JbiCoverFit.Capped128).value, 0.001f)
        // Below the cap it keeps the phone rhythm.
        assertEquals(112.0f, jbiCoverSide(133.33.dp, JbiCoverFit.Capped128).value, 0.01f)
        // The legacy variant never moves.
        assertEquals(100f, jbiCoverSide(192.7.dp, JbiCoverFit.Legacy100).value, 0.001f)
    }

    @Test
    fun should_neverDropBelow100dp_when_columnIsNarrow() {
        // Wide beside the NP panel: 126dp columns → 0.84 × 126 = 105.8, above the floor.
        assertEquals(105.84f, jbiCoverSide(126.dp).value, 0.01f)
        // A 110dp column would give 92.4dp; the floor holds it at 100dp.
        assertEquals(100f, jbiCoverSide(110.dp).value, 0.001f)
        assertEquals(100f, jbiCoverSide(110.dp, JbiCoverFit.Capped128).value, 0.001f)
        // …but never wider than the column itself.
        assertEquals(90f, jbiCoverSide(90.dp).value, 0.001f)
        assertEquals(90f, jbiCoverSide(90.dp, JbiCoverFit.Legacy100).value, 0.001f)
    }

    // ---- true columns ----

    @Test
    fun should_placeWideCardOnTrueColumns_when_rowAlternates() {
        val w1 = wide("w1")
        val w2 = wide("w2")
        val c = (1..8).map { compact("c$it") }
        val rows = packWidgetRows(listOf(w1, w2) + c, columns = 4)
        val placements = jbiCellPlacements(rows, columns = 4)

        assertEquals(
            listOf(
                // [w1][c1][c2]: the wide card hugs the start edge…
                listOf(JbiCellPlacement(0, 2), JbiCellPlacement(2, 1), JbiCellPlacement(3, 1)),
                // …then alternates to the end edge: [c3][c4][w2].
                listOf(JbiCellPlacement(0, 1), JbiCellPlacement(1, 1), JbiCellPlacement(2, 2)),
                listOf(
                    JbiCellPlacement(0, 1),
                    JbiCellPlacement(1, 1),
                    JbiCellPlacement(2, 1),
                    JbiCellPlacement(3, 1),
                ),
            ),
            placements,
        )

        // Medium portrait at 1px = 1dp: 688 wide, 12 gap → 163 columns, 175 pitch.
        val gap = 12f
        val pitch = jbiColumnPitch(maxWidth = 688f, columns = 4, gap = gap)
        assertEquals(175f, pitch, 0.001f)
        val (row0, row1, row2) = placements
        // The end-side 1×2 starts on column 2's line and spans 2 × 163 + 12.
        assertEquals(350, row1[2].cellLeft(pitch))
        assertEquals(338, row1[2].cellWidth(pitch, gap))
        assertEquals(688, row1[2].cellLeft(pitch) + row1[2].cellWidth(pitch, gap))
        // The start-side 1×2 has the same width, and the 1×1s beside each sit on
        // exactly the lines of a full row of 1×1s — no paired-row drift.
        assertEquals(0, row0[0].cellLeft(pitch))
        assertEquals(338, row0[0].cellWidth(pitch, gap))
        assertEquals(row2[2].cellLeft(pitch), row0[1].cellLeft(pitch))
        assertEquals(row2[3].cellLeft(pitch), row0[2].cellLeft(pitch))
        assertEquals(row2[1].cellLeft(pitch), row1[1].cellLeft(pitch))
        (row0 + row1 + row2).filter { it.span == 1 }.forEach { cell ->
            assertEquals(163, cell.cellWidth(pitch, gap))
        }
    }

    @Test
    fun should_leaveTrailingColumnsEmpty_when_rowIsShort() {
        val rows = packWidgetRows(listOf(wide("w1"), compact("c1")), columns = 6)
        val placements = jbiCellPlacements(rows, columns = 6)
        assertEquals(listOf(listOf(JbiCellPlacement(0, 2), JbiCellPlacement(2, 1))), placements)
    }

    // ---- helpers ----

    private fun compact(id: String): HomeWidgetCard = HomeWidgetCard(
        stableId = id,
        entityType = MemoryEntityType.ALBUM,
        title = id,
        subtitle = "subtitle",
        coverArtUrl = null,
        target = HomeWidgetTarget.AlbumDetail(albumId = id),
    )

    private fun wide(id: String): HomeWidgetCard = compact(id).copy(
        expanded = true,
        ratingText = "7.0",
    )
}
