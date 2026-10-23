package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Constraints
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
 * PLAY + NEXT (TransportKeyGroup): rotating Now Playing crashed M3
 * ButtonGroup's measure policy in the shell's lookahead pass (Play vitals,
 * Pixel 11 Pro XL, versionCode 12). The hand-rolled group keeps its widths
 * whole in any pass. The squeeze test below does not reproduce that crash on
 * the old ButtonGroup under Robolectric; it only guards the new layout.
 */
@RunWith(RobolectricTestRunner::class)
class TransportKeyGroupTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun should_keepNaturalWidths_when_atRest() {
        assertArrayEquals(intArrayOf(160, 56), transportKeyWidths(intArrayOf(160, 56), 8, 400) { 0f })
    }

    @Test
    fun should_growPressedKeyIntoItsNeighbour_when_held() {
        val play = transportKeyWidths(intArrayOf(200, 100), 8, 400) { if (it == 0) 1f else 0f }
        assertArrayEquals(intArrayOf(230, 70), play)
        val next = transportKeyWidths(intArrayOf(200, 100), 8, 400) { if (it == 1) 1f else 0f }
        assertArrayEquals(intArrayOf(185, 115), next)
        assertEquals(300, next.sum())
    }

    @Test
    fun should_shrinkPlayFirst_when_keysOverrunTheSpace() {
        assertArrayEquals(intArrayOf(92, 100), transportKeyWidths(intArrayOf(200, 100), 8, 200) { 0f })
        assertArrayEquals(intArrayOf(0, 42), transportKeyWidths(intArrayOf(200, 100), 8, 50) { 0f })
    }

    @Test
    fun should_neverGoNegative_when_squeezedOrOvershooting() {
        assertTrue(transportKeyWidths(intArrayOf(10, 10), 8, 400) { 9f }.all { it >= 0 })
        assertArrayEquals(intArrayOf(0, 0), transportKeyWidths(intArrayOf(200, 100), 8, 0) { 1f })
        assertArrayEquals(intArrayOf(0, 0), transportKeyWidths(intArrayOf(200, 100), 8, -40) { 0f })
        assertArrayEquals(intArrayOf(200, 100), transportKeyWidths(intArrayOf(200, 100), 8, Constraints.Infinity) { 0f })
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun should_survive_when_columnIsSqueezedInsideASharedTransitionLayout() {
        var columnWidth by mutableStateOf(400.dp)
        composeRule.setContent {
            YoinTheme {
                SharedTransitionLayout {
                    Box(modifier = Modifier.width(columnWidth)) {
                        PlaybackControls(
                            isPlaying = true,
                            onTogglePlayPause = {},
                            onSkipNext = {},
                            onSkipPrevious = {},
                            positionMs = 30_000L,
                            durationMs = 180_000L,
                            progress = 0.2f,
                            buffered = 0.5f,
                            onSeek = {},
                            playInteractionSource = remember { MutableInteractionSource() },
                            nextInteractionSource = remember { MutableInteractionSource() },
                            playPressed = false,
                            live = false,
                        )
                    }
                }
            }
        }
        for (width in listOf(0.dp, 12.dp, 90.dp, 200.dp, 400.dp)) {
            columnWidth = width
            composeRule.waitForIdle()
        }
    }
}
