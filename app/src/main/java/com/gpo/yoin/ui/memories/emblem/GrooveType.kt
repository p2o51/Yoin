package com.gpo.yoin.ui.memories.emblem

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.gpo.yoin.R
import kotlin.math.roundToInt

/**
 * The emblem's Google Sans Flex instances (variable font), kept out of Type.kt. The score is wght 690 (album)
 * / 640 (average), ROND 50, wdth 76 for four characters ("10.0") else 92; the caption is wght 600; the
 * unrated word is wght 600 at wdth 76 / 92. opsz follows the type size, as the browser's automatic optical
 * sizing does in the prototype (rounded, so a handful of instances are shared app-wide).
 */
internal object GrooveType {
    data class Axes(val weight: Int, val width: Float, val round: Float, val opticalSize: Int)

    private val cache = HashMap<Axes, FontFamily>()

    fun score(kind: GrooveKind, fourCharacters: Boolean, sizeDp: Double): Pair<FontFamily, FontWeight> =
        family(Axes(if (kind == GrooveKind.Album) 690 else 640, if (fourCharacters) 76f else 92f, 50f, opsz(sizeDp)))

    fun caption(sizeDp: Double): Pair<FontFamily, FontWeight> = family(Axes(600, 100f, 0f, opsz(sizeDp)))

    fun unratedWord(widthAxis: Float, sizeDp: Double): Pair<FontFamily, FontWeight> =
        family(Axes(600, widthAxis, 0f, opsz(sizeDp)))

    private fun opsz(sizeDp: Double): Int = sizeDp.roundToInt().coerceIn(6, 144)

    @OptIn(ExperimentalTextApi::class)
    private fun family(axes: Axes): Pair<FontFamily, FontWeight> {
        val weight = FontWeight(axes.weight)
        val family = synchronized(cache) {
            cache.getOrPut(axes) {
                FontFamily(
                    Font(
                        R.font.google_sans_flex_variable,
                        weight = weight,
                        variationSettings = FontVariation.Settings(
                            FontVariation.weight(axes.weight),
                            FontVariation.width(axes.width),
                            FontVariation.Setting("ROND", axes.round),
                            FontVariation.Setting("opsz", axes.opticalSize.toFloat()),
                        ),
                    ),
                )
            }
        }
        return family to weight
    }
}
