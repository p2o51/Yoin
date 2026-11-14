package com.gpo.yoin.ui.home

import com.gpo.yoin.ui.experience.previewYoinWindowInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShelfEdgeTest {

    @Test
    fun should_notFadeShelves_when_phonePortrait() {
        assertFalse(homeShelvesFadeAtEdges(previewYoinWindowInfo(widthDp = 411, heightDp = 914)))
        assertFalse(homeShelvesFadeAtEdges(previewYoinWindowInfo(widthDp = 360, heightDp = 780)))
    }

    @Test
    fun should_notFadeShelves_when_phoneLandscape() {
        assertFalse(homeShelvesFadeAtEdges(previewYoinWindowInfo(widthDp = 914, heightDp = 411)))
    }

    @Test
    fun should_keepFade_when_largeScreen() {
        // Pixel Tablet portrait (Medium) and landscape (Expanded), foldable inner screen.
        assertTrue(homeShelvesFadeAtEdges(previewYoinWindowInfo(widthDp = 800, heightDp = 1280)))
        assertTrue(homeShelvesFadeAtEdges(previewYoinWindowInfo(widthDp = 1280, heightDp = 800)))
        assertTrue(homeShelvesFadeAtEdges(previewYoinWindowInfo(widthDp = 832, heightDp = 900)))
    }
}
