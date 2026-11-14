package com.gpo.yoin.data.source.spotify

import java.util.Locale

/**
 * Which `Accept-Language` values to read a Spotify track's names in, for lyrics.
 *
 * Spotify keeps an artist's name per language and answers in the one a request asks for: Hebe Tien is
 * 田馥甄 in Chinese, ヒビ・ティエン in Japanese, Hebe Tien in English. The app writes them in its own
 * language, which isn't the song's, and lyrics sources index the song under its own. So the names are read
 * again in up to two languages, in this order:
 *
 * 1. The device's language: the first read also brings the ISRC, and a listener's own language is usually
 *    the one their lyrics sources speak (a Chinese phone on a Japanese Spotify app).
 * 2. The language of the country in the ISRC, for the places whose lyrics the sources carry: TW → zh-TW,
 *    JP → ja, KR → ko. Skipped when it's the device's language already.
 *
 * A song from a country not listed (US, GB…) is read in English instead, unless the device speaks it: lyrics
 * sources know Katy Perry, not ケイティ・ペリー.
 */
internal object SpotifyNameLanguages {

    /** The device's language for the first read: [locale] as a tag, its language alone when it has no region. */
    fun deviceLanguage(locale: Locale = Locale.getDefault()): String =
        locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: "en"

    /** The language of [isrc]'s country, or null for a country without one here (or no ISRC). */
    fun isrcLanguage(isrc: String?): String? {
        val country = isrc?.trim()?.take(2)?.uppercase() ?: return null
        return ISRC_LANGUAGES[country]
    }

    /** True when [a] and [b] name the same language (zh-TW and zh-HK don't: Spotify may answer each differently). */
    fun sameLanguage(a: String, b: String): Boolean {
        val left = Locale.forLanguageTag(a)
        val right = Locale.forLanguageTag(b)
        if (left.language != right.language) return false
        // Chinese names differ by script, which the region stands for here.
        return left.language != "zh" || chineseScript(left) == chineseScript(right)
    }

    private fun chineseScript(locale: Locale): String = when {
        locale.script.isNotEmpty() -> locale.script
        locale.country in TRADITIONAL_REGIONS -> "Hant"
        else -> "Hans"
    }

    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

    /**
     * ISRC country → the language Spotify names the song in there. Only places whose songs the lyrics sources
     * (QQ Music, NetEase, LRCLIB) carry under names in another script than English.
     */
    private val ISRC_LANGUAGES = mapOf(
        "TW" to "zh-TW",
        "HK" to "zh-HK",
        "MO" to "zh-HK",
        "CN" to "zh-CN",
        "JP" to "ja",
        "KR" to "ko",
    )
}
