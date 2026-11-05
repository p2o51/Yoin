package com.gpo.yoin.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import android.os.Build
import android.os.LocaleList
import androidx.annotation.FontRes
import androidx.annotation.RequiresApi
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle as ComposeFontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily

/**
 * A Google Sans Flex [Font] whose Chinese / Japanese / Korean glyphs keep the requested weight.
 *
 * Google Sans Flex has no CJK glyphs, so Han text falls back to the system CJK font. With an app font that
 * fallback is the system's choice, and on Samsung (One UI) it comes out at one weight: a Bold title is bold
 * in Latin and regular in Chinese. On API 29+ we build the typeface ourselves with
 * [Typeface.CustomFallbackBuilder] and put the system CJK font right behind GSF at the weight we asked for:
 *
 *  - a real bold file when the system ships one (several OEMs do);
 *  - otherwise the variable CJK font (Noto Sans CJK VF on recent AOSP) instanced at that `wght`;
 *  - otherwise the regular file declared as 400, so the text engine fakes the bold (≥ 600) itself.
 *
 * Below API 29, or if anything here fails, it is the plain resource font, as before.
 *
 * [axes] are variation settings for the GSF file (`wght`, `ROND`, `wdth`, `opsz` …).
 */
fun yoinFont(
    @FontRes resId: Int,
    weight: FontWeight,
    axes: Map<String, Float> = emptyMap(),
): Font = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    CjkFallbackFont(resId, weight, axes)
} else {
    plainFont(resId, weight, axes)
}

@OptIn(ExperimentalTextApi::class)
private fun plainFont(resId: Int, weight: FontWeight, axes: Map<String, Float>): Font = Font(
    resId,
    weight = weight,
    variationSettings = FontVariation.Settings(
        *axes.map { (tag, value) -> FontVariation.Setting(tag, value) }.toTypedArray(),
    ),
)

@RequiresApi(Build.VERSION_CODES.Q)
private class CjkFallbackFont(
    val resId: Int,
    override val weight: FontWeight,
    val axes: Map<String, Float>,
) : AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
    override val style: ComposeFontStyle = ComposeFontStyle.Normal

    override fun equals(other: Any?): Boolean =
        other is CjkFallbackFont && other.resId == resId && other.weight == weight && other.axes == axes

    override fun hashCode(): Int = (resId * 31 + weight.hashCode()) * 31 + axes.hashCode()

    override fun toString(): String = "CjkFallbackFont(resId=$resId, weight=$weight, axes=$axes)"

    private object Loader : TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface? {
            val f = font as CjkFallbackFont
            return runCatching { build(context, f) }.getOrNull()
                ?: plainTypeface(context, f)
        }

        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? =
            loadBlocking(context, font)

        private fun plainTypeface(context: Context, f: CjkFallbackFont): Typeface? = runCatching {
            val gsf = PlatformFont.Builder(context.resources, f.resId)
                .setWeight(f.weight.weight)
                .apply { if (f.axes.isNotEmpty()) setFontVariationSettings(f.axes.toVariationString()) }
                .build()
            Typeface.CustomFallbackBuilder(PlatformFontFamily.Builder(gsf).build())
                .setStyle(FontStyle(f.weight.weight, FontStyle.FONT_SLANT_UPRIGHT))
                .build()
        }.getOrNull()

        private fun build(context: Context, f: CjkFallbackFont): Typeface {
            val w = f.weight.weight
            val gsf = PlatformFont.Builder(context.resources, f.resId)
                .setWeight(w)
                .apply { if (f.axes.isNotEmpty()) setFontVariationSettings(f.axes.toVariationString()) }
                .build()
            val builder = Typeface.CustomFallbackBuilder(PlatformFontFamily.Builder(gsf).build())
                .setStyle(FontStyle(w, FontStyle.FONT_SLANT_UPRIGHT))
                .setSystemFallback("sans-serif")
            SystemCjk.fontFor(w)?.let { builder.addCustomFallback(PlatformFontFamily.Builder(it).build()) }
            return builder.build()
        }
    }
}

private fun Map<String, Float>.toVariationString(): String =
    entries.joinToString(", ") { (tag, value) -> "'$tag' $value" }

/** The system's sans CJK font for the current locale, picked once and cached per weight. */
@RequiresApi(Build.VERSION_CODES.Q)
private object SystemCjk {
    private val fontsByWeight = HashMap<Int, PlatformFont?>()

    /** Han script variants in fallback order; the first that matches the user's locales wins. */
    private val scriptTags = listOf("zh-Hans", "zh-Hant", "ja", "ko")

    private val candidates: List<PlatformFont> by lazy {
        val all = runCatching { SystemFonts.getAvailableFonts().toList() }.getOrDefault(emptyList())
            .filter { it.style.slant == FontStyle.FONT_SLANT_UPRIGHT && !it.isSerif() }
        val tag = preferredTag()
        all.filter { font -> font.localeTags().any { it.equals(tag, ignoreCase = true) || it.startsWith("$tag-", true) } }
    }

    private val isVariable: Boolean by lazy {
        candidates.firstOrNull()?.let { runCatching { hasWeightAxis(it.buffer, it.ttcIndex) }.getOrDefault(false) }
            ?: false
    }

    fun fontFor(weight: Int): PlatformFont? = synchronized(fontsByWeight) {
        fontsByWeight.getOrPut(weight) { runCatching { pick(weight) }.getOrNull() }
    }

    private fun pick(weight: Int): PlatformFont? {
        if (candidates.isEmpty()) return null
        // A real file at (or near) this weight beats any synthesis.
        val axis = if (isVariable) "'wght' $weight" else null
        candidates.firstOrNull { kotlin.math.abs(it.style.weight - weight) < 50 }
            ?.let { return copy(it, it.style.weight, axis) }
        val regular = candidates.minByOrNull { kotlin.math.abs(it.style.weight - 400) } ?: return null
        return if (isVariable) {
            copy(regular, weight, axis)
        } else {
            // Declared at its true weight: the text engine fakes bold when asked for ≥ 600.
            copy(regular, regular.style.weight, null)
        }
    }

    private fun copy(source: PlatformFont, weight: Int, variation: String?): PlatformFont {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PlatformFont.Builder(source)
        } else {
            val file = source.file
            if (file != null) PlatformFont.Builder(file) else PlatformFont.Builder(source.buffer)
        }
        return builder
            .setTtcIndex(source.ttcIndex)
            .setWeight(weight)
            .setSlant(FontStyle.FONT_SLANT_UPRIGHT)
            .apply { if (variation != null) setFontVariationSettings(variation) }
            .build()
    }

    private fun preferredTag(): String {
        val locales = LocaleList.getDefault()
        for (i in 0 until locales.size()) {
            val l = locales[i]
            when (l.language) {
                "ja" -> return "ja"
                "ko" -> return "ko"
                "zh" -> {
                    val hant = l.script == "Hant" ||
                        (l.script.isEmpty() && l.country in setOf("TW", "HK", "MO"))
                    return if (hant) "zh-Hant" else "zh-Hans"
                }
            }
        }
        return scriptTags.first()
    }

    private fun PlatformFont.localeTags(): List<String> {
        val list = localeList
        return (0 until list.size()).map { list[it].toLanguageTag() }
    }

    /** Serif CJK files (Noto Serif CJK) also carry zh locales; keep to the sans one. */
    private fun PlatformFont.isSerif(): Boolean =
        (file?.name ?: "").contains("serif", ignoreCase = true)
}

/** True when the font (inside a TTC at [ttcIndex]) has an `fvar` table with a `wght` axis. */
internal fun hasWeightAxis(source: ByteBuffer, ttcIndex: Int): Boolean {
    val buf = source.duplicate().order(ByteOrder.BIG_ENDIAN)
    var offset = 0
    if (buf.getInt(0) == 0x74746366) { // 'ttcf'
        val numFonts = buf.getInt(8)
        if (ttcIndex !in 0 until numFonts) return false
        offset = buf.getInt(12 + 4 * ttcIndex)
    }
    val numTables = buf.getShort(offset + 4).toInt() and 0xFFFF
    for (i in 0 until numTables) {
        val record = offset + 12 + 16 * i
        if (buf.getInt(record) == 0x66766172) { // 'fvar'
            val fvar = buf.getInt(record + 8)
            val axesOffset = buf.getShort(fvar + 4).toInt() and 0xFFFF
            val axisCount = buf.getShort(fvar + 8).toInt() and 0xFFFF
            val axisSize = buf.getShort(fvar + 10).toInt() and 0xFFFF
            for (a in 0 until axisCount) {
                if (buf.getInt(fvar + axesOffset + a * axisSize) == 0x77676874) return true // 'wght'
            }
            return false
        }
    }
    return false
}
