package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sung line sits a little above the screen's middle wherever the lyric
 * page is laid out (owner 2026-11-08 「不管在什么地方播放，歌词高亮都应该在屏幕
 * 中间靠上的位置」).
 */
class LyricsScreenSeatTest {

    private val fullFade = 128 // 64dp at density 2

    /** Where the row's centre lands on the screen for a window at [top], [window] tall. */
    private fun seatOnScreen(top: Float, window: Int, screen: Int): Float {
        val fraction = lyricsScreenSeatFraction(top, window, screen, fullFade)
        val fade = lyricsEdgeFadePx(window, fullFade)
        return top + fade + (window - fade * 2) * fraction
    }

    @Test
    fun should_seatAboveScreenMiddle_when_tabletRightColumnInFocus() {
        // Pixel Tablet landscape, density 2: in focus the right column runs 160dp..780dp.
        val screen = 1600
        val onScreen = seatOnScreen(top = 320f, window = 1240, screen = screen)

        assertEquals(screen * LyricsScreenSeat, onScreen, screen * 0.02f)
    }

    @Test
    fun should_neverSinkPastBandMiddle_when_windowEndsAboveSeat() {
        // The top half of a hinge: the screen's seat lies below the window.
        assertEquals(LyricsFocusFraction, lyricsScreenSeatFraction(0f, 600, 1800, fullFade))
    }

    @Test
    fun should_keepLinesAbove_when_windowStartsBelowSeat() {
        // A window under a large cover: the seat would be above it.
        assertEquals(LyricsSeatMinFraction, lyricsScreenSeatFraction(1200f, 500, 1800, fullFade))
    }

    @Test
    fun should_fallBackToMiddle_when_notMeasured() {
        assertEquals(LyricsFocusFraction, lyricsScreenSeatFraction(0f, 0, 0, fullFade))
    }
}
