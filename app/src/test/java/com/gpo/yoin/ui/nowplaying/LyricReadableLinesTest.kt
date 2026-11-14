package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricReadableLinesTest {

    private fun readable(current: Int, range: IntRange) = range.filter { isReadableLyricLine(it, current) }

    @Test
    fun should_keepTheLineJustSungAndTheNextTwoReadable_when_aLineIsCurrent() {
        assertEquals(listOf(4, 6, 7), readable(current = 5, range = 0..10))
    }

    @Test
    fun should_notTreatTheCurrentLineAsReadable_when_itIsBeingSung() {
        assertEquals(false, isReadableLyricLine(index = 5, current = 5))
    }

    @Test
    fun should_keepOnlyTheNextTwoReadable_when_theFirstLineIsCurrent() {
        assertEquals(listOf(1, 2), readable(current = 0, range = 0..10))
    }

    @Test
    fun should_keepNothingReadable_when_noLineIsCurrent() {
        assertEquals(emptyList<Int>(), readable(current = -1, range = 0..10))
    }

    @Test
    fun should_letLinesTwoOrMoreAboveRestAsFineDots_when_aLineIsCurrent() {
        assertEquals(false, isReadableLyricLine(index = 3, current = 5))
        assertEquals(false, isReadableLyricLine(index = 8, current = 5))
    }
}
