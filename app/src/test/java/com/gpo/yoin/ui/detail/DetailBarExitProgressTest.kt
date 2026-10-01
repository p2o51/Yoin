package com.gpo.yoin.ui.detail

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailBarExitProgressTest {
    @Test
    fun should_keepBarOffscreen_untilNowPlayingPushRevealsContent() = runTest {
        val intro = DetailEnterIntroState(false)
        val back = DetailBackCollapseState()
        assertEquals(1f, detailBarExitProgress(intro, back), 0f)
        intro.pageVisible = true
        intro.slide.snapTo(0.6f)
        assertEquals(0.6f, detailBarExitProgress(intro, back), 0.001f)
        // Interrupting entry does not rewind the partially visible bar.
        back.chased.snapTo(0.5f)
        assertEquals(0.8f, detailBarExitProgress(intro, back), 0.001f)
        back.chased.snapTo(1f)
        assertEquals(1f, detailBarExitProgress(intro, back), 0.001f)
    }

    @Test
    fun should_returnToRestingBar_whenBackIsCancelledAfterEnter() = runTest {
        val intro = DetailEnterIntroState(true)
        val back = DetailBackCollapseState()
        back.chased.snapTo(0.4f)
        assertEquals(0.4f, detailBarExitProgress(intro, back), 0.001f)
        back.chased.snapTo(0f)
        assertEquals(0f, detailBarExitProgress(intro, back), 0.001f)
    }
}
