package com.gpo.yoin.ui.nowplaying

import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LayoutMode
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingPresentationTest {

    @Test
    fun should_sizeThePanelLikeAPhone_when_foldOrTabletPortrait() {
        assertEquals(360.dp, nowPlayingPanelWidth(690.dp))
        assertEquals(400.dp, nowPlayingPanelWidth(800.dp))
        assertEquals(420.dp, nowPlayingPanelWidth(900.dp))
    }

    @Test
    fun should_openThePanel_when_mediumFullWindow() {
        assertEquals(
            NowPlayingPresentation.Panel,
            resolveNowPlayingPresentation(LayoutMode.Medium, true, embedded = false, windowWidth = 800.dp, fullscreen = false),
        )
        assertEquals(
            NowPlayingPresentation.Enlarged,
            resolveNowPlayingPresentation(LayoutMode.Medium, true, embedded = false, windowWidth = 800.dp, fullscreen = true),
        )
    }

    @Test
    fun should_goStraightToTheEnlargedPhone_when_noRoomBesideThePanel() {
        // 640 − 360 = 280 < 320 (a split-screen half).
        assertFalse(canOpenNowPlayingPanel(LayoutMode.Medium, true, embedded = false, windowWidth = 640.dp))
        assertEquals(
            NowPlayingPresentation.Enlarged,
            resolveNowPlayingPresentation(LayoutMode.Medium, true, embedded = false, windowWidth = 640.dp, fullscreen = false),
        )
    }

    @Test
    fun should_fillThePane_when_embedded() {
        assertFalse(canOpenNowPlayingPanel(LayoutMode.Medium, true, embedded = true, windowWidth = 606.dp))
        assertEquals(
            NowPlayingPresentation.Enlarged,
            resolveNowPlayingPresentation(LayoutMode.Medium, true, embedded = true, windowWidth = 606.dp, fullscreen = false),
        )
        assertEquals(
            NowPlayingPresentation.Phone,
            resolveNowPlayingPresentation(LayoutMode.Compact, true, embedded = true, windowWidth = 576.dp, fullscreen = false),
        )
    }

    @Test
    fun should_keepTwoColumns_onlyWhenWideAndTall() {
        assertEquals(
            NowPlayingPresentation.DualPane,
            resolveNowPlayingPresentation(LayoutMode.Wide, true, embedded = false, windowWidth = 1280.dp, fullscreen = false),
        )
        // A landscape handset is Wide by width but short: LandscapeNP.
        assertEquals(
            NowPlayingPresentation.Landscape,
            resolveNowPlayingPresentation(LayoutMode.Wide, false, embedded = false, windowWidth = 844.dp, fullscreen = false),
        )
        // A short AND narrow split half stays on the phone column.
        assertEquals(
            NowPlayingPresentation.Phone,
            resolveNowPlayingPresentation(LayoutMode.Compact, false, embedded = false, windowWidth = 411.dp, fullscreen = false),
        )
    }

    @Test
    fun should_landInTheEnlargedPhone_when_unfoldingWithNowPlayingOpen() {
        assertTrue(fullscreenAfterLayoutChange(LayoutMode.Compact, LayoutMode.Medium, expanded = true, wasFullscreen = false))
        // Any other arrival in Medium starts as the panel.
        assertFalse(fullscreenAfterLayoutChange(LayoutMode.Wide, LayoutMode.Medium, expanded = true, wasFullscreen = true))
        assertFalse(fullscreenAfterLayoutChange(LayoutMode.Compact, LayoutMode.Medium, expanded = false, wasFullscreen = false))
    }

    @Test
    fun should_showTheLyricHint_atMostOnceADay() {
        val today = LocalDate.of(2026, 9, 30)
        assertTrue(lyricIdleHintAllowed(null, today))
        assertFalse(lyricIdleHintAllowed(today.toEpochDay(), today))
        assertTrue(lyricIdleHintAllowed(today.toEpochDay(), today.plusDays(1)))
    }

    @Test
    fun should_claimTheHintOnce_when_askedTwiceTheSameDay() {
        val store = LyricHintStore.InMemory()
        val today = LocalDate.of(2026, 9, 30)
        assertTrue(lyricIdleHintAllowed(store.lastShownEpochDay(), today))
        store.markShown(today.toEpochDay())
        assertFalse(lyricIdleHintAllowed(store.lastShownEpochDay(), today))
    }
}
