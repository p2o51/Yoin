package com.gpo.yoin.ui.navigation.pane

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.YoinWindowInfo
import com.gpo.yoin.ui.experience.forPaneWidth

class PaneSplitTest {

    @Test
    fun should_giveTheShellSixHundred_when_tabletLandscape() {
        // TabletSplit: shell 600 | gutter | detail 656 at 1280.
        val budget = resolvePaneBudget(1280.dp, defaultShellFraction(1280.dp))
        assertEquals(600f, budget.shellWidth.value, 0.5f)
        assertEquals(1280f - 24f - 600f, budget.paneWidth.value, 0.5f)
    }

    @Test
    fun should_splitPointFortyFive_when_windowIsJustWide() {
        val budget = resolvePaneBudget(840.dp, defaultShellFraction(840.dp))
        assertEquals((840f - 24f) * 0.45f, budget.shellWidth.value, 0.5f)
        assertTrue(budget.paneWidth.value >= PaneMinWidth.value)
    }

    @Test
    fun should_keepBothColumnsAtLeastAPhone_when_dragged() {
        val narrowShell = resolvePaneBudget(1280.dp, 0.1f)
        assertEquals(PaneMinWidth.value, narrowShell.shellWidth.value, 0.5f)
        val narrowPane = resolvePaneBudget(1280.dp, 0.95f)
        assertEquals(PaneMinWidth.value, narrowPane.paneWidth.value, 0.5f)
    }

    @Test
    fun should_moveTheDividerOneToOne_when_dragging() {
        val split = PaneSplitState()
        val before = resolvePaneBudget(1280.dp, split.fractionFor(1280.dp)).shellWidth
        split.dragBy(100.dp, 1280.dp)
        val after = resolvePaneBudget(1280.dp, split.fractionFor(1280.dp)).shellWidth
        assertEquals(100f, (after - before).value, 0.5f)
    }

    @Test
    fun should_reclampADraggedFraction_when_theWindowNarrows() {
        val split = PaneSplitState()
        split.dragBy(400.dp, 1280.dp) // shell ≈ 1000 of 1256
        val budget = resolvePaneBudget(840.dp, split.fractionFor(840.dp))
        assertTrue(budget.paneWidth.value >= PaneMinWidth.value - 0.5f)
    }

    @Test
    fun should_needTwoPhoneColumnsAndTheGutter_when_besideTheNowPlayingPanel() {
        assertEquals(744f, TwoColumnsMinWidth.value, 0.01f)
    }

    @Test
    fun should_keepTheSixHundredShellColumnMedium_when_anyWindowFrom1080() {
        val window = YoinWindowInfo(
            layoutMode = LayoutMode.Wide,
            isWidthAtLeastMedium = true,
            isHeightAtLeastMedium = true,
            hingeBounds = null,
        )
        var w = 1080f
        while (w <= 1360f) {
            val budget = resolvePaneBudget(w.dp, defaultShellFraction(w.dp))
            val shell = w.dp - (budget.paneWidth + PaneGutterWidth)
            assertEquals("window $w", LayoutMode.Medium, window.forPaneWidth(shell).layoutMode)
            w += 0.5f
        }
    }
}
