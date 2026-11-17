package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

/** A window read a few pixels off its estimate keeps the row's place (2026-11-17 tablet log). */
class LyricsSettledSeatTest {

    @Test
    fun noRowTakesTheScreenSeat() {
        assertEquals(0.36f, lyricsSettledSeat(actual = null, next = 0.36f, bandPx = 1700, slopPx = 32f))
    }

    @Test
    fun aRowWithinSlopKeepsItsPlace() {
        // 0.0118 of 1700px = 20px off: within 32px.
        assertEquals(0.3423f, lyricsSettledSeat(actual = 0.3423f, next = 0.3541f, bandPx = 1700, slopPx = 32f), 1e-6f)
    }

    @Test
    fun aRowFarOffTakesTheNewSeat() {
        assertEquals(0.5f, lyricsSettledSeat(actual = 0.36f, next = 0.5f, bandPx = 1700, slopPx = 32f))
    }
}
