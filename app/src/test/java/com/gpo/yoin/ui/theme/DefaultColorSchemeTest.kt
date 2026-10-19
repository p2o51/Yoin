package com.gpo.yoin.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultColorSchemeTest {

    @Test
    fun should_keepTheWallpaperSeed_when_itHasAHue() {
        val blue = 0xFF3D6FD8.toInt()
        assertEquals(blue, pickDefaultSeed(blue))
    }

    @Test
    fun should_startFromYoinViolet_when_wallpaperIsGrey() {
        assertEquals(YoinSeedArgb, pickDefaultSeed(0xFF777779.toInt()))
    }

    @Test
    fun should_startFromYoinViolet_when_thereIsNoSystemSeed() {
        assertEquals(YoinSeedArgb, pickDefaultSeed(null))
    }
}
