package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.gpo.yoin.player.CastState
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 断点交接 §3.3: on a 240dp column at font scale 1.3 no Now Playing control is
 * clipped or dropped — all three pills stay (folded to icons), and the
 * play-mode button keeps its full circle at the row's end.
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingControlsFitTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_keepAllThreePills_when_columnIs240dpAtFontScale1_3() {
        rule.setContent {
            YoinTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.3f)) {
                    Box(modifier = Modifier.width(NarrowColumn)) {
                        BottomPills(
                            onQueueClick = {},
                            onDevicesClick = {},
                            onWriteClick = {},
                            castState = CastState.Available,
                        )
                    }
                }
            }
        }
        listOf("Queue", "Devices", "Write").forEach { label ->
            val node = rule.onNodeWithContentDescription(label)
            node.assertIsDisplayed()
            assertTrue("$label ends inside the column", node.getBoundsInRoot().right <= NarrowColumn)
        }
    }

    @Test
    fun should_keepPlayModeWhole_when_columnIs240dpAtFontScale1_3() {
        rule.setContent {
            YoinTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.3f)) {
                    Box(modifier = Modifier.width(NarrowColumn)) {
                        PlaybackControls(
                            isPlaying = true,
                            onTogglePlayPause = {},
                            onSkipNext = {},
                            onSkipPrevious = {},
                            positionMs = 0L,
                            durationMs = 200_000L,
                            progress = 0f,
                            buffered = 0f,
                            onSeek = {},
                            playInteractionSource = remember { MutableInteractionSource() },
                            nextInteractionSource = remember { MutableInteractionSource() },
                            playPressed = false,
                            nextPressed = false,
                        )
                    }
                }
            }
        }
        val playMode = rule.onNodeWithContentDescription("Play mode")
        playMode.assertIsDisplayed()
        val bounds = playMode.getBoundsInRoot()
        assertTrue("play mode ends inside the column", bounds.right <= NarrowColumn)
        // A whole circle: 56, or the 48 step — never squeezed below it.
        val side = (bounds.right - bounds.left).value
        assertTrue("play mode is a whole control ($side dp)", side >= 47.5f && side <= 56.5f)
        rule.onNodeWithContentDescription("Skip next").assertIsDisplayed()
    }

    @Test
    fun should_keepFullControls_when_columnIsWide() {
        val wide = fitPlaybackControls(
            maxWidth = 400.dp,
            controlSize = 56.dp,
            playTextWidth = 72.dp,
            hasExpandToggle = false,
        )
        assertEquals(PlaybackControlsFit(56.dp, 1f), wide)
    }

    @Test
    fun should_shrinkPaddingFirst_then_controls_when_columnNarrows() {
        val padded = fitPlaybackControls(250.dp, 56.dp, playTextWidth = 72.dp, hasExpandToggle = false)
        assertEquals(PlaybackControlsFit(56.dp, 0.5f), padded)
        val stepped = fitPlaybackControls(220.dp, 56.dp, playTextWidth = 72.dp, hasExpandToggle = false)
        assertEquals(PlaybackControlsFit(48.dp, 0.5f), stepped)
    }

    private companion object {
        val NarrowColumn = 240.dp
    }
}
