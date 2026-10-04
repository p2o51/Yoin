package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingCoversHomeTest {

    @Test
    fun should_coverHome_when_phone() {
        assertTrue(nowPlayingCoversHome(NowPlayingPresentation.Phone))
    }

    @Test
    fun should_leaveHomeInView_when_panel() {
        assertFalse(nowPlayingCoversHome(NowPlayingPresentation.Panel))
    }

    @Test
    fun should_coverHome_when_enlarged() {
        assertTrue(nowPlayingCoversHome(NowPlayingPresentation.Enlarged))
    }

    @Test
    fun should_coverHome_when_dualPane() {
        assertTrue(nowPlayingCoversHome(NowPlayingPresentation.DualPane))
    }

    @Test
    fun should_coverHome_when_landscape() {
        assertTrue(nowPlayingCoversHome(NowPlayingPresentation.Landscape))
    }

    @Test
    fun should_coverHome_when_tabletop() {
        assertTrue(nowPlayingCoversHome(NowPlayingPresentation.Tabletop))
    }
}
