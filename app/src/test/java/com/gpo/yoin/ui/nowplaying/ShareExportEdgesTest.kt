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
 * Every share image — all ten layouts, Card and Story, every tone's fourth slot included — is a plain,
 * fully opaque rectangle (owner 2026-11-25: rounded / see-through corners show black once posted): every
 * edge pixel of the export is opaque, at more than one density and font scale.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp")
class ShareExportEdgesTest(
    // Enums by name: they are internal, the runner's constructor public.
    layoutName: String,
    formatName: String,
    private val dpi: Int,
    private val fontScale: Float,
) {

    @get:Rule
    val rule = createComposeRule()

    private val layout = ShareLayout.valueOf(layoutName)
    private val format = LyricsShareFormat.valueOf(formatName)

    private fun export(): ImageBitmap {
        RuntimeEnvironment.setQualifiers("+${dpi}dpi")
        RuntimeEnvironment.setFontScale(fontScale)
        var layer: GraphicsLayer? = null
        rule.setContent {
            YoinTheme {
                val recording = rememberGraphicsLayer()
                layer = recording
                ShareImage(
                    content = PreviewShareContent.copy(
                        lyrics = PreviewShareContent.lyrics!!.copy(showTranslation = true),
                    ),
                    layout = layout,
                    format = format,
                    // The fourth slot: Mist for the lyrics, Paper for About and Note.
                    tone = if (dpi % 2 == 0) ShareTone.Soft else ShareTone.Paper,
                    exportLayer = recording,
                )
            }
        }
        // Drawing the window is what records the export layer.
        rule.onRoot().captureToImage()
        return runBlocking { checkNotNull(layer).toImageBitmap() }
    }

    @Test
    fun should_exportAnOpaqueRectangle_when_savedOrShared() {
        val image = export()
        val expectedWidth = if (format == LyricsShareFormat.Card) LyricsCardExportWidthPx else LyricsStoryExportWidthPx
        assertEquals(expectedWidth, image.width)
        if (format == LyricsShareFormat.Story) assertEquals(LyricsStoryExportHeightPx, image.height)

        val pixels = image.toPixelMap()
        val w = image.width
        val h = image.height
        val edge = (0 until w).flatMap { x -> listOf(x to 0, x to h - 1) } +
            (0 until h).flatMap { y -> listOf(0 to y, w - 1 to y) }
        val holes = edge.filter { (x, y) -> pixels[x, y].alpha < 1f }
        assertTrue(
            "$layout $format at ${dpi}dpi, font ×$fontScale (${w}x$h): " +
                "${holes.size} see-through edge pixels, first ${holes.take(4)}",
            holes.isEmpty(),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1} {2}dpi font{3}")
        fun cases(): List<Array<Any>> = ShareLayout.entries.flatMap { layout ->
            listOf(
                arrayOf<Any>(layout.name, "Card", 420, 1f),
                arrayOf<Any>(layout.name, "Card", 441, 1.3f),
                arrayOf<Any>(layout.name, "Story", 420, 1f),
                arrayOf<Any>(layout.name, "Story", 561, 0.85f),
            )
        }
    }
}
