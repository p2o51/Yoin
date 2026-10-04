package com.gpo.yoin.ui.memories.showcase

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The diary's ending (QA O2): the run-out groove only sits over the two numerals. */
class MemoryDiaryFootTest {

    private val zone = ZoneId.of("Asia/Tokyo")
    private val today = LocalDate.of(2026, 10, 5)
    private val mayFourteenth = LocalDate.of(2026, 5, 14).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun should_hide_run_out_when_album_was_only_visited() {
        // a visit-only album: no plays in Yoin (or none counted), so no numerals
        val none = diaryFooterStats(playsInYoin = null, firstHeardAt = null, zone = zone, today = today)
        val zero = diaryFooterStats(playsInYoin = 0, firstHeardAt = mayFourteenth, zone = zone, today = today)
        assertNull(none)
        assertNull(zero)
        assertFalse(diaryShowsRunOut(none))
        assertFalse(diaryShowsRunOut(zero))
    }

    @Test
    fun should_show_run_out_over_numerals_when_album_has_plays() {
        val stats = diaryFooterStats(playsInYoin = 14, firstHeardAt = mayFourteenth, zone = zone, today = today)
        assertNotNull(stats)
        assertEquals(14, stats!!.plays)
        assertEquals(144, stats.days)
        assertTrue(diaryShowsRunOut(stats))
    }
}
