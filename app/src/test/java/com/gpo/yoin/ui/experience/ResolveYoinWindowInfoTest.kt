package com.gpo.yoin.ui.experience

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveYoinWindowInfoTest {

    @Test
    fun should_mapWidthBreakpoints_when_flat() {
        assertEquals(LayoutMode.Compact, resolve(599.9f, 900f).layoutMode)
        assertEquals(LayoutMode.Medium, resolve(600f, 900f).layoutMode)
        assertEquals(LayoutMode.Medium, resolve(839.9f, 900f).layoutMode)
        assertEquals(LayoutMode.Wide, resolve(840f, 900f).layoutMode)
    }

    @Test
    fun should_readTabletop_beforeWidth_when_halfOpenedHorizontalHinge() {
        val hinge = Rect(0f, 1000f, 2000f, 1010f)
        val info = resolveYoinWindowInfo(1000.dp, 900.dp, isTabletop = true, hingeBounds = hinge)
        assertEquals(LayoutMode.Tabletop, info.layoutMode)
        assertEquals(hinge, info.hingeBounds)
    }

    @Test
    fun should_beEqual_when_aResizeCrossesNoStep() {
        // The derived state only hands a new value on when this changes, so a
        // drag inside one column step must resolve to an equal value.
        assertEquals(resolve(700f, 900f), resolve(700.5f, 900.5f))
    }

    @Test
    fun should_matchPreviewRules() {
        assertEquals(resolve(411f, 891f), previewYoinWindowInfo(widthDp = 411, heightDp = 891))
        assertEquals(resolve(844f, 390f), previewYoinWindowInfo(widthDp = 844, heightDp = 390))
    }

    private fun resolve(width: Float, height: Float) =
        resolveYoinWindowInfo(width.dp, height.dp, isTabletop = false, hingeBounds = null)
}
