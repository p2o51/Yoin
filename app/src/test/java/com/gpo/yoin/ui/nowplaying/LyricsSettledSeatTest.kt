package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

/** A window read a few pixels off its estimate keeps the seat it has (2026-11-17 tablet log). */
class LyricsSettledSeatTest {

    @Test
    fun firstReadIsTaken() {
        assertEquals(0.34f, lyricsSettledSeat(Float.NaN, 0.34f))
    }

    @Test
    fun oneStepIsNotWorthAGlide() {
        assertEquals(0.34f, lyricsSettledSeat(0.34f, 0.36f))
        assertEquals(0.34f, lyricsSettledSeat(0.34f, 0.32f))
    }

    @Test
    fun aRealMoveIsTaken() {
        assertEquals(0.5f, lyricsSettledSeat(0.36f, 0.5f))
    }
}
