package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the expanded lyric page seats the line being sung (ticket 2,
 * 2026-10-12: on a landscape phone the row's top went to 48dp + 38% of a
 * ~227dp window, so a wrapped line and its translation ran into the bottom
 * fade and the title row under it).
 */
class LyricsFocusSeatTest {

    // A Pixel 10 Pro in landscape: density 3, a 226.7dp lyric window, 48dp top padding.
    private val landscapeWindow = 680
    private val landscapePadding = 144
    private val fullFade = 192

    /** The seated row's top in the window (a scroll offset of s leaves the row at padding − s). */
    private fun seatTop(
        window: Int,
        padding: Int,
        row: Int,
        fade: Int = lyricsEdgeFadePx(window, fullFade),
        focusFraction: Float = LyricsFocusFraction
    ): Int = padding - lyricsFocusScrollOffsetPx(window, padding, row, fade, fade, focusFraction)

    @Test
    fun should_centreFocusRowInCleanBand_when_viewportShort() {
        val row = 153 // one line, 51dp
        val top = seatTop(landscapeWindow, landscapePadding, row)

        assertEquals("row centre at the window's middle", landscapeWindow / 2f, top + row / 2f, 1f)
    }

    @Test
    fun should_keepWrappedLineAndTranslationInCleanBand_when_viewportShort() {
        val fade = lyricsEdgeFadePx(landscapeWindow, fullFade)
        val row = 330 // two lines and a translation, 110dp
        val top = seatTop(landscapeWindow, landscapePadding, row)

        assertTrue("row starts below the top fade (top $top, fade $fade)", top >= fade)
        val bottom = top + row
        assertTrue("row ends above the bottom fade (bottom $bottom)", bottom <= landscapeWindow - fade)
    }

    @Test
    fun should_keepPortraitAnchor_when_viewportTall() {
        // A 600dp portrait page at density 1, one 48dp line: where the old
        // "row top at 48dp + 38%" seat put it, which read as the middle.
        val top = seatTop(window = 600, padding = 48, row = 48, fade = 64)

        assertEquals(48 + (600 * 0.38f).toInt(), top)
    }

    @Test
    fun should_seatRowCentreAtWindowMiddle_when_viewportVeryTall() {
        val top = seatTop(window = 3000, padding = landscapePadding, row = 153)

        assertEquals(1500f, top + 153 / 2f, 1f)
    }

    @Test
    fun should_pinRowTopToFadeEdge_when_rowTallerThanBand() {
        val top = seatTop(landscapeWindow, landscapePadding, row = 500)

        assertEquals(lyricsEdgeFadePx(landscapeWindow, fullFade), top)
    }

    @Test
    fun should_measureFromContentStart_when_listHasTopPadding() {
        val fade = lyricsEdgeFadePx(landscapeWindow, fullFade)
        val padded = lyricsFocusScrollOffsetPx(landscapeWindow, landscapePadding, 153, fade, fade)
        val unpadded = lyricsFocusScrollOffsetPx(landscapeWindow, 0, 153, fade, fade)

        assertEquals(landscapePadding, padded - unpadded)
        assertEquals(seatTop(landscapeWindow, 0, 153), seatTop(landscapeWindow, landscapePadding, 153))
    }

    @Test
    fun should_keepRowInsideBand_when_focusFractionNearAnEdge() {
        val fade = lyricsEdgeFadePx(landscapeWindow, fullFade)
        val nearTop = seatTop(landscapeWindow, landscapePadding, 153, focusFraction = 0.02f)
        val nearBottom = seatTop(landscapeWindow, landscapePadding, 153, focusFraction = 0.98f)

        assertEquals(fade, nearTop)
        assertEquals(landscapeWindow - fade - 153, nearBottom)
    }

    @Test
    fun should_shortenEdgeFades_when_windowShort() {
        assertEquals(136, lyricsEdgeFadePx(landscapeWindow, fullFade))
    }

    @Test
    fun should_keepFullEdgeFades_when_windowTall() {
        assertEquals(fullFade, lyricsEdgeFadePx(1800, fullFade))
    }
}
