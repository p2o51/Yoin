package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The expanded lyrics list, laid out for real (2026-10-05 device-QA fixes). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class LyricsFullscreenPaneLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private val duration = 200_000L
    private val current = (0 until 20).map { LyricLine(startMs = 10_000L + it * 5_000L, text = "Current line $it") }
    private val nextTitle = "Second Song"

    private fun nextSong(lines: Int) = UpNextLyrics(
        songId = "second",
        title = nextTitle,
        artist = "Night QA Band",
        lines = (0 until lines).map { LyricLine(startMs = 8_000L + it * 4_000L, text = "Next line $it") },
    )

    private fun handoffMs(upNext: UpNextLyrics) = upNextTimingFor(current, upNext, duration)!!.handoffAtMs

    @Test
    fun should_swapInPlace_when_theNextSongsLyricsAreShorterThanTheWindow() {
        assertEquals(0f, handoverJump(nextLines = 5).value, 1f)
    }

    @Test
    fun should_swapInPlace_when_theNextSongsLyricsFillTheWindow() {
        assertEquals(0f, handoverJump(nextLines = 30).value, 1f)
    }

    /** Next title's top before the song change (handed over) minus after it (own list at rest). */
    private fun handoverJump(nextLines: Int): Dp {
        val upNext = nextSong(nextLines)
        var songId by mutableStateOf("first")
        var position by mutableLongStateOf(handoffMs(upNext) - 3_000L)
        rule.setContent {
            YoinTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    val first = songId == "first"
                    LyricsFullscreenPane(
                        lyrics = if (first) current else upNext.lines,
                        positionMs = { position },
                        loading = false,
                        showTranslation = false,
                        autoScrollEnabled = true,
                        recenterRequestKey = 0,
                        onUserScroll = {},
                        onSeekToMs = {},
                        trackKey = songId,
                        queueIndex = if (first) 0 else 1,
                        songTitle = if (first) "First Song" else nextTitle,
                        artist = "Night QA Band",
                        upNext = if (first) upNext else null,
                        durationMs = duration,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { position = handoffMs(upNext) + 100L }
        rule.mainClock.advanceTimeBy(5_000L)
        rule.waitForIdle()
        val handedOver = rule.onNodeWithText(nextTitle).getBoundsInRoot().top

        rule.runOnIdle {
            songId = "second"
            position = 0L
        }
        rule.mainClock.advanceTimeBy(5_000L)
        rule.waitForIdle()
        rule.onAllNodesWithText(nextTitle).assertCountEquals(1)
        val atRest = rule.onNodeWithText(nextTitle).getBoundsInRoot().top
        return handedOver - atRest
    }

    @Test
    fun should_keepLinesOutOfTheCaptionBand_when_selectingWithoutMovingThem() {
        var selecting by mutableStateOf(false)
        rule.setContent {
            YoinTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    LyricsFullscreenPane(
                        lyrics = current,
                        positionMs = { 80_000L },
                        loading = false,
                        showTranslation = false,
                        autoScrollEnabled = true,
                        recenterRequestKey = 0,
                        onUserScroll = {},
                        onSeekToMs = {},
                        songTitle = "First Song",
                        artist = "Night QA Band",
                        durationMs = duration,
                        selecting = selecting,
                    )
                }
            }
        }
        rule.waitForIdle()
        val probe = "Current line 12"
        val before = rule.onNodeWithText(probe).getUnclippedBoundsInRoot()

        rule.runOnIdle { selecting = true }
        rule.mainClock.advanceTimeBy(2_000L)
        rule.waitForIdle()

        val after = rule.onNodeWithText(probe).getUnclippedBoundsInRoot()
        assertEquals("lines keep their x", before.left.value, after.left.value, 0.5f)
        assertEquals("lines keep their y", before.top.value, after.top.value, 0.5f)

        val captionBottom = rule.onNodeWithText(lyricsSelectionLabel(0)).getBoundsInRoot().bottom
        current.indices.forEach { i ->
            val nodes = rule.onAllNodesWithText("Current line $i").fetchSemanticsNodes()
            nodes.forEach { node ->
                val visible = node.boundsInRoot
                if (visible.height > 0f) {
                    assertTrue(
                        "line $i is drawn under the caption (top ${visible.top}px, caption bottom $captionBottom)",
                        visible.top >= with(rule.density) { captionBottom.toPx() } - 0.5f,
                    )
                }
            }
        }
    }
}
