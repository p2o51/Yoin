package com.gpo.yoin.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryTitleTest {

    @Test
    fun should_prefer_coverage_when_any_track_rated() {
        assertEquals(
            "9 of 14 rated",
            deterministicMemoryTitle(
                ratedTrackCount = 9,
                totalTrackCount = 14,
                noteCount = 3,
                hasAlbumReview = true,
            ),
        )
    }

    @Test
    fun should_fall_to_notes_when_nothing_rated() {
        assertEquals(
            "Kept for 4 notes",
            deterministicMemoryTitle(
                ratedTrackCount = 0,
                totalTrackCount = 9,
                noteCount = 4,
                hasAlbumReview = false,
            ),
        )
    }

    @Test
    fun should_use_singular_noun_when_single_note() {
        assertEquals(
            "Kept for 1 note",
            deterministicMemoryTitle(
                ratedTrackCount = 0,
                totalTrackCount = 0,
                noteCount = 1,
                hasAlbumReview = false,
            ),
        )
    }

    @Test
    fun should_name_review_when_only_review_exists() {
        assertEquals(
            "Your album review",
            deterministicMemoryTitle(
                ratedTrackCount = 0,
                totalTrackCount = 12,
                noteCount = 0,
                hasAlbumReview = true,
            ),
        )
    }

    @Test
    fun should_never_be_blank_when_all_signals_absent() {
        assertEquals(
            "From your listening",
            deterministicMemoryTitle(
                ratedTrackCount = 0,
                totalTrackCount = 0,
                noteCount = 0,
                hasAlbumReview = false,
            ),
        )
    }
}
