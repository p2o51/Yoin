package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The lyric keys' group (LyricsKeyRow): rotating with the lyrics page open
 * crashed M3 ButtonGroup's measure policy in the shell's lookahead pass
 * (2026-10-21). The hand-rolled row keeps its widths whole in any pass.
 */
@RunWith(RobolectricTestRunner::class)
class LyricsKeyRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun atRestEveryKeyKeepsItsSide() {
        assertArrayEquals(intArrayOf(100, 100, 100, 100), lyricsKeyWidths(4, 100) { 0f })
    }

    @Test
    fun aMiddleKeyTakesHalfFromEachNeighbour() {
        val widths = lyricsKeyWidths(4, 200) { if (it == 1) 1f else 0f }
        assertArrayEquals(intArrayOf(185, 230, 185, 200), widths)
        assertEquals(800, widths.sum())
    }

    @Test
    fun anEndKeyTakesAllFromTheOneBesideIt() {
        assertArrayEquals(intArrayOf(115, 85, 100, 100), lyricsKeyWidths(4, 100) { if (it == 0) 1f else 0f })
        assertArrayEquals(intArrayOf(100, 100, 85, 115), lyricsKeyWidths(4, 100) { if (it == 3) 1f else 0f })
    }

    @Test
    fun noWidthGoesNegative() {
        // Every key held past an overshoot: neighbours give up more than they have.
        val widths = lyricsKeyWidths(4, 10) { 9f }
        assertTrue(widths.all { it >= 0 })
        assertArrayEquals(intArrayOf(0), lyricsKeyWidths(1, 0) { 1f })
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun survivesASqueezedColumnInsideASharedTransitionLayout() {
        var columnWidth by mutableStateOf(320.dp)
        composeRule.setContent {
            YoinTheme {
                SharedTransitionLayout {
                    Box(modifier = Modifier.width(columnWidth).height(44.dp)) {
                        LyricsActionBar(
                            actionInFlight = null,
                            canTranslate = true,
                            canSelect = true,
                            canRecenter = true,
                            onSearchClick = {},
                            onTranslateClick = {},
                            onSelectClick = {},
                            onRecenterClick = {},
                            iconSize = 40.dp,
                        )
                    }
                }
            }
        }
        for (width in listOf(0.dp, 12.dp, 90.dp, 320.dp)) {
            columnWidth = width
            composeRule.waitForIdle()
        }
    }
}
