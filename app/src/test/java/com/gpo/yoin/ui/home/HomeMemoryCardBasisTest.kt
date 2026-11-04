package com.gpo.yoin.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMemoryCardBasisTest {

    @Test
    fun should_notShowTrackBasis_when_albumHasManualRating() {
        val (date, trackBasis) = memoryCardBasis(
            hasReview = false,
            hasManualRating = true,
            ratedTrackCount = 5,
            totalTracks = 5,
            lastTouchedAt = 1_000L,
        )
        assertFalse(trackBasis)
        assertEquals(1_000L, date)
    }

    @Test
    fun should_showTrackBasis_when_scoreIsTheTrackAverage() {
        val (date, trackBasis) = memoryCardBasis(
            hasReview = false,
            hasManualRating = false,
            ratedTrackCount = 4,
            totalTracks = 10,
            lastTouchedAt = 1_000L,
        )
        assertTrue(trackBasis)
        assertNull(date)
    }

    @Test
    fun should_dateTheCard_when_albumIsReviewed() {
        val (date, trackBasis) = memoryCardBasis(
            hasReview = true,
            hasManualRating = false,
            ratedTrackCount = 3,
            totalTracks = 8,
            lastTouchedAt = 2_000L,
        )
        assertFalse(trackBasis)
        assertEquals(2_000L, date)
    }
}
