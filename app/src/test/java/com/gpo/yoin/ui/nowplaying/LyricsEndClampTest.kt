package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Once nothing scrolls in from below, the list stops at its natural end and the
 * sung line walks on down the screen (owner 2026-11-16).
 */
class LyricsEndClampTest {

    @Test
    fun lastRowRestsTailAboveTheWindowBottom() {
        val viewport = 1600
        val padding = 96
        val tail = 300
        // Focus row's top → last row's bottom: 200px (the last line itself).
        val offset = lyricsEndClampScrollOffsetPx(viewport, padding, 200, tail)
        // Row top in the window = padding - offset.
        val rowTop = padding - offset
        assertEquals(viewport - tail, rowTop + 200)
    }

    @Test
    fun farFromTheEndTheSeatWins() {
        val seated = lyricsFocusScrollOffsetPx(
            viewportPx = 1600,
            contentPaddingTopPx = 96,
            rowPx = 100,
            fadeTopPx = 128,
            fadeBottomPx = 128,
            focusFraction = 0.4f,
        )
        val clamp = lyricsEndClampScrollOffsetPx(1600, 96, 2400, 300)
        assertEquals(seated, minOf(seated, clamp))
    }
}
