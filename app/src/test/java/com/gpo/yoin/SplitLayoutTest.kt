package com.gpo.yoin

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitLayoutTest {

    @Test
    fun should_giveSettingsListAbout420_when_tabletLandscape() {
        assertEquals(420f, settingsSplitRatio(1280f) * 1280f, 1f)
    }

    @Test
    fun should_keepSettingsListBetweenAThirdAndFortyPercent_when_windowsAreExtreme() {
        assertEquals(0.4f, settingsSplitRatio(840f), 0.0001f)
        assertEquals(0.3f, settingsSplitRatio(2000f), 0.0001f)
    }
}
