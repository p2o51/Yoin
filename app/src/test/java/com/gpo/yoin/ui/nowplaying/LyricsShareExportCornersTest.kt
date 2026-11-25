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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The saved / shared lyric image is a plain rectangle (owner 2026-11-25): the Card format's PNG had the
 * card's rounded corners cut out as transparency, which shows black once posted. Every corner pixel of
 * both formats' export is opaque; the sheet rounds only its preview.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp")
class LyricsShareExportCornersTest {

    @get:Rule
    val rule = createComposeRule()

    private val pick = listOf(
        LyricLine(startMs = 0L, text = "We were static on a radio at midnight"),
        LyricLine(startMs = 4_000L, text = "Two voices looking for the same old frequency"),
    )

    private fun export(format: LyricsShareFormat): ImageBitmap {
        var layer: GraphicsLayer? = null
        rule.setContent {
            YoinTheme {
                val recording = rememberGraphicsLayer()
                layer = recording
                LyricsShareImage(
                    format = format,
                    tone = LyricsCardTone.Soft,
                    lines = pick,
                    showTranslation = false,
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

    private fun assertOpaqueCorners(image: ImageBitmap) {
        val pixels = image.toPixelMap()
        val last = image.width - 1 to image.height - 1
        for ((x, y) in listOf(0 to 0, last.first to 0, 0 to last.second, last.first to last.second)) {
            assertEquals("alpha at ($x, $y) of ${image.width}x${image.height}", 1f, pixels[x, y].alpha, 0.001f)
        }
    }

    @Test
    fun should_exportASquareCorneredCard_when_savingTheCardFormat() {
        val image = export(LyricsShareFormat.Card)

        assertEquals(LyricsCardExportWidthPx, image.width)
        assertOpaqueCorners(image)
    }

    @Test
    fun should_exportAFullBleedStory_when_savingTheStoryFormat() {
        val image = export(LyricsShareFormat.Story)

        assertEquals(LyricsStoryExportWidthPx, image.width)
        assertOpaqueCorners(image)
    }
}
