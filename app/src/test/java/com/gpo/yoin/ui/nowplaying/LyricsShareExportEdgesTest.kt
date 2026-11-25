package com.gpo.yoin.ui.nowplaying

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The saved / shared lyric image is a plain, fully opaque rectangle (owner 2026-11-25): the Card's PNG had
 * its rounded corners cut out as transparency (black once posted), and its height — rounded from the laid
 * out height × the export scale — could land up to half a pixel past the drawn card, leaving a partly
 * transparent last row. Every edge pixel of both formats' export is opaque, at any density, system font
 * scale and pick length.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp")
class LyricsShareExportEdgesTest(
    // The format by name: the enum is internal, the runner's constructor public.
    formatName: String,
    private val dpi: Int,
    private val fontScale: Float,
    private val lineCount: Int,
) {

    @get:Rule
    val rule = createComposeRule()

    private val format = LyricsShareFormat.valueOf(formatName)

    private fun export(): ImageBitmap {
        RuntimeEnvironment.setQualifiers("+${dpi}dpi")
        RuntimeEnvironment.setFontScale(fontScale)
        val lines = (0 until lineCount).map { i ->
            LyricLine(
                startMs = i * 4_000L,
                text = "Lyric line $i, ".repeat(1 + i % 3).trim(),
                translation = if (i % 2 == 0) "第 $i 行" else null,
            )
        }
        var layer: GraphicsLayer? = null
        rule.setContent {
            YoinTheme {
                val recording = rememberGraphicsLayer()
                layer = recording
                LyricsShareImage(
                    format = format,
                    tone = LyricsCardTone.Soft,
                    lines = lines,
                    showTranslation = true,
                    songTitle = "Paper Lanterns",
                    artist = "Kiri Ono",
                    coverArtUrl = null,
                    exportLayer = recording,
                )
            }
        }
        // Drawing the window is what records the export layer.
        rule.onRoot().captureToImage()
        val recorded = checkNotNull(layer)
        return runBlocking { recorded.toImageBitmap() }
    }

    @Test
    fun should_exportAnOpaqueRectangle_when_savedOrShared() {
        val image = export()
        val expectedWidth = if (format == LyricsShareFormat.Card) LyricsCardExportWidthPx else LyricsStoryExportWidthPx
        assertEquals(expectedWidth, image.width)

        val pixels = image.toPixelMap()
        val w = image.width
        val h = image.height
        val edge = (0 until w).flatMap { x -> listOf(x to 0, x to h - 1) } +
            (0 until h).flatMap { y -> listOf(0 to y, w - 1 to y) }
        val holes = edge.filter { (x, y) -> pixels[x, y].alpha < 1f }
        assertTrue(
            "$format at ${dpi}dpi, font ×$fontScale, $lineCount lines (${w}x$h): " +
                "${holes.size} see-through edge pixels, first ${holes.take(4)}",
            holes.isEmpty(),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}dpi font{2} lines{3}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("Card", 320, 1f, 1),
            arrayOf("Card", 420, 1f, 2),
            arrayOf("Card", 420, 1.3f, 5),
            arrayOf("Card", 440, 1f, 3),
            arrayOf("Card", 560, 0.85f, 8),
            arrayOf("Card", 480, 2f, MaxSelectedLyricLines),
            arrayOf("Story", 420, 1f, 2),
            arrayOf("Story", 560, 1.3f, MaxSelectedLyricLines),
        )
    }
}
