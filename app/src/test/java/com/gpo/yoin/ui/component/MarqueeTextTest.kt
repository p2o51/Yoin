package com.gpo.yoin.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w600dp-h400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarqueeTextTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val style = TextStyle(fontSize = 20.sp, fontFamily = FontFamily.Monospace, background = Color.Blue)

    @Test
    fun should_fadeOnlyTrailingTextWithoutErasingParent_when_firstLongLineIsDrawn() {
        show(text = "MMMMMMMMMMMMMMMMMMMM", running = false)
        val pixels = image().toPixelMap()
        val y = pixels.height / 2
        assertTrue("leading glyph background stays opaque", pixels[1, y].blue > 0.9f && pixels[1, y].red < 0.1f)
        assertTrue("trailing text fades into the white parent", pixels[pixels.width - 2, y].red > 0.9f)
        assertEquals("mask must not erase parent", 1f, pixels[pixels.width - 2, y].alpha, 0.01f)
    }

    @Test
    fun should_mirrorTrailingFade_when_rtlLineIsWaiting() {
        show(text = "مممممممممممممممممممممممم", running = false, direction = LayoutDirection.Rtl)
        val pixels = image().toPixelMap()
        val y = pixels.height / 2
        assertTrue("RTL trailing edge is left", pixels[1, y].red > 0.9f)
        assertTrue(
            "RTL leading edge stays opaque",
            pixels[pixels.width - 2, y].blue > 0.9f && pixels[pixels.width - 2, y].red < 0.1f
        )
    }

    @Test
    fun should_keepShortTextUnfadedAndStill_when_running() {
        show(text = "MM", running = true)
        val before = image()
        rule.mainClock.advanceTimeBy(3_000)
        assertPixelsEqual(before, image())
        val pixels = before.toPixelMap()
        assertTrue(pixels[1, pixels.height / 2].red < 0.1f)
    }

    @Test
    fun should_waitThenMoveAndResetToStart_when_runningChanges() {
        var running by mutableStateOf(true)
        show(text = "MMMM WWWW IIII XXXX", running = { running }, textColor = Color.Black)
        val initial = image()
        rule.mainClock.advanceTimeBy(1_000)
        assertPixelsEqual(initial, image())
        rule.mainClock.advanceTimeBy(600)
        val moving = image().toPixelMap()
        val before = initial.toPixelMap()
        assertTrue(
            "glyphs must move in the center, beyond either fade mask",
            (20 until moving.width - 20).any { x ->
                (0 until moving.height).any { y -> before[x, y] != moving[x, y] }
            }
        )
        assertTrue("leading fade starts after the initial pause", moving[1, moving.height / 2].red > 0.9f)
        rule.runOnIdle {
            running = false
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        assertPixelsEqual(initial, image())
        rule.mainClock.advanceTimeBy(3_000)
        assertPixelsEqual(initial, image())
    }

    @Test
    fun should_removeFadeInSameLayout_when_viewportExpandsToFit() {
        var width by mutableStateOf(100)
        show(text = "MMMMMMMMMMMM", running = { false }, width = { width })
        assertEquals(100, image().width)
        rule.runOnIdle {
            width = 400
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        val pixels = image().toPixelMap()
        assertEquals(400, pixels.width)
        assertTrue(
            "former clipped edge must be ordinary text: " +
                "size=${pixels.width}x${pixels.height}, pixel=${pixels[98, pixels.height / 2]}",
            pixels[98, pixels.height / 2].red < 0.1f
        )
    }

    @Test
    fun should_restartFadeOnlyWithTheMarqueeTarget_when_widthChangesByPixels() {
        var width by mutableStateOf(101)
        show(text = "MMMMMMMMMMMMMMMMMMMM", running = { true }, width = { width })
        rule.mainClock.advanceTimeBy(1_600)
        val moving = image().toPixelMap()
        assertTrue("leading edge is fading while moving", moving[1, moving.height / 2].red > 0.9f)

        // Foundation's spacing rounds both 101/3 and 102/3 to 34px: no animation restart.
        rule.runOnIdle {
            width = 102
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        val unchangedTarget = image().toPixelMap()
        assertEquals(102, unchangedTarget.width)
        assertTrue("same target must keep the leading fade", unchangedTarget[1, unchangedTarget.height / 2].red > 0.9f)

        // 104/3 rounds to 35px: the motion and leading fade must now return to their start.
        rule.runOnIdle {
            width = 104
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        val restarted = image()
        assertEquals(104, restarted.width)
        val start = restarted.toPixelMap()
        assertTrue("new target immediately clears leading fade", start[1, start.height / 2].red < 0.1f)
        rule.mainClock.advanceTimeBy(1_000)
        assertPixelsEqual(restarted, image())
        rule.mainClock.advanceTimeBy(600)
        val resumed = image().toPixelMap()
        assertTrue("new target gets its own initial pause", resumed[1, resumed.height / 2].red > 0.9f)
    }

    @Test
    fun should_remeasureCjkAndResetPhase_when_fontScaleChanges() {
        var scale by mutableStateOf(1f)
        show(text = "夜明けの街を歩く音楽", running = { true }, fontScale = { scale })
        rule.mainClock.advanceTimeBy(1_800)
        val previousHeight = image().height
        rule.runOnIdle {
            scale = 1.6f
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        val initial = image()
        assertTrue("font scale must actually change measured height", initial.height > previousHeight)
        val pixels = initial.toPixelMap()
        assertTrue("new font starts with an opaque leading edge", pixels[1, pixels.height / 2].red < 0.1f)
        rule.mainClock.advanceTimeBy(1_000)
        assertPixelsEqual(initial, image())
    }

    private fun show(text: String, running: Boolean, direction: LayoutDirection = LayoutDirection.Ltr) =
        show(text, { running }, direction = direction)

    private fun show(
        text: String,
        running: () -> Boolean,
        width: () -> Int = { 100 },
        fontScale: () -> Float = { 1f },
        direction: LayoutDirection = LayoutDirection.Ltr,
        textColor: Color = Color.Blue
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale()),
                LocalLayoutDirection provides direction
            ) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(width().dp).background(Color.White).testTag("viewport")) {
                            MarqueeText(text, style, textColor, running = running())
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun image(): ImageBitmap {
        val bitmap = rule.runOnIdle {
            val window = rule.activity.window.decorView
            window.measure(
                View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(window.height, View.MeasureSpec.EXACTLY)
            )
            window.layout(0, 0, window.measuredWidth, window.measuredHeight)
            Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888).also {
                // Robolectric's Compose clock does not run Android ViewRoot traversals.
                // Complete the pending host measure/layout and draw exactly this frame.
                window.draw(Canvas(it))
            }
        }
        val bounds = rule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInWindow
        return Bitmap.createBitmap(
            bitmap,
            bounds.left.roundToInt(),
            bounds.top.roundToInt(),
            bounds.width.roundToInt(),
            bounds.height.roundToInt()
        ).asImageBitmap()
    }

    private fun assertPixelsEqual(expected: ImageBitmap, actual: ImageBitmap) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val first = expected.toPixelMap()
        val second = actual.toPixelMap()
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                assertEquals("pixel $x,$y", first[x, y], second[x, y])
            }
        }
    }
}
