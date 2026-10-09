package com.gpo.yoin.ui.detail

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class AlbumSpectrumTest {

    private fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }

    private fun contrastWithWhite(argb: Int): Double = 1.05 / (luminance(argb) + 0.05)

    private fun cover(n: Int = AlbumSpectrumColumns, colorAt: (x: Int, y: Int) -> Int) =
        IntArray(n * n) { colorAt(it % n, it / n) }

    @Test
    fun should_keepWhiteTextAboveSevenToOne_when_anyColourIsToneLocked() {
        val samples = buildList {
            for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
                add((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        samples.forEach { argb ->
            val ratio = contrastWithWhite(lockTone(argb))
            assertTrue("0x${argb.toUInt().toString(16)} → $ratio", ratio >= 7.0)
        }
    }

    @Test
    fun should_keepSevenToOne_when_aSaturatedLightColourLosesLightnessToGamutMapping() {
        // Lightness alone landed this at #006825, 6.98:1 (Codex review on #33).
        val ratio = contrastWithWhite(lockTone(0xFFB5FFBE.toInt()))
        assertTrue("$ratio", ratio >= 7.0)
    }

    @Test
    fun should_useEverySlotOnce_when_spectrumIsComputed() {
        val pixels = cover { x, y -> if ((x + y) % 3 == 0) 0xFFE0402A.toInt() else 0xFF203A80.toInt() }
        val spectrum = computeAlbumSpectrum(pixels, AlbumSpectrumColumns)
        assertEquals((0 until AlbumSpectrumColumns).toList(), spectrum.target.sorted())
    }

    @Test
    fun should_makeBandsAsWideAsEachColoursShare_when_coverIsThreeQuartersRed() {
        val red = 0xFFD02020.toInt()
        val blue = 0xFF2040D0.toInt()
        val n = AlbumSpectrumColumns
        val spectrum = computeAlbumSpectrum(cover { x, _ -> if (x < n * 3 / 4) red else blue }, n)
        val bands = (0 until n).map { spectrum.colorAtSlot(it) }
        val counts = bands.groupingBy { it }.eachCount().values.sorted()
        assertEquals(listOf(n / 4, n * 3 / 4), counts)
        // Contiguous: the colour changes exactly once along the bar.
        assertEquals(1, bands.zipWithNext().count { (a, b) -> a != b })
    }

    @Test
    fun should_sendEachColumnToItsOwnColoursBand_when_coverHasTwoColours() {
        val red = 0xFFD02020.toInt()
        val blue = 0xFF2040D0.toInt()
        val n = AlbumSpectrumColumns
        val spectrum = computeAlbumSpectrum(cover { x, _ -> if (x % 2 == 0) red else blue }, n)
        val redLocked = Color(lockTone(red))
        (0 until n).forEach { x ->
            val isRed = x % 2 == 0
            assertEquals(isRed, spectrum.color[x] == redLocked)
        }
    }

    @Test
    fun should_stillGiveTwoBands_when_coverIsOneColour() {
        val spectrum = computeAlbumSpectrum(cover { _, _ -> 0xFF808080.toInt() }, AlbumSpectrumColumns)
        assertEquals(2, spectrum.color.toSet().size)
    }

    @Test
    fun should_giveTheSameSpectrum_when_theSameCoverIsComputedTwice() {
        val pixels = cover { x, y -> (0xFF shl 24) or ((x * 9) shl 16) or ((y * 9) shl 8) or 0x40 }
        val a = computeAlbumSpectrum(pixels, AlbumSpectrumColumns)
        val b = computeAlbumSpectrum(pixels, AlbumSpectrumColumns)
        assertEquals(a.color, b.color)
        assertEquals(a.target.toList(), b.target.toList())
    }

    @Test
    fun should_lockTheFallbackColours_when_thereIsNoCover() {
        val spectrum = fallbackAlbumSpectrum(listOf(Color(0xFFFFF0F0), Color(0xFF6750A4)))
        spectrum.color.forEach { assertTrue(contrastWithWhite(it.toArgb()) >= 7.0) }
        assertEquals((0 until AlbumSpectrumColumns).toList(), spectrum.target.toList())
    }

    // ---- geometry ----

    private val density = Density(1f)

    private fun geometry(
        layout: AlbumBarLayout,
        width: Float = 412f,
        rtl: Boolean = false,
        screenEdges: Boolean = true,
    ) = albumBarGeometry(
        layout = layout,
        width = width,
        height = 900f,
        headerHeight = 112f,
        titleRowTop = 28f,
        titleRowHeight = 52f,
        startInset = 0f,
        endInset = 0f,
        rtl = rtl,
        density = density,
        pageEdgesAreScreenEdges = screenEdges,
    )

    @Test
    fun should_putTheStampAtTheBarsEnd_when_layoutIsLeftToRight() {
        val g = geometry(AlbumBarLayout.Compact)
        assertEquals(412f - 16f - 48f, g.stamp.left, 0.01f)
        assertEquals(28f + (52f - 48f) / 2f, g.stamp.top, 0.01f)
        assertEquals(Rect(0f, 0f, 412f, 112f), g.bar)
    }

    @Test
    fun should_putTheStampAtTheStart_when_layoutIsRightToLeft() {
        val g = geometry(AlbumBarLayout.Compact, rtl = true)
        assertEquals(16f, g.stamp.left, 0.01f)
    }

    @Test
    fun should_centreTheHeroCover_when_layoutIsCompact() {
        val g = geometry(AlbumBarLayout.Compact)
        assertEquals(300f, g.cover.width, 0.01f)
        assertEquals((412f - 300f) / 2f, g.cover.left, 0.01f)
        assertEquals(112f + 28f, g.cover.top, 0.01f)
    }

    @Test
    fun should_startTheCoverAtTheCappedColumn_when_layoutIsMediumAndWide() {
        val g = geometry(AlbumBarLayout.Medium, width = 800f)
        assertEquals((800f - 720f) / 2f + 16f, g.cover.left, 0.01f)
        assertEquals(240f, g.cover.width, 0.01f)
    }

    @Test
    fun should_moveTheCoverFromRestToStamp_when_progressGoesFromZeroToOne() {
        val g = geometry(AlbumBarLayout.Compact)
        assertEquals(g.cover, albumBarCoverRect(g, g.cover, 0f))
        val docked = albumBarCoverRect(g, g.cover, 1f)
        assertEquals(g.stamp.left, docked.left, 0.01f)
        assertEquals(g.stamp.width, docked.width, 0.01f)
    }

    @Test
    fun should_keepTheHuggingArmsClearOfThePageEdge_when_layoutIsMediumAndNarrow() {
        val g = geometry(AlbumBarLayout.Medium, width = 656f)
        val pose = g.butterflyFrom
        val centre = pose.left + pose.width / 2f
        assertTrue(centre - 0.46f * pose.width >= 8f - 0.01f)
    }

    @Test
    fun should_hugTheCoverInsteadOfBleeding_when_compactPageSitsInAColumn() {
        val bleeding = geometry(AlbumBarLayout.Compact, width = 460f).butterflyFrom
        assertEquals(0f, bleeding.left, 0.01f)
        val hugging = geometry(AlbumBarLayout.Compact, width = 460f, screenEdges = false).butterflyFrom
        val centre = hugging.left + hugging.width / 2f
        assertEquals(1f, hugging.scale, 0.001f)
        assertTrue(centre - 0.46f * hugging.width >= 8f - 0.01f)
        assertTrue(centre + 0.46f * hugging.width <= 460f - 8f + 0.01f)
    }
}
