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
            resolveNowPlayingPresentation(LayoutMode.Medium, true, windowWidth = 800.dp, fullscreen = false),
        )
        assertEquals(
            NowPlayingPresentation.Enlarged,
            resolveNowPlayingPresentation(LayoutMode.Medium, true, windowWidth = 800.dp, fullscreen = true),
        )
    }

    @Test
    fun should_goStraightToTheEnlargedPhone_when_noRoomBesideThePanel() {
        // 640 − 360 = 280 < 320 (a split-screen half).
        assertFalse(canOpenNowPlayingPanel(LayoutMode.Medium, true, windowWidth = 640.dp))
        assertEquals(
            NowPlayingPresentation.Enlarged,
            resolveNowPlayingPresentation(LayoutMode.Medium, true, windowWidth = 640.dp, fullscreen = false),
        )
    }

    @Test
    fun should_neverOpenAPanel_when_compact() {
        assertFalse(canOpenNowPlayingPanel(LayoutMode.Compact, true, windowWidth = 599.dp))
        assertEquals(
            NowPlayingPresentation.Phone,
            resolveNowPlayingPresentation(LayoutMode.Compact, true, windowWidth = 576.dp, fullscreen = false),
        )
        // The flag is inert on a handset: there is no Full state to toggle to.
        assertEquals(
            NowPlayingPresentation.Phone,
            resolveNowPlayingPresentation(LayoutMode.Compact, true, windowWidth = 576.dp, fullscreen = true),
        )
    }

    @Test
    fun should_openThePanelFirst_when_wideAndTall() {
        // Pixel Tablet landscape: 1280 − 420 = 860 beside the panel.
        assertTrue(canOpenNowPlayingPanel(LayoutMode.Wide, true, windowWidth = 1280.dp))
        assertEquals(
            NowPlayingPresentation.Panel,
            resolveNowPlayingPresentation(LayoutMode.Wide, true, windowWidth = 1280.dp, fullscreen = false),
        )
        assertEquals(
            NowPlayingPresentation.DualPane,
            resolveNowPlayingPresentation(LayoutMode.Wide, true, windowWidth = 1280.dp, fullscreen = true),
        )
    }

    @Test
    fun should_keepTwoColumns_when_wideButNoRoomForAPanel() {
        // Width alone can never deny a Wide window its panel (840 − 420 = 420
        // ≥ 320); only height can. A landscape handset is Wide by width but
        // short: LandscapeNP, no panel, no two columns.
        assertFalse(canOpenNowPlayingPanel(LayoutMode.Wide, false, windowWidth = 844.dp))
        assertEquals(
            NowPlayingPresentation.Landscape,
            resolveNowPlayingPresentation(LayoutMode.Wide, false, windowWidth = 844.dp, fullscreen = false),
        )
        // Rotating a tablet between portrait (Medium) and landscape (Wide)
        // keeps the user's Panel / Full choice; growing out of Compact lands
        // in the Full state.
        assertTrue(fullscreenAfterLayoutChange(LayoutMode.Medium, LayoutMode.Wide, expanded = true, wasFullscreen = true))
        assertTrue(fullscreenAfterLayoutChange(LayoutMode.Compact, LayoutMode.Wide, expanded = true, wasFullscreen = false))
        assertFalse(fullscreenAfterLayoutChange(LayoutMode.Wide, LayoutMode.Medium, expanded = true, wasFullscreen = false))
    }

    @Test
    fun should_keepTwoColumns_onlyWhenWideAndTall() {
        assertEquals(
            NowPlayingPresentation.DualPane,
            resolveNowPlayingPresentation(LayoutMode.Wide, true, windowWidth = 1280.dp, fullscreen = true),
        )
        // A landscape handset is Wide by width but short: LandscapeNP.
        assertEquals(
            NowPlayingPresentation.Landscape,
            resolveNowPlayingPresentation(LayoutMode.Wide, false, windowWidth = 844.dp, fullscreen = true),
        )
        // A short AND narrow split half stays on the phone column.
        assertEquals(
            NowPlayingPresentation.Phone,
            resolveNowPlayingPresentation(LayoutMode.Compact, false, windowWidth = 411.dp, fullscreen = false),
        )
    }

    @Test
    fun should_landInTheEnlargedPhone_when_unfoldingWithNowPlayingOpen() {
        assertTrue(fullscreenAfterLayoutChange(LayoutMode.Compact, LayoutMode.Medium, expanded = true, wasFullscreen = false))
        // Medium ⇄ Wide keeps the user's choice either way.
        assertTrue(fullscreenAfterLayoutChange(LayoutMode.Wide, LayoutMode.Medium, expanded = true, wasFullscreen = true))
        assertFalse(fullscreenAfterLayoutChange(LayoutMode.Medium, LayoutMode.Wide, expanded = true, wasFullscreen = false))
        // Any other arrival in a panel-capable tier starts as the panel.
        assertFalse(fullscreenAfterLayoutChange(LayoutMode.Tabletop, LayoutMode.Medium, expanded = true, wasFullscreen = true))
        assertFalse(fullscreenAfterLayoutChange(LayoutMode.Compact, LayoutMode.Medium, expanded = false, wasFullscreen = false))
        // Outside those tiers the flag is inert and kept as it was.
        assertTrue(fullscreenAfterLayoutChange(LayoutMode.Medium, LayoutMode.Compact, expanded = true, wasFullscreen = true))
    }

    @Test
    fun should_skipThePanel_when_twoColumnsCannotFitBesideIt() {
        // Pixel Tablet landscape: 1280 − 420 = 860 ≥ 744 → the panel opens
        // beside the shell and the detail column.
        val twoColumns = 360.dp * 2 + 24.dp
        assertTrue(canOpenNowPlayingPanel(LayoutMode.Wide, true, windowWidth = 1280.dp, minContentWidth = twoColumns))
        // A 1000dp desktop window: 1000 − 420 = 580 < 744 → straight to the
        // two-column player while a column is open; the panel when it is not.
        assertFalse(canOpenNowPlayingPanel(LayoutMode.Wide, true, windowWidth = 1000.dp, minContentWidth = twoColumns))
        assertEquals(
            NowPlayingPresentation.DualPane,
            resolveNowPlayingPresentation(LayoutMode.Wide, true, 1000.dp, fullscreen = false, minContentWidth = twoColumns),
        )
        assertEquals(
            NowPlayingPresentation.Panel,
            resolveNowPlayingPresentation(LayoutMode.Wide, true, 1000.dp, fullscreen = false),
        )
        // A smaller floor never undercuts the phone column.
        assertFalse(canOpenNowPlayingPanel(LayoutMode.Medium, true, windowWidth = 640.dp, minContentWidth = 0.dp))
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
