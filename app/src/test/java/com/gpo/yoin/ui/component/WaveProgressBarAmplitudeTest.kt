package com.gpo.yoin.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What M3E's wavy indicator actually draws once the scrubber's play state
 * settles, measured as the height of the red progress ink: the stroke alone
 * when flat, the stroke plus the wave's swing when wavy. Two reference bars
 * that never change state (one playing, one paused) give the expected
 * heights.
 *
 * M3E drops a new amplitude target while its own 500ms tween runs, so a
 * second toggle inside that tween only lands through the scrubber's delayed
 * recheck. M3E reads the amplitude while it builds its draw cache, so every
 * frame here is also drawn, the way the real render loop would.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1000dp-h200dp-xhdpi")
class WaveProgressBarAmplitudeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var playing by mutableStateOf(true)
    private lateinit var frame: Bitmap

    private fun setBars(progress: Float, playingAtStart: Boolean) {
        playing = playingAtStart
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                Column {
                    val bars = listOf(PLAYING_REFERENCE to true, PAUSED_REFERENCE to false, SUBJECT to playing)
                    for ((tag, isPlaying) in bars) {
                        WaveProgressBar(
                            progress = progress,
                            buffered = 0f,
                            durationMs = 180_000L,
                            onSeek = {},
                            isPlaying = isPlaying,
                            trackColor = Color.Transparent,
                            progressColor = Color.Red,
                            modifier = Modifier.width(BAR_WIDTH).testTag(tag)
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val window = rule.activity.window.decorView
        frame = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        advance(SETTLE_MS)
    }

    /** Robolectric never renders by itself: run each frame, then draw the window. */
    private fun advance(ms: Long) {
        repeat((ms / FRAME_MS).toInt()) {
            rule.mainClock.advanceTimeBy(FRAME_MS)
            rule.waitForIdle()
            rule.runOnIdle {
                frame.eraseColor(android.graphics.Color.TRANSPARENT)
                rule.activity.window.decorView.draw(Canvas(frame))
            }
        }
    }

    private fun toggle() {
        rule.runOnIdle { playing = !playing }
        advance(FRAME_MS)
    }

    /** Height in px of a bar's red progress ink in the last drawn frame; 0 when none. */
    private fun inkHeightPx(tag: String): Int {
        val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        var top = Int.MAX_VALUE
        var bottom = -1
        for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt().coerceAtMost(frame.height)) {
            for (x in bounds.left.roundToInt() until bounds.right.roundToInt().coerceAtMost(frame.width)) {
                val c = frame.getPixel(x, y)
                val alpha = c ushr 24
                val red = (c shr 16) and 0xFF
                val green = (c shr 8) and 0xFF
                val blue = c and 0xFF
                if (alpha > 128 && red > 160 && green < 90 && blue < 90) {
                    if (y < top) top = y
                    bottom = y
                }
            }
        }
        return if (bottom < 0) 0 else bottom - top + 1
    }

    private fun referenceHeights(): Pair<Int, Int> {
        val wave = inkHeightPx(PLAYING_REFERENCE)
        val flat = inkHeightPx(PAUSED_REFERENCE)
        assertTrue("the paused reference draws no ink", flat > 0)
        assertTrue("wave $wave px is no taller than flat $flat px", wave >= flat + MIN_SWING_PX)
        return wave to flat
    }

    @Test
    fun should_keepFullAmplitude_when_progressInFirstTenPercent() {
        setBars(progress = 0.05f, playingAtStart = true)

        val (wave, _) = referenceHeights()

        assertEquals(wave.toFloat(), inkHeightPx(SUBJECT).toFloat(), TOLERANCE_PX)
    }

    @Test
    fun should_flatten_when_paused() {
        setBars(progress = 0.5f, playingAtStart = true)

        toggle()
        advance(SETTLE_MS)

        val (_, flat) = referenceHeights()
        assertEquals(flat.toFloat(), inkHeightPx(SUBJECT).toFloat(), TOLERANCE_PX)
    }

    @Test
    fun should_landOnAWave_when_pausedAndResumedInsideTheTween() {
        setBars(progress = 0.5f, playingAtStart = true)

        toggle() // pause: M3E starts its 500ms tween down to flat
        advance(QUICK_GAP_MS)
        toggle() // resume inside that tween: M3E drops this target
        advance(SETTLE_MS)

        val (wave, _) = referenceHeights()
        assertEquals(wave.toFloat(), inkHeightPx(SUBJECT).toFloat(), TOLERANCE_PX)
    }

    @Test
    fun should_settleFlat_when_resumedAndPausedInsideTheTween() {
        setBars(progress = 0.5f, playingAtStart = false)

        toggle() // resume: M3E starts its 500ms tween up to the wave
        advance(QUICK_GAP_MS)
        toggle() // pause inside that tween: M3E drops this target
        advance(SETTLE_MS)

        val (_, flat) = referenceHeights()
        assertEquals(flat.toFloat(), inkHeightPx(SUBJECT).toFloat(), TOLERANCE_PX)
    }

    @Test
    fun should_landOnAWave_when_playPausePlayTappedQuickly() {
        setBars(progress = 0.5f, playingAtStart = true)

        toggle()
        advance(QUICK_GAP_MS)
        toggle()
        advance(QUICK_GAP_MS)
        toggle()
        advance(QUICK_GAP_MS)
        toggle()
        advance(SETTLE_MS)

        val (wave, _) = referenceHeights()
        assertEquals(wave.toFloat(), inkHeightPx(SUBJECT).toFloat(), TOLERANCE_PX)
    }

    private companion object {
        const val PLAYING_REFERENCE = "wave-playing-reference"
        const val PAUSED_REFERENCE = "wave-paused-reference"
        const val SUBJECT = "wave-subject"

        // Wide enough that 5% of it still holds two 24dp wavelengths.
        val BAR_WIDTH = 960.dp

        const val FRAME_MS = 16L
        const val QUICK_GAP_MS = 112L

        // Well past M3E's 500ms tween, the recheck, and a second tween.
        const val SETTLE_MS = 1_600L

        const val TOLERANCE_PX = 1f
        const val MIN_SWING_PX = 4
    }
}
