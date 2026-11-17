package com.gpo.yoin.ui.nowplaying

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.gpo.yoin.player.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActionGlowTest {
    @Test
    fun should_lightShuffle_when_roundKeyTurnsShuffleOn() {
        assertEquals(ActionGlowKind.ShuffleOn, playModeGlowKind(PlayMode.RepeatAll, PlayMode.RepeatAll.next()))
    }

    @Test
    fun should_lightEachRepeatState_when_repeatSegmentSteps() {
        assertEquals(ActionGlowKind.RepeatOne, playModeGlowKind(PlayMode.Shuffle, PlayMode.Shuffle.nextRepeat()))
        assertEquals(ActionGlowKind.RepeatOff, playModeGlowKind(PlayMode.RepeatOne, PlayMode.RepeatOne.nextRepeat()))
        assertEquals(ActionGlowKind.RepeatAll, playModeGlowKind(PlayMode.RepeatOff, PlayMode.RepeatOff.nextRepeat()))
    }

    @Test
    fun should_lightShuffleOff_when_shuffleSegmentTurnsItOff() {
        assertEquals(ActionGlowKind.ShuffleOff, playModeGlowKind(PlayMode.Shuffle, PlayMode.Shuffle.withShuffle(false)))
    }

    @Test
    fun should_lightNothing_when_modeUnchanged() {
        assertNull(playModeGlowKind(PlayMode.RepeatOne, PlayMode.RepeatOne))
    }

    @Test
    fun should_drain_when_ratingCleared() {
        assertEquals(ActionGlowKind.RatingClear, ratingGlowKind(0f))
        assertEquals(ActionGlowKind.RatingClear, ratingGlowKind(null))
        assertEquals(ActionGlowKind.RatingCommit, ratingGlowKind(7.5f))
    }

    @Test
    fun should_runLonger_when_ratingIsFullMarks() {
        assertEquals(1300, ActionGlowKind.RatingCommit.durationFor(8f))
        assertEquals(1700, ActionGlowKind.RatingCommit.durationFor(10f))
        assertEquals(ActionGlowKind.FavoriteOn.durationMs, ActionGlowKind.FavoriteOn.durationFor(10f))
    }

    @Test
    fun should_placeRatingFromBottom_when_barIsVertical() {
        val bar = Rect(100f, 0f, 150f, 200f)
        assertEquals(Offset(125f, 200f), ratingPoint(bar, 0f, vertical = true, rtl = false))
        assertEquals(Offset(125f, 100f), ratingPoint(bar, 5f, vertical = true, rtl = false))
        assertEquals(Offset(125f, 0f), ratingPoint(bar, 10f, vertical = true, rtl = false))
    }

    @Test
    fun should_placeRatingFromStart_when_barIsHorizontal() {
        val bar = Rect(0f, 0f, 200f, 40f)
        assertEquals(Offset(50f, 20f), ratingPoint(bar, 2.5f, vertical = false, rtl = false))
        assertEquals(Offset(150f, 20f), ratingPoint(bar, 2.5f, vertical = false, rtl = true))
    }

    @Test
    fun should_retireRunningPlayModeLight_when_keyTappedAgain() {
        val key = Rect(0f, 0f, 48f, 48f)
        val glows = mutableListOf(ActionGlow(ActionGlowKind.ShuffleOn, key, bornUptimeMs = 1_000L))
        glows.addHandingOver(ActionGlow(ActionGlowKind.RepeatOne, key, bornUptimeMs = 1_300L))
        assertEquals(1_300L, glows[0].retiredUptimeMs)
        assertEquals(Long.MAX_VALUE, glows[1].retiredUptimeMs)
        assertFalse(glows[0].isDone(1_300L + GlowHandOverMs))
        assertTrue(glows[0].isDone(1_301L + GlowHandOverMs))
        assertFalse(glows[1].isDone(1_301L + GlowHandOverMs))
    }

    @Test
    fun should_leaveOtherLightsRunning_when_playModeLightFires() {
        val key = Rect(0f, 0f, 48f, 48f)
        val glows = mutableListOf(ActionGlow(ActionGlowKind.FavoriteOn, key, bornUptimeMs = 1_000L))
        glows.addHandingOver(ActionGlow(ActionGlowKind.ShuffleOn, key, bornUptimeMs = 1_100L))
        assertEquals(Long.MAX_VALUE, glows[0].retiredUptimeMs)
        assertEquals(2, glows.size)
    }

    @Test
    fun should_keepPlayModeLightsShort_when_fired() {
        val kinds = ActionGlowKind.entries.filter { it.isPlayMode }
        assertEquals(5, kinds.size)
        kinds.forEach { assertTrue("$it runs ${it.durationMs} ms", it.durationMs <= 800) }
    }

    @Test
    fun should_retireRunningTranslationWave_when_toggledAgain() {
        val glows = mutableListOf(
            ActionGlow(ActionGlowKind.TranslationWave, Rect.Zero, bornUptimeMs = 1_000L, value = 1f),
        )
        glows.addHandingOver(
            ActionGlow(ActionGlowKind.TranslationWave, Rect.Zero, bornUptimeMs = 1_250L, value = 0f),
        )
        assertEquals(1_250L, glows[0].retiredUptimeMs)
        assertEquals(Long.MAX_VALUE, glows[1].retiredUptimeMs)
    }

    @Test
    fun should_notCrash_when_translationWaveAreaTopExceedsCanvasHeight() {
        val image = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).asImageBitmap()
        val canvas = Canvas(image)
        val scope = CanvasDrawScope()
        val palette = GlowPalette(Color.Blue, Color.Cyan, Color.Magenta, dark = true)
        val cases = listOf(
            // Portrait-recorded lyrics area drawn on a shrunk/rotated 1000px-tall canvas.
            ActionGlow(
                kind = ActionGlowKind.TranslationWave,
                anchorRoot = Rect.Zero,
                bornUptimeMs = 0L,
                value = 1f,
                targetY = 1900f,
                areaRoot = Rect(0f, 1800f, 800f, 2400f),
            ),
            // Partially overlapping area where targetY is below the canvas bottom.
            ActionGlow(
                kind = ActionGlowKind.TranslationWave,
                anchorRoot = Rect.Zero,
                bornUptimeMs = 0L,
                value = 0f,
                targetY = 1900f,
                areaRoot = Rect(0f, 500f, 800f, 2400f),
            ),
        )
        cases.forEach { glow ->
            scope.draw(Density(2f), LayoutDirection.Ltr, canvas, Size(800f, 1000f)) {
                drawActionGlow(
                    glow = glow,
                    tMs = 500f,
                    origin = Offset.Zero,
                    pal = palette,
                    blend = BlendMode.Plus,
                )
            }
        }
    }
}
