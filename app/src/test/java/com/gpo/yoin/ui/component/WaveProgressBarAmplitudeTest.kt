package com.gpo.yoin.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What M3E's wavy indicator actually draws once the scrubber's play state
 * settles, measured as the height of the red progress ink: the stroke alone
 * when flat, the stroke plus the wave's swing when wavy. Two reference bars
 * that never change state (one playing, one paused, both mid-song) give the
 * expected heights.
 *
 * M3E drops a new amplitude target while its own 500ms tween runs, so a
 * second toggle inside that tween only lands through the scrubber's delayed
 * recheck. M3E reads the amplitude while it builds its draw cache, so every
 * frame here is also drawn, the way the real render loop would.
 *
 * Every case runs at 1x and at a 2x animator duration scale (developer
 * options): M3E's tween stretches with the scale, and the recheck must
 * stretch with it.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1000dp-h200dp-xhdpi")
class WaveProgressBarAmplitudeTest(private val durationScale: Float) {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>(
        effectContext = object : MotionDurationScale {
            override val scaleFactor: Float get() = durationScale
        }
    )

    private var playing by mutableStateOf(true)
    private lateinit var frame: Bitmap

    /** Past M3E's tween, the recheck and a second tween, at this run's scale. */
    private val settleMs get() = (SETTLE_MS * durationScale).toLong()

    private fun setBars(subjectProgress: Float, playingAtStart: Boolean) {
        playing = playingAtStart
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                Column {
                    val bars = listOf(
                        Triple(PLAYING_REFERENCE, REFERENCE_PROGRESS, true),
                        Triple(PAUSED_REFERENCE, REFERENCE_PROGRESS, false),
                        Triple(SUBJECT, subjectProgress, playing)
                    )
                    for ((tag, progress, isPlaying) in bars) {
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
        advance(settleMs)
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

    /**
     * The Recomposer this window composes with: pausing its frame clock stops
     * animations while recomposition and draw go on. CoveredWindowAnimationGate
     * reads it off the content view (View.compositionContext); the test
     * environment hands it to the ComposeView as its parent context instead,
     * which has no public getter.
     */
    private fun windowRecomposer(): Recomposer = rule.runOnIdle {
        val composeView = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        composeView.compositionContext as? Recomposer
            ?: AbstractComposeView::class.java.getDeclaredField("parentContext")
                .apply { isAccessible = true }
                .get(composeView) as Recomposer
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

    /**
     * The two references' ink heights (playing, paused). Unless they differ by
     * a visible swing, nothing here can tell a wave from a flat line.
     */
    private fun referenceHeights(): Pair<Int, Int> {
        val wave = inkHeightPx(PLAYING_REFERENCE)
        val flat = inkHeightPx(PAUSED_REFERENCE)
        assertTrue("the paused reference draws no ink", flat > 0)
        assertTrue("wave $wave px is no taller than flat $flat px", wave >= flat + MIN_SWING_PX)
        return wave to flat
    }

    @Test
    fun should_keepFullAmplitude_when_progressInFirstTenPercent() {
        setBars(subjectProgress = 0.05f, playingAtStart = true)

        // Playing at 5%, the bar swings as far as one mid-song; M3's default
        // amplitude draws it flat.
        val (wave, _) = referenceHeights()
        assertEquals(wave.toFloat(), inkHeightPx(SUBJECT).toFloat(), TOLERANCE_PX)
    }

    @Test
    fun should_keepFullAmplitude_when_progressInLastFivePercent() {
        setBars(subjectProgress = 0.97f, playingAtStart = true)

        // Playing at 97%, the bar swings as far as one mid-song; M3's default
        // amplitude draws it flat.
        val (wave, _) = referenceHeights()
        assertEquals(wave.toFloat(), inkHeightPx(SUBJECT).toFloat(), TOLERANCE_PX)
    }

    @Test
    fun should_flatten_when_paused() {
        setBars(subjectProgress = REFERENCE_PROGRESS, playingAtStart = true)

        toggle()
        advance(settleMs)

        val (_, flat) = referenceHeights()
        assertEquals(flat.toFloat(), inkHeightPx(SUBJECT).toFloat(), TOLERANCE_PX)
    }

    @Test
    fun should_landOnAWave_when_pausedAndResumedInsideTheTween() {
        setBars(subjectProgress = REFERENCE_PROGRESS, playingAtStart = true)

        toggle() // pause: M3E starts its tween down to flat
        advance(QUICK_GAP_MS)
        toggle() // resume inside that tween: M3E drops this target
        advance(settleMs)

        val (wave, _) = referenceHeights()
        assertEquals(wave.toFloat(), inkHeightPx(SUBJECT).toFloat(), TOLERANCE_PX)
    }

    @Test
    fun should_settleFlat_when_resumedAndPausedInsideTheTween() {
        setBars(subjectProgress = REFERENCE_PROGRESS, playingAtStart = false)

        toggle() // resume: M3E starts its tween up to the wave
        advance(QUICK_GAP_MS)
        toggle() // pause inside that tween: M3E drops this target
        advance(settleMs)

        val (_, flat) = referenceHeights()
        assertEquals(flat.toFloat(), inkHeightPx(SUBJECT).toFloat(), TOLERANCE_PX)
    }

    @Test
    fun should_landOnAWave_when_playPausePlayTappedQuickly() {
        setBars(subjectProgress = REFERENCE_PROGRESS, playingAtStart = true)

        toggle()
        advance(QUICK_GAP_MS)
        toggle()
        advance(QUICK_GAP_MS)
        toggle()
        advance(QUICK_GAP_MS)
        toggle()
        advance(settleMs)

        val (wave, _) = referenceHeights()
        assertEquals(wave.toFloat(), inkHeightPx(SUBJECT).toFloat(), TOLERANCE_PX)
    }

    @Test
    fun should_settleFlat_when_resumedAndPausedWhileTheFrameClockIsPaused() {
        setBars(subjectProgress = REFERENCE_PROGRESS, playingAtStart = false)
        val recomposer = windowRecomposer()

        // Now Playing under a detail page: the covered shell's frame clock is
        // paused, so M3E's tween cannot advance, while time passes and the
        // shell still recomposes and draws.
        rule.runOnIdle { recomposer.pauseCompositionFrameClock() }
        toggle() // Play on the detail page: M3E launches its tween, held
        advance(QUICK_GAP_MS)
        toggle() // pause: dropped while that held tween is pending
        advance(settleMs)
        rule.runOnIdle { recomposer.resumeCompositionFrameClock() } // back to Now Playing
        advance(settleMs)

        val (_, flat) = referenceHeights()
        assertEquals(flat.toFloat(), inkHeightPx(SUBJECT).toFloat(), TOLERANCE_PX)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "durationScale={0}")
        fun durationScales(): List<Array<Any>> = listOf(arrayOf(1f), arrayOf(2f))

        private const val PLAYING_REFERENCE = "wave-playing-reference"
        private const val PAUSED_REFERENCE = "wave-paused-reference"
        private const val SUBJECT = "wave-subject"

        private const val REFERENCE_PROGRESS = 0.5f

        // Wide enough that 5% of it still holds two 24dp wavelengths.
        private val BAR_WIDTH = 960.dp

        private const val FRAME_MS = 16L
        private const val QUICK_GAP_MS = 112L

        // At 1x: well past M3E's 500ms tween, the recheck, and a second tween.
        private const val SETTLE_MS = 1_600L

        private const val TOLERANCE_PX = 1f
        private const val MIN_SWING_PX = 4
    }
}
