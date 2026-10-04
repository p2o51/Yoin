package com.gpo.yoin.ui.home

import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.feedContentWidth
import com.gpo.yoin.ui.experience.feedFrameClass
import com.gpo.yoin.ui.experience.previewYoinWindowInfo
import com.gpo.yoin.ui.memories.MemoryEntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [jbiLayout] / [jbiTemplate]: the lane-staggered Jump Back In. */
class HomeJbiTemplateTest {

    private fun cover(index: Int, type: MemoryEntityType = MemoryEntityType.ALBUM) = HomeWidgetCard(
        stableId = "c$index",
        entityType = type,
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

    private fun shelf(signals: Int, covers: Int = 24): List<HomeWidgetCard> {
        val types = listOf(MemoryEntityType.ALBUM, MemoryEntityType.SONG, MemoryEntityType.PLAYLIST)
        return (1..signals).map(::signal) + (1..covers).map { cover(it, types[it % types.size]) }
    }

    private fun assertTiles(template: JbiTemplate) {
        val seen = Array(template.rows) { IntArray(template.columns) }
        template.pieces.forEach { piece ->
            for (r in piece.row until piece.endRow) for (c in piece.column until piece.endColumn) seen[r][c]++
        }
        seen.forEachIndexed { r, row -> row.forEachIndexed { c, n -> assertEquals("cell $c,$r", 1, n) } }
        // A lying card's two columns are one lane: one offset.
        template.pieces.filter { it.kind == JbiPieceKind.WideSignal }.forEach { piece ->
            assertEquals(template.offsets[piece.column], template.offsets[piece.column + 1])
        }
        // Reading order: by where they sit on screen, then column.
        val order = template.pieces.map { template.topOf(it) to it.column }
        assertEquals(order.sortedWith(compareBy({ it.first }, { it.second })), order)
    }

    @Test
    fun should_tileAndSeatEveryCardOnce_when_anyWidthSignalsStyleAndSeed() {
        for (columns in 3..10) {
            val rows = jbiTemplateRows(columns)
            for (signals in 0..2) {
                for (seed in 0 until 120) {
                    val layout = jbiLayout(shelf(signals), columns, rows, seed)
                    assertNotNull("K=$columns s=$signals seed=$seed", layout)
                    layout!!
                    assertEquals(rows, layout.template.rows)
                    assertTiles(layout.template)
                    assertEquals(layout.template.pieces, layout.cells.map { it.piece })
                    val ids = layout.cells.map { it.card.stableId }
                    assertEquals(ids.size, ids.toSet().size)
                    assertEquals(signals, layout.cells.count { it.piece.kind.isSignal })
                    assertTrue(layout.cells.filter { it.piece.kind.isSignal }.all { it.card.expanded })
                    assertTrue(layout.cells.filterNot { it.piece.kind.isSignal }.none { it.card.expanded })
                }
            }
        }
    }

    @Test
    fun should_levelBesideStandingCopyAndDropBesideLyingCopy_when_drifting() {
        for (columns in 3..10) {
            for (signals in 0..2) {
                for (seed in 0 until 150) {
                    val rows = jbiTemplateRows(columns)
                    val template = jbiTemplate(columns, rows, signals, seed)
                    val at = "K=$columns s=$signals seed=$seed: $template"
                    val offsets = template.offsets
                    assertEquals(at, 0f, offsets.first())
                    // A standing card's both sides start level with it.
                    val level = mutableSetOf<Int>()
                    // Boundaries that may stay level: left of a lying card (its cover side).
                    val mayLevel = mutableSetOf<Int>()
                    val dropped = mutableSetOf<Int>()
                    template.pieces.forEach { p ->
                        when (p.kind) {
                            JbiPieceKind.TallSignal -> {
                                if (p.column > 0) level += p.column - 1
                                if (p.column < columns - 1) level += p.column
                            }
                            JbiPieceKind.WideSignal -> {
                                level += p.column
                                if (p.column > 0) mayLevel += p.column - 1
                                if (p.endColumn < columns) dropped += p.endColumn - 1
                            }
                            JbiPieceKind.Cover -> Unit
                        }
                    }
                    // A lying card's right neighbour: half a row lower, and the card on the top row.
                    template.pieces.filter { it.kind == JbiPieceKind.WideSignal && it.endColumn < columns }
                        .forEach { p ->
                            val right = p.endColumn
                            if (right - 1 in dropped && right - 1 !in level) {
                                assertEquals(at, offsets[p.column] + 0.5f, offsets[right])
                                assertEquals(at, 0, p.row)
                            }
                        }
                    level.forEach { c -> assertEquals(at, offsets[c], offsets[c + 1]) }
                    (0 until columns - 1).filter { it !in level && it !in mayLevel }.forEach { c ->
                        assertNotEquals("$at | $c", offsets[c], offsets[c + 1])
                    }
                }
            }
        }
    }

    @Test
    fun should_keepTheTwoCardsInDifferentLanes_when_bothPresent() {
        for (columns in 3..10) {
            for (seed in 0 until 150) {
                val template = jbiTemplate(columns, jbiTemplateRows(columns), 2, seed)
                val signals = template.pieces.filter { it.kind.isSignal }
                assertEquals(2, signals.size)
                val (a, b) = signals
                assertTrue("K=$columns seed=$seed", a.endColumn <= b.column || b.endColumn <= a.column)
                // A lane apart wherever the width allows (their copies never side by side).
                if (columns >= 5) {
                    assertTrue("K=$columns seed=$seed: $template", a.endColumn < b.column || b.endColumn < a.column)
                }
            }
        }
    }

    @Test
    fun should_layCardsDown_when_oneRow() {
        val template = jbiTemplate(9, 1, signals = 2, seed = 4)
        assertTrue(template.pieces.filter { it.kind.isSignal }.all { it.kind == JbiPieceKind.WideSignal })
    }

    @Test
    fun should_returnSameLayout_when_sameInputs() {
        val cards = shelf(2)
        for (columns in 3..10) {
            val rows = jbiTemplateRows(columns)
            val a = jbiLayout(cards, columns, rows, seed = 42)
            val b = jbiLayout(cards.toList(), columns, rows, seed = 42)
            assertEquals(a, b)
        }
    }

    @Test
    fun should_varyTemplates_when_seedsDiffer() {
        for (columns in 4..10) {
            val distinct = (0 until 40).map { seed ->
                jbiTemplate(columns, jbiTemplateRows(columns), 2, seed)
            }.toSet()
            assertTrue("K=$columns: ${distinct.size}", distinct.size >= 8)
        }
    }

    @Test
    fun should_keepSeed_when_signalCardArrives() {
        val covers = shelf(0)
        assertEquals(jbiLayoutSeed(covers), jbiLayoutSeed(listOf(signal(1), signal(2)) + covers))
    }

    @Test
    fun should_seatCoversInRankingOrder_when_readingOrder() {
        val layout = jbiLayout(shelf(2), 9, 2, seed = 3)!!
        val seated = layout.cells.filter { it.piece.kind == JbiPieceKind.Cover }.map { it.card }
        assertEquals(shelf(2).filterNot { it.expanded }.take(seated.size), seated)
    }

    @Test
    fun should_dropARow_when_shelfTooShort() {
        // 5 lanes × 3 with both cards takes 11 covers; 8 only fill two rows.
        val layout = jbiLayout(shelf(2, covers = 8), 5, 3, seed = 1)
        assertNotNull(layout)
        assertEquals(2, layout!!.template.rows)
        assertTiles(layout.template)
    }

    @Test
    fun should_returnNull_when_notEvenOneRowFits() {
        assertNull(jbiLayout(shelf(0, covers = 3), 9, 2, seed = 1))
    }

    @Test
    fun should_keepCoversPhoneSized_when_templatedAtAnyWidth() {
        for (w in 480..2400 step 4) {
            val info = previewYoinWindowInfo(widthDp = w, heightDp = 1000)
            if (info.feedUnits < 3) continue
            val k = info.feedCoverColumns
            val content = feedContentWidth(w.dp, feedFrameClass(info))
            val column = (content - 12.dp * (k - 1)) / k
            val cover = jbiCoverSide(column, JbiCoverFit.Capped128)
            assertTrue("$w: $cover", cover.value in 100f..128f)
        }
    }

    @Test
    fun should_keepPhoneShelfAtTwelveCells_when_trimming() {
        assertEquals(12, trimToPhoneShelf(shelf(2)).sumOf { if (it.expanded) 2 else 1 })
        assertEquals(12, trimToPhoneShelf(shelf(1)).sumOf { if (it.expanded) 2 else 1 })
        assertEquals(12, trimToPhoneShelf(shelf(0)).size)
        assertEquals(5, trimToPhoneShelf(shelf(0, covers = 5)).size)
        assertEquals(listOf("s1", "s2", "c1"), trimToPhoneShelf(shelf(2)).take(3).map { it.stableId })
    }
}
