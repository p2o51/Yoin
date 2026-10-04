package com.gpo.yoin.ui.experience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFollowsColumnsTest {

    @Test
    fun should_keepPhoneGrid_when_compact() {
        assertFalse(previewYoinWindowInfo(widthDp = 411, heightDp = 914).contentFollowsColumns)
        assertFalse(previewYoinWindowInfo(widthDp = 360, heightDp = 780).contentFollowsColumns)
    }

    @Test
    fun should_keepPhoneGrid_when_landscapePhoneReadsWide() {
        val landscapePhone = previewYoinWindowInfo(widthDp = 914, heightDp = 411)
        assertEquals(LayoutMode.Wide, landscapePhone.layoutMode)
        assertTrue(landscapePhone.isCompactHeight)
        assertFalse(landscapePhone.contentFollowsColumns)
        // A short Medium-width window is a landscape phone too.
        assertFalse(previewYoinWindowInfo(widthDp = 700, heightDp = 400).contentFollowsColumns)
    }

    @Test
    fun should_followColumns_when_mediumTabletopOrWide() {
        // Pixel Tablet portrait (Medium) and landscape (Wide).
        assertTrue(previewYoinWindowInfo(widthDp = 800, heightDp = 1280).contentFollowsColumns)
        assertTrue(previewYoinWindowInfo(widthDp = 1280, heightDp = 800).contentFollowsColumns)
        val tabletop = YoinWindowInfo(
            layoutMode = LayoutMode.Tabletop,
            isWidthAtLeastMedium = true,
            isHeightAtLeastMedium = true,
            hingeBounds = null,
            chromeForm = ShellChromeForm.PortraitBar,
        )
        assertTrue(tabletop.contentFollowsColumns)
    }

    @Test
    fun should_followColumns_when_compactAtLeast480() {
        // The feed's width class, not the mode: a 480dp Compact container is N = 3.
        assertTrue(previewYoinWindowInfo(widthDp = 480, heightDp = 1000).contentFollowsColumns)
        assertFalse(previewYoinWindowInfo(widthDp = 479, heightDp = 1000).contentFollowsColumns)
    }
}
