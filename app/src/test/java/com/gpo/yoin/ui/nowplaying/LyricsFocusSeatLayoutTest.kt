package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The expanded lyric list seats the line being sung in the middle of what
 * reads crisp, laid out for real (ticket 2, 2026-10-12: a Pixel 10 Pro in
 * landscape showed the active line 59% down a ~227dp window, its wrap and
 * translation in the bottom fade).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w952dp-h427dp")
class LyricsFocusSeatLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private val wrapped = "Under iron rain rest my head under arrowheads, everything fountainhead from the arrowhead"
    private val wrappedNext = "Hold the line where the rivers bend and the evening keeps its promise to the quiet hills"

    // Line 12 and 13 are long enough to wrap in a 560dp column; every line has a translation.
    private val lyrics = (0 until 24).map { i ->
        LyricLine(
            startMs = 10_000L + i * 5_000L,
            text = when (i) {
                12 -> wrapped
                13 -> wrappedNext
                else -> "Line $i"
            },
            translation = "Translation $i"
        )
    }

    private fun lineMs(index: Int) = 10_000L + index * 5_000L + 1_000L

    @Test
    fun should_keepWrappedLineAndTranslationInCleanBand_when_landscapePhoneWindow() {
        val window = DpSize(560.dp, 227.dp)
        show(window, positionMs = lineMs(12))

        assertSeated(window, wrapped, "Translation 12")
    }

    @Test
    fun should_seatNextWrappedLine_when_lineAdvancesInLandscapePhoneWindow() {
        val window = DpSize(560.dp, 227.dp)
        var position by mutableLongStateOf(lineMs(11))
        show(window, positionMs = { position })

        rule.runOnIdle { position = lineMs(13) }
        rule.mainClock.advanceTimeBy(5_000L)
        rule.waitForIdle()

        assertSeated(window, wrappedNext, "Translation 13")
    }

    @Test
    fun should_keepFocusLineInPlace_when_translationTogglesInLandscapePhoneWindow() {
        var showTranslation by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                Box(Modifier.size(560.dp, 227.dp)) {
                    LyricsFullscreenPane(
                        lyrics = lyrics,
                        positionMs = { lineMs(12) },
                        loading = false,
                        showTranslation = showTranslation,
                        autoScrollEnabled = true,
                        recenterRequestKey = 0,
                        onUserScroll = {},
                        onSeekToMs = {},
                        songTitle = "5 Star Crest",
                        artist = "Bladee",
                        durationMs = 200_000L
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(2_000L)
        val restTop = rule.onNodeWithText(wrapped).getUnclippedBoundsInRoot().top

        rule.runOnIdle { showTranslation = true }
        var maxDrift = 0.dp
        repeat(45) {
            rule.mainClock.advanceTimeByFrame()
            val top = rule.onNodeWithText(wrapped).getUnclippedBoundsInRoot().top
            maxDrift = maxOf(maxDrift, abs((top - restTop).value).dp)
        }
        assertTrue("focus line drifted $maxDrift", maxDrift < 2.dp)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun should_centreActiveLine_when_windowTall() {
        val window = DpSize(400.dp, 640.dp)
        show(window, positionMs = lineMs(8))

        val (top, bottom) = rowBounds("Line 8", "Translation 8")
        assertEquals("row centre at the window's middle", window.height.value / 2f, (top + bottom).value / 2f, 1.5f)
    }

    private fun show(window: DpSize, positionMs: Long) = show(window) { positionMs }

    private fun show(window: DpSize, positionMs: () -> Long) {
        rule.setContent {
            YoinTheme {
                Box(Modifier.size(window)) {
                    LyricsFullscreenPane(
                        lyrics = lyrics,
                        positionMs = positionMs,
                        loading = false,
                        showTranslation = true,
                        autoScrollEnabled = true,
                        recenterRequestKey = 0,
                        onUserScroll = {},
                        onSeekToMs = {},
                        songTitle = "5 Star Crest",
                        artist = "Bladee",
                        durationMs = 200_000L
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(5_000L)
        rule.waitForIdle()
    }

    /** The row's text block: the lyric's top to its translation's bottom (the row pads both by 6dp). */
    private fun rowBounds(line: String, translation: String): Pair<Dp, Dp> =
        rule.onNodeWithText(line).getUnclippedBoundsInRoot().top to
            rule.onNodeWithText(translation).getUnclippedBoundsInRoot().bottom

    private fun assertSeated(window: DpSize, line: String, translation: String) {
        val (top, bottom) = rowBounds(line, translation)
        val lineHeight = rule.onNodeWithText(line).getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("the line wraps ($lineHeight tall)", lineHeight > 48.dp)

        val fade = min(LyricsEdgeFade, window.height * LyricsEdgeFadeMaxFraction)
        assertTrue("row starts below the top fade (top $top, fade $fade)", top >= fade - 0.5.dp)
        assertTrue(
            "row ends above the bottom fade (bottom $bottom, fade from ${window.height - fade})",
            bottom <= window.height - fade + 0.5.dp
        )
        assertEquals("row centre at the window's middle", window.height.value / 2f, (top + bottom).value / 2f, 1.5f)
    }
}
