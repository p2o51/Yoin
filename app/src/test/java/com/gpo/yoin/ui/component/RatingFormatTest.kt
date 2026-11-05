package com.gpo.yoin.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatingFormatTest {

    @Test
    fun should_showNoNumber_when_ratingIsNull() {
        assertNull(ratingLabel(null))
    }

    @Test
    fun should_showZeroPointZero_when_ratingIsZero() {
        assertEquals("0.0", ratingLabel(0f))
    }

    @Test
    fun should_showOneDecimal_when_ratingIsSet() {
        assertEquals("7.4", ratingLabel(7.4f))
        assertEquals("10", ratingLabel(10f))
    }

    @Test
    fun should_leaveTheBarEmpty_when_ratingIsNullOrZero() {
        assertEquals(0f, ratingFillFraction(null))
        assertEquals(0f, ratingFillFraction(0f))
        assertEquals(0.74f, ratingFillFraction(7.4f), 0.0001f)
    }
}
