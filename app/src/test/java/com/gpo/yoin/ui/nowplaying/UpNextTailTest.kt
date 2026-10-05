package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure geometry of the up-next hand-over (2026-10-05 QA: the swap jumped
 * ~105dp when the next song's lyrics were shorter than the lyrics window).
 *
 * Model: a top-to-bottom LazyColumn with vertical content padding. The
 * hand-over asks for the next title at offset 0; the list clamps that scroll
 * at its end. The next song's own list opens with its title at the top of the
 * content area (scroll 0) — that is the resting position the hand-over must
 * equal for the swap to be truly in place.
 */
class UpNextTailTest {

    // Tablet-portrait-ish numbers at 2x: a 1000dp list, 48dp padding, 4dp gaps.
    private val viewport = 2_000
    private val padTop = 96
    private val padBottom = 96
    private val spacing = 8
    private val minTail = 192
    private val contentArea = viewport - padTop - padBottom

    // This song: title card + 30 lines + the 56dp gap item, all above the next title.
    private val aboveNextTitle = 31 * (100 + spacing) + 112 + spacing

    /** Where the list draws the next title when asked to park it at offset 0. */
    private fun handoffTitleTop(upNextBlock: Int, tail: Int): Int {
        val content = aboveNextTitle + upNextBlock + spacing + tail
        val maxScroll = (padTop + content + padBottom - viewport).coerceAtLeast(0)
        val scroll = aboveNextTitle.coerceAtMost(maxScroll)
        return padTop + aboveNextTitle - scroll
    }

    /** The next song's own list at rest: scroll 0, title first. */
    private val restingTitleTop = padTop

    private fun block(lines: Int) = 120 + lines * (100 + spacing)

    @Test
    fun should_parkTheNextTitleWhereItsOwnListOpens_when_theNextLyricsAreShort() {
        for (lines in 0..20) {
            val upNext = block(lines)
            val tail = upNextTailPx(contentArea, upNext, spacing, minTail)
            assertEquals("next song with $lines lines", restingTitleTop, handoffTitleTop(upNext, tail))
        }
    }

    @Test
    fun should_keepTheUsualTail_when_theNextLyricsFillTheWindow() {
        val upNext = contentArea + 500
        assertEquals(minTail, upNextTailPx(contentArea, upNext, spacing, minTail))
        assertEquals(restingTitleTop, handoffTitleTop(upNext, minTail))
    }

    @Test
    fun should_stillReachTheTop_when_theBlockIsNotMeasuredYet() {
        // Unknown block (0) errs long: whatever the real block, the title parks.
        val tail = upNextTailPx(contentArea, upNextBlockPx = 0, itemSpacingPx = spacing, minTailPx = minTail)
        for (lines in 0..20) {
            assertEquals(restingTitleTop, handoffTitleTop(block(lines), tail))
        }
    }

    @Test
    fun should_jumpByTheShortfall_when_theTailIsTheOldFixedSpace() {
        // The bug, reproduced in the model: a fixed tail leaves the title low by
        // exactly what the short block misses, and the swap snaps it up by that.
        val upNext = block(8)
        val shortfall = contentArea - (upNext + spacing + minTail)
        assertTrue(shortfall > 0)
        assertEquals(restingTitleTop + shortfall, handoffTitleTop(upNext, minTail))
    }
}
