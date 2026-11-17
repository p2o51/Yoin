package com.gpo.yoin.ui.continueon

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.gpo.yoin.ui.theme.ExpressiveColorSchemeFactory
import com.gpo.yoin.ui.theme.YoinSeedArgb
import contrast.Contrast
import hct.Hct
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Continue On card and the Library search hint carry no shadow (owner 2026-11-13); their
 * colours alone must set them apart from the page, for the default scheme and for cover palettes.
 */
class FlatFloaterContrastTest {
    private val seeds = listOf(
        YoinSeedArgb,
        0xFFD32F2F.toInt(),
        0xFFFFEB3B.toInt(),
        0xFF00BCD4.toInt(),
        0xFF43A047.toInt(),
        0xFF8D8A90.toInt(),
    )

    private fun schemes(): List<ColorScheme> = seeds.flatMap { seed ->
        listOf(false, true).map { dark -> ExpressiveColorSchemeFactory.fromSeed(seedArgb = seed, isDark = dark) }
    }

    @Test
    fun should_standOutFromThePage_when_theContinueOnCardHasNoShadow() {
        schemes().forEach { page ->
            val card = continueOnCardScheme(page)
            assertAtLeast("card on page", card.surface, page.surface, 3.0)
            assertAtLeast("card on page container", card.surface, page.surfaceContainerHigh, 3.0)
            assertAtLeast("title", card.onSurface, card.surface, 4.5)
            assertAtLeast("quiet line", card.onSurfaceVariant, card.surface, 4.5)
            assertAtLeast("accent", card.primary, card.surface, 4.5)
            assertAtLeast("button on card", card.primary, card.surface, 3.0)
            assertAtLeast("button text", card.onPrimary, card.primary, 4.5)
        }
    }

    @Test
    fun should_standOutFromThePage_when_theSearchHintHasNoShadow() {
        schemes().forEach { page ->
            assertAtLeast("hint on page", page.inverseSurface, page.surface, 3.0)
            assertAtLeast("hint icon", page.inversePrimary, page.inverseSurface, 3.0)
        }
    }

    private fun assertAtLeast(what: String, a: Color, b: Color, min: Double) {
        val ratio = Contrast.ratioOfTones(Hct.fromInt(a.toArgb()).tone, Hct.fromInt(b.toArgb()).tone)
        assertTrue("$what: %.2f < %.1f".format(ratio, min), ratio >= min)
    }
}
