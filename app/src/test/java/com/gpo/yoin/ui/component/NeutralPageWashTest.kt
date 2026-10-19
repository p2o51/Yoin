package com.gpo.yoin.ui.component

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class NeutralPageWashTest {

    // Distinct stops, like a dynamic dark scheme where the three differ by a
    // few levels — exactly the step the Discord report saw at the panel corner.
    private val scheme = darkColorScheme(
        surfaceContainer = Color(0xFF081B20),
        background = Color(0xFF040F12),
        surfaceContainerLow = Color(0xFF05141A),
    )

    @Test
    fun should_runSurfaceContainerBackgroundSurfaceContainerLow_when_pageWashIsNeutral() {
        assertEquals(
            listOf(scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow),
            neutralPageWashColors(scheme),
        )
    }

    @Test
    fun should_matchSeamField_when_shellPaintsBehindNowPlayingPanel() {
        // The shell behind the panel's corners and the seams' bottom field read
        // the same stops, so neither drifts from the pages' gradient alone.
        assertEquals(
            listOf(scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow),
            SeamBackground(neutralPageWashColors(scheme)).colors,
        )
    }
}
