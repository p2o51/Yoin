package com.gpo.yoin.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PullUpHandoffTest {

    @Test
    fun should_showOnlyTheHero_when_atRest() {
        assertEquals(1f, pullUpHeroAlpha(0f), 0.0001f)
        assertEquals(0f, pullUpHeroRise(0f), 0.0001f)
        assertEquals(0f, pullUpListAlpha(0f), 0.0001f)
        assertEquals(1f, pullUpListDrop(0f), 0.0001f)
    }

    @Test
    fun should_showOnlyTheListInPlace_when_pulledUp() {
        assertEquals(0f, pullUpHeroAlpha(1f), 0.0001f)
        assertEquals(1f, pullUpHeroRise(1f), 0.0001f)
        assertEquals(1f, pullUpListAlpha(1f), 0.0001f)
        assertEquals(0f, pullUpListDrop(1f), 0.0001f)
    }

    @Test
    fun should_neverStackTheFlowingTitlesOnTheRows_when_midway() {
        // The flowing titles are gone by half way; the list only starts after 0.35,
        // so the two are never both more than faintly visible at once.
        var e = 0f
        while (e <= 1f) {
            assertTrue("e=$e", minOf(pullUpHeroAlpha(e), pullUpListAlpha(e)) < 0.2f)
            e += 0.01f
        }
        assertEquals(0f, pullUpHeroAlpha(0.5f), 0.0001f)
    }

    @Test
    fun should_moveBothSidesUpOnly_when_pullingUp() {
        var e = 0f
        var lastRise = -1f
        var lastDrop = 2f
        while (e <= 1f) {
            assertTrue(pullUpHeroRise(e) >= lastRise)
            assertTrue(pullUpListDrop(e) <= lastDrop)
            lastRise = pullUpHeroRise(e)
            lastDrop = pullUpListDrop(e)
            e += 0.01f
        }
    }
}
