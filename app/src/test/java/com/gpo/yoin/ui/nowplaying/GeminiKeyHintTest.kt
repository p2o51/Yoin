package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiKeyHintTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun should_beDue_when_neverShown() {
        assertTrue(isDue(lastShownMs = null, nowMs = 1_000L))
    }

    @Test
    fun should_notBeDue_when_shownWithinThreeDays() {
        assertFalse(isDue(lastShownMs = 10 * day, nowMs = 12 * day))
        assertFalse(isDue(lastShownMs = 10 * day, nowMs = 13 * day - 1))
    }

    @Test
    fun should_beDue_when_threeDaysPassed() {
        assertTrue(isDue(lastShownMs = 10 * day, nowMs = 13 * day))
    }

    @Test
    fun should_beDue_when_clockSetBack() {
        assertTrue(isDue(lastShownMs = 10 * day, nowMs = 5 * day))
    }
}
