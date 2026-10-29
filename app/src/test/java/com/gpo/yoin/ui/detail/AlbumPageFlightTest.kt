package com.gpo.yoin.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumPageFlightTest {

    @Test
    fun should_holdLastPlayOnItsLine_until_theHeroTextHasFaded() {
        assertEquals(0f, albumLastPlayFlightProgress(0f), 0f)
        assertEquals(0f, albumLastPlayFlightProgress(0.25f), 0f)
        assertEquals(1f, albumHeroPageFade(null), 0f)
    }

    @Test
    fun should_landLastPlay_when_thePageArrives() {
        assertEquals(1f, albumLastPlayFlightProgress(1f), 1e-6f)
        val mid = albumLastPlayFlightProgress(0.6f)
        assertTrue(mid > 0f && mid < 1f)
    }
}
