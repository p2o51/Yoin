package com.gpo.yoin.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.ExpressiveBackdropColors
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.theme.YoinLightColorScheme
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the cover's memory mark looks like: a mark landing after its cover
 * fades in (no hard cut), and its ink stays legible while the cover's palette
 * isn't read yet (the Memories pill's fallback ink, not the backdrop's pale
 * container colour).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h800dp-notnight-xxhdpi")
class HomeAlbumMemoryMarkInkTest {

    @get:Rule
    val rule = createComposeRule()

    private val scheme = YoinLightColorScheme

    // The theme's own colours, as the sticker read them.
    private var page = Color.Unspecified
    private var fallbackInk = Color.Unspecified
    private lateinit var host: View

    // The cover's backdrop before its palette is read: WidgetBackdropArtwork's container fallbacks.
    private val unread = ExpressiveBackdropColors(
        baseColor = scheme.secondaryContainer,
        accentColor = scheme.tertiaryContainer
    )

    @Test
    fun should_inkWithTheMemoriesFallback_when_theCoverPaletteIsUnread() {
        show(mark = rating, backdrop = unread)

        assertNear(fallbackInk, pixel(COOKIE))
    }

    @Test
    fun should_inkWithTheCoverPalette_when_itIsRead() {
        val read = ExpressiveBackdropColors(
            baseColor = Color(0xFF7A3E12),
            accentColor = Color(0xFFFFB68A),
            isResolvedFromPalette = true
        )
        show(mark = rating, backdrop = read)

        // Light theme: the palette's base (the JBI score rule).
        assertNear(read.baseColor, pixel(COOKIE))
    }

    @Test
    fun should_fadeTheMarkIn_when_itLandsAfterTheCover() {
        var mark by mutableStateOf<HomeAlbumMemoryMark?>(null)
        show(markOf = { mark }, backdrop = unread)
        assertNear(Color.Black, pixel(DISC))

        rule.mainClock.autoAdvance = false
        rule.runOnIdle { mark = rating }
        val frames = List(FADE_FRAMES) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            pixel(DISC).lightness
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val settled = pixel(DISC)

        // The disc (page colour) over black eases in over several frames, never backing off, then is whole.
        assertNear(page, settled)
        val partWay = frames.map { lightness -> lightness / settled.lightness }.filter { it > 0.05f && it < 0.95f }
        assertTrue("no part-way frames: $frames → $settled", partWay.size >= 2)
        assertTrue("backed off: $frames", frames.zipWithNext().all { (a, b) -> b >= a })
    }

    private val rating = HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, "9.0", 0)

    private fun show(mark: HomeAlbumMemoryMark?, backdrop: ExpressiveBackdropColors) =
        show(markOf = { mark }, backdrop = backdrop)

    private fun show(markOf: () -> HomeAlbumMemoryMark?, backdrop: ExpressiveBackdropColors) {
        rule.setContent {
            YoinTheme(colorSchemeOverride = scheme) {
                Box(
                    modifier = Modifier
                        .testTag(TAG)
                        .size(STAGE)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    host = LocalView.current
                    page = MaterialTheme.colorScheme.background
                    fallbackInk = MaterialTheme.colorScheme.secondary
                    AlbumMemoryMarkSticker(mark = markOf(), backdrop = backdrop)
                }
            }
        }
        rule.waitForIdle()
    }

    /** The colour at [at] (dp from the stage's top-start corner), as the window draws it. */
    private fun pixel(at: Pair<Float, Float>): Color {
        val density = rule.density.density
        val stage = rule.onNodeWithTag(TAG).fetchSemanticsNode().boundsInWindow
        val root = host.rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(Canvas(bitmap)) }
        val x = (stage.left + at.first * density).toInt()
        val y = (stage.top + at.second * density).toInt()
        return Color(bitmap.getPixel(x, y))
    }

    private val Color.lightness: Float get() = red + green + blue

    private fun assertNear(expected: Color, actual: Color) {
        val close = abs(expected.red - actual.red) < TOLERANCE &&
            abs(expected.green - actual.green) < TOLERANCE &&
            abs(expected.blue - actual.blue) < TOLERANCE
        assertTrue("expected ~$expected, was $actual", close)
    }

    private companion object {
        const val TAG = "markStage"
        val STAGE = 32.dp

        // The 16dp disc and its 12dp cookie, centred on the 32dp stage.
        val COOKIE = 16f to 16f

        // Inside the disc (7dp above its centre, 1dp in from its edge), 1dp above the cookie.
        val DISC = 16f to 9f

        // Frames watched after the mark lands: its first ones and well into the effects spring.
        const val FADE_FRAMES = 8
        const val TOLERANCE = 0.04f
    }
}
