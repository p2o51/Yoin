package com.gpo.yoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLayoutTest {

    @Test
    fun should_keepTheXmlRatio_when_windowIsNarrowerThan1080() {
        assertEquals(0.45f, shellSplitRatio(840f), 0.0001f)
        assertEquals(0.45f, shellSplitRatio(1000f), 0.0001f)
    }

    @Test
    fun should_giveTheShellAtLeast600_when_tabletLandscape() {
        val ratio = shellSplitRatio(1280f)
        // Even after a 10dp divider takes its share, the shell pane is ≥ 600.
        assertTrue(ratio * 1280f - 10f >= 600f)
        assertTrue((1f - ratio) * 1280f >= 480f)
    }

    @Test
    fun should_followPointFortyFive_when_windowIsDesktopWide() {
        // 1440 → shell ≈ 648, detail ≈ 792 (both read Medium, §14.2).
        assertEquals(648f, shellSplitRatio(1440f) * 1440f, 0.5f)
    }

    @Test
    fun should_giveSettingsListAbout420_when_tabletLandscape() {
        assertEquals(420f, settingsSplitRatio(1280f) * 1280f, 1f)
    }
}
