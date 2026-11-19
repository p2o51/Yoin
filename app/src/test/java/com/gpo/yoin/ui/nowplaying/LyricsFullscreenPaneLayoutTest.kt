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

    @Test
    fun should_keepFocusLinePinned_when_translationToggles() {
        val translated = (0 until 20).map {
            LyricLine(startMs = 10_000L + it * 5_000L, text = "Current line $it", translation = "Translation $it")
        }
        var showTranslation by mutableStateOf(false)
        rule.setContent {
            YoinTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    LyricsFullscreenPane(
                        lyrics = translated,
                        positionMs = { 50_000L },
                        loading = false,
                        showTranslation = showTranslation,
                        autoScrollEnabled = true,
                        recenterRequestKey = 0,
                        onUserScroll = {},
                        onSeekToMs = {},
                        trackKey = "translated-song",
                        songTitle = "First Song",
                        artist = "Night QA Band",
                        durationMs = duration,
                    )
                }
            }
        }
        rule.waitForIdle()
        val beforeTop = rule.onNodeWithText("Current line 8").getBoundsInRoot().top

        rule.runOnIdle { showTranslation = true }
        rule.mainClock.advanceTimeBy(2_000L)
        rule.waitForIdle()

        val afterTop = rule.onNodeWithText("Current line 8").getBoundsInRoot().top
        assertEquals("focus line keeps its y", beforeTop.value, afterTop.value, 2f)
        val tr8 = rule.onNodeWithText("Translation 8").getBoundsInRoot()
        assertTrue(tr8.bottom - tr8.top > 0.dp)
    }

    @Test
    fun should_glideToNextLine_when_playheadAdvancesDuringTranslationWave() {
        val translated = (0 until 20).map {
            LyricLine(startMs = 10_000L + it * 5_000L, text = "Current line $it", translation = "Translation $it")
        }
        var showTranslation by mutableStateOf(false)
        var position by mutableLongStateOf(50_000L)
        rule.setContent {
            YoinTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    LyricsFullscreenPane(
                        lyrics = translated,
                        positionMs = { position },
                        loading = false,
                        showTranslation = showTranslation,
                        autoScrollEnabled = true,
                        recenterRequestKey = 0,
                        onUserScroll = {},
                        onSeekToMs = {},
                        trackKey = "translated-wave",
                        songTitle = "First Song",
                        artist = "Night QA Band",
                        durationMs = duration,
                    )
                }
            }
        }
        rule.waitForIdle()
        val line8Before = rule.onNodeWithText("Current line 8").getBoundsInRoot()
        val seatCenter = (line8Before.top + line8Before.bottom).value / 2f

        rule.runOnIdle { showTranslation = true }
        rule.mainClock.advanceTimeBy(150L)
        rule.runOnIdle { position = 55_000L }
        rule.mainClock.advanceTimeBy(3_000L)
        rule.waitForIdle()

        val line9 = rule.onNodeWithText("Current line 9").getBoundsInRoot()
        val tr9 = rule.onNodeWithText("Translation 9").getBoundsInRoot()
        assertTrue(tr9.bottom - tr9.top > 0.dp)
        val line9Center = (line9.top + tr9.bottom).value / 2f
        assertEquals("next line glides to the focus seat", seatCenter, line9Center, 4f)
    }

    @Test
    fun should_notJumpFocusLine_when_togglingTranslationOnAndOffRepeatedly() {
        val translated = (0 until 20).map {
            LyricLine(startMs = 10_000L + it * 5_000L, text = "Current line $it", translation = "Translation $it")
        }
        var showTranslation by mutableStateOf(false)
        rule.setContent {
            YoinTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    LyricsFullscreenPane(
                        lyrics = translated,
                        positionMs = { 50_000L }, // line 8 (mid-song)
                        loading = false,
                        showTranslation = showTranslation,
                        autoScrollEnabled = true,
                        recenterRequestKey = 0,
                        onUserScroll = {},
                        onSeekToMs = {},
                        trackKey = "toggle-repeat",
                        songTitle = "First Song",
                        artist = "Night QA Band",
                        durationMs = duration,
                    )
                }
            }
        }
        rule.waitForIdle()
        val initialTop = rule.onNodeWithText("Current line 8").getBoundsInRoot().top.value

        fun sampleMaxDriftDuringToggle(next: Boolean): Pair<Float, Float> {
            var maxDrift = 0f
            var maxStep = 0f
            var prev = rule.onNodeWithText("Current line 8").getBoundsInRoot().top.value
            rule.mainClock.autoAdvance = false
            rule.runOnIdle { showTranslation = next }
            repeat(80) {
                rule.mainClock.advanceTimeByFrame()
                val now = rule.onNodeWithText("Current line 8").getBoundsInRoot().top.value
                maxDrift = maxOf(maxDrift, kotlin.math.abs(now - initialTop))
                maxStep = maxOf(maxStep, kotlin.math.abs(now - prev))
                prev = now
            }
            rule.mainClock.autoAdvance = true
            rule.mainClock.advanceTimeBy(1_500L)
            rule.waitForIdle()
            val settled = rule.onNodeWithText("Current line 8").getBoundsInRoot().top.value
            maxDrift = maxOf(maxDrift, kotlin.math.abs(settled - initialTop))
            return maxDrift to maxStep
        }

        val (driftOn, stepOn) = sampleMaxDriftDuringToggle(true)
        val (driftOff, stepOff) = sampleMaxDriftDuringToggle(false)
        val (driftOn2, stepOn2) = sampleMaxDriftDuringToggle(true)

        assertTrue("toggle ON drifted ${driftOn}dp (max step ${stepOn}dp)", driftOn <= 3f && stepOn <= 3f)
        assertTrue("toggle OFF drifted ${driftOff}dp (max step ${stepOff}dp)", driftOff <= 3f && stepOff <= 3f)
        assertTrue("second toggle ON drifted ${driftOn2}dp (max step ${stepOn2}dp)", driftOn2 <= 3f && stepOn2 <= 3f)
    }

    @Test
    fun should_notSnapJump_when_togglingTranslationNearEndOfSong() {
        val translated = (0 until 20).map {
            LyricLine(startMs = 10_000L + it * 5_000L, text = "Current line $it", translation = "Translation $it")
        }
        var showTranslation by mutableStateOf(true)
        rule.setContent {
            YoinTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    LyricsFullscreenPane(
                        lyrics = translated,
                        positionMs = { 80_000L }, // line 14 (near end, like Adam)
                        loading = false,
                        showTranslation = showTranslation,
                        autoScrollEnabled = true,
                        recenterRequestKey = 0,
                        onUserScroll = {},
                        onSeekToMs = {},
                        trackKey = "toggle-near-end",
                        songTitle = "First Song",
                        artist = "Night QA Band",
                        durationMs = duration,
                    )
                }
            }
        }
        rule.waitForIdle()

        fun sampleMaxSingleFrameJump(next: Boolean): Float {
            var maxStep = 0f
            var prev = rule.onNodeWithText("Current line 14").getBoundsInRoot().top.value
            rule.mainClock.autoAdvance = false
            rule.runOnIdle { showTranslation = next }
            repeat(80) {
                rule.mainClock.advanceTimeByFrame()
                val now = rule.onNodeWithText("Current line 14").getBoundsInRoot().top.value
                maxStep = maxOf(maxStep, kotlin.math.abs(now - prev))
                prev = now
            }
            rule.mainClock.autoAdvance = true
            rule.mainClock.advanceTimeBy(1_500L)
            rule.waitForIdle()
            return maxStep
        }

        val stepOff = sampleMaxSingleFrameJump(false)
        val stepOn = sampleMaxSingleFrameJump(true)
        assertTrue("collapse near end had single-frame jump ${stepOff}dp", stepOff <= 8f)
        assertTrue("expand near end had single-frame jump ${stepOn}dp", stepOn <= 8f)
    }

    @Test
    fun should_keepGlidingAndLandCleanly_when_togglingTranslationMidLineAdvance() {
        val translated = (0 until 20).map {
            LyricLine(startMs = 10_000L + it * 5_000L, text = "Current line $it", translation = "Translation $it")
        }
        var showTranslation by mutableStateOf(false)
        var position by mutableLongStateOf(50_000L) // line 8
        rule.setContent {
            YoinTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    LyricsFullscreenPane(
                        lyrics = translated,
                        positionMs = { position },
                        loading = false,
                        showTranslation = showTranslation,
                        autoScrollEnabled = true,
                        recenterRequestKey = 0,
                        onUserScroll = {},
                        onSeekToMs = {},
                        trackKey = "toggle-mid-glide",
                        songTitle = "First Song",
                        artist = "Night QA Band",
                        durationMs = duration,
                    )
                }
            }
        }
        rule.waitForIdle()
        val line8Before = rule.onNodeWithText("Current line 8").getBoundsInRoot()
        val seatCenter = (line8Before.top + line8Before.bottom).value / 2f

        // Advance playhead so animateScrollToItem starts gliding, then toggle translation mid-glide.
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { position = 55_000L } // line 9
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        rule.runOnIdle { showTranslation = true }
        var maxStep = 0f
        var prev = rule.onNodeWithText("Current line 9").getBoundsInRoot().top.value
        val trace = mutableListOf("init=$prev")
        repeat(80) { frame ->
            rule.mainClock.advanceTimeByFrame()
            val now = rule.onNodeWithText("Current line 9").getBoundsInRoot().top.value
            val delta = kotlin.math.abs(now - prev)
            if (delta > 1f) trace.add("f$frame:$prev->$now(d=$delta)")
            maxStep = maxOf(maxStep, delta)
            prev = now
        }
        rule.mainClock.autoAdvance = true
        rule.mainClock.advanceTimeBy(2_000L)
        rule.waitForIdle()

        val line9 = rule.onNodeWithText("Current line 9").getBoundsInRoot()
        val tr9 = rule.onNodeWithText("Translation 9").getBoundsInRoot()
        assertTrue(tr9.bottom - tr9.top > 0.dp)
        assertTrue("mid-glide translation toggle had jump ${maxStep}dp trace=$trace", maxStep <= 16f)
        val line9Center = (line9.top + tr9.bottom).value / 2f
        assertEquals("line 9 lands on the focus seat", seatCenter, line9Center, 6f)
    }

    @Test
    fun should_centerOnPlayingLine_when_reenteringExpandedLyricsAfterScrollingAway() {
        var expandedMounted by mutableStateOf(true)
        var autoScroll by mutableStateOf(true)
        val position = 80_000L // line 14 (far below row 0)
        rule.setContent {
            YoinTheme {
                androidx.compose.runtime.LaunchedEffect(expandedMounted) {
                    if (!expandedMounted) autoScroll = true
                }
                Box(Modifier.size(360.dp, 640.dp)) {
                    if (expandedMounted) {
                        LyricsFullscreenPane(
                            lyrics = current,
                            positionMs = { position },
                            loading = false,
                            showTranslation = false,
                            autoScrollEnabled = autoScroll,
                            recenterRequestKey = 0,
                            onUserScroll = { autoScroll = false },
                            onSeekToMs = {},
                            trackKey = "reenter-song",
                            songTitle = "First Song",
                            artist = "Night QA Band",
                            durationMs = duration,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val firstCenter = rule.onNodeWithText("Current line 14").getBoundsInRoot()
        assertTrue(firstCenter.top > 0.dp && firstCenter.bottom < 640.dp)

        // Simulate user manually scrolling away, leaving Expanded, then re-entering Expanded.
        rule.runOnIdle { autoScroll = false }
        rule.waitForIdle()
        rule.runOnIdle { expandedMounted = false }
        rule.waitForIdle()
        rule.runOnIdle { expandedMounted = true }
        rule.waitForIdle()

        assertTrue("auto-scroll restored on re-entry", autoScroll)
        val reentered = rule.onNodeWithText("Current line 14").getBoundsInRoot()
        assertEquals("re-entering expanded lyrics seats active line", firstCenter.top.value, reentered.top.value, 2f)
    }
}
