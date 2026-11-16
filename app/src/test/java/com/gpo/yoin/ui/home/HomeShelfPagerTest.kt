package com.gpo.yoin.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** The half-column shelf pager's page math (large-screen Home, stage 1). */
class HomeShelfPagerTest {

    private val eps = 0.001f

    @Test
    fun should_fillPageExactly_when_cardsFit() {
        val g = shelfPageGeometry(pagePx = 600f, cardPx = 82f, minGapPx = 14f)
        assertEquals(6, g.perPage)
        // Six cards and five gaps span the page edge to edge.
        assertEquals(600f, 6 * g.cardPx + 5 * g.gapPx, eps)
        assertEquals(600f + g.gapPx, g.stridePx, eps)
    }

    @Test
    fun should_keepOneCardAndMinGap_when_cardWiderThanHalfPage() {
        val g = shelfPageGeometry(pagePx = 300f, cardPx = 200f, minGapPx = 14f)
        assertEquals(1, g.perPage)
        assertEquals(314f, g.stridePx, eps)
    }

    @Test
    fun should_countLeadAndPartialPages_when_counting() {
        assertEquals(3, shelfPageCount(cardCount = 13, perPage = 6))
        assertEquals(3, shelfPageCount(cardCount = 12, perPage = 6, leadPages = 1))
        assertEquals(1, shelfPageCount(cardCount = 0, perPage = 6, leadPages = 1))
    }

    @Test
    fun should_padLastPageToWhole_when_itIsShort() {
        val g = shelfPageGeometry(600f, 82f, 14f)
        assertEquals(0f, shelfFillerPx(12, g), eps)
        val filler = shelfFillerPx(10, g)
        // Ten cards, the filler (after one more gap) ends exactly at the second page's right edge.
        val content = 10 * g.cardPx + 9 * g.gapPx + g.gapPx + filler
        assertEquals(2 * g.stridePx - g.gapPx, content, eps)
    }

    @Test
    fun should_measureScrollWithLeadPage_when_paged() {
        val g = shelfPageGeometry(600f, 82f, 14f)
        assertEquals(g.stridePx + 2 * (g.cardPx + g.gapPx) + 5f, shelfScrollPx(3, 5, g, leadPages = 1), eps)
        assertEquals(2 * (g.cardPx + g.gapPx), shelfScrollPx(2, 0, g), eps)
    }

    @Test
    fun should_beZeroOffPage_when_settled() {
        assertEquals(0f, shelfOffPagePx(2 * 610f, 610f), eps)
        assertEquals(10f, shelfOffPagePx(620f, 610f), eps)
        assertEquals(10f, shelfOffPagePx(1210f, 610f), eps)
    }

    @Test
    fun should_moveExactlyOnePage_when_released() {
        val stride = 600f
        // A fling forward from page 0, even a long drag, lands on page 1.
        assertEquals(1, shelfReleasePage(1100f, 2000f, stride, startPage = 0, pageCount = 5, flingPx = 400f))
        // A slow release short of halfway snaps back.
        assertEquals(0, shelfReleasePage(200f, 0f, stride, startPage = 0, pageCount = 5, flingPx = 400f))
        // A fast flick a little way still turns the page.
        assertEquals(1, shelfReleasePage(60f, 900f, stride, startPage = 0, pageCount = 5, flingPx = 400f))
        // Backward from page 2.
        assertEquals(1, shelfReleasePage(1150f, -900f, stride, startPage = 2, pageCount = 5, flingPx = 400f))
        // Never past the last page.
        assertEquals(4, shelfReleasePage(2450f, 3000f, stride, startPage = 4, pageCount = 5, flingPx = 400f))
    }

    @Test
    fun should_revealTideOverRevealDistance_when_offPage() {
        assertEquals(0f, shelfTideReveal(0f, 40f), eps)
        assertEquals(.5f, shelfTideReveal(20f, 40f), eps)
        assertEquals(1f, shelfTideReveal(300f, 40f), eps)
    }
}
