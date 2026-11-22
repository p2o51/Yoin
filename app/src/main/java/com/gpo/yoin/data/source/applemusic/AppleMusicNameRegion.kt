package com.gpo.yoin.data.source.applemusic

import java.util.Locale

/**
 * Which storefront an Apple Music account shows song, album and artist names from, set per account
 * (Settings › the Apple Music account). An account's own storefront writes every song in its language:
 * the US storefront calls 晴る by ヨルシカ "Sunny" by "Yorushika", and Japan's calls Kim Petras キム・ペトラス.
 * The catalog serves the same song in every storefront under the same id, so asking another storefront
 * for it, in that storefront's language, brings its names from there.
 */
sealed interface AppleMusicNameRegion {
    /** The stored preference key: never rename `"off"`, `"original"`, or storefront codes (`"cn"`, `"jp"`, …). */
    val key: String

    /** The account's own storefront: names as Apple Music shows them. */
    data object Off : AppleMusicNameRegion {
        override val key: String = "off"
    }

    /** Each song from the storefront of the language it was released in ([OriginalNameTarget.detect]). */
    data object Original : AppleMusicNameRegion {
        override val key: String = "original"
    }

    /** Every song from one chosen Apple Music storefront, in that storefront's primary language. */
    data class Storefront(val storefront: String, val language: String) : AppleMusicNameRegion {
        override val key: String get() = storefront

        /** Localized country/region name for the picker and settings summary. */
        fun displayName(locale: Locale = Locale.getDefault()): String = displayName(storefront, locale)
    }

    companion object {
        val Default: AppleMusicNameRegion = Off

        val MainlandChina: Storefront = Storefront("cn", "zh-Hans-CN")
        val Japan: Storefront = Storefront("jp", "ja")

        /**
         * Curated major Apple Music storefronts with their official primary language tag (`l` parameter).
         * Matches iOS and HarmonyOS in exact code order so saved preferences and UX align across platforms.
         */
        val CuratedStorefronts: List<Storefront> = listOf(
            Storefront("us", "en-US"),
            Storefront("gb", "en-GB"),
            Japan,
            MainlandChina,
            Storefront("tw", "zh-Hant-TW"),
            Storefront("hk", "zh-Hant-HK"),
            Storefront("kr", "ko"),
            Storefront("ca", "en-CA"),
            Storefront("au", "en-AU"),
            Storefront("de", "de-DE"),
            Storefront("fr", "fr-FR"),
            Storefront("es", "es-ES"),
            Storefront("it", "it-IT"),
            Storefront("br", "pt-BR"),
            Storefront("mx", "es-MX"),
            Storefront("in", "hi"),
            Storefront("th", "th"),
            Storefront("ru", "ru"),
        )

        private val byCode: Map<String, Storefront> = CuratedStorefronts.associateBy { it.storefront }

        fun fromKey(key: String?): AppleMusicNameRegion {
            val normalized = key?.trim()?.lowercase(Locale.ROOT) ?: return Default
            return when (normalized) {
                Off.key -> Off
                Original.key -> Original
                else -> byCode[normalized] ?: Default
            }
        }

        /** Localized display name for a storefront code (e.g. "jp" → "Japan" / "日本"). */
        fun displayName(storefront: String, locale: Locale = Locale.getDefault()): String {
            val code = storefront.trim().lowercase(Locale.ROOT)
            val isChinese = locale.language.equals("zh", ignoreCase = true)
            if (code == "cn") return if (isChinese) "中国大陆" else "China mainland"
            if (isChinese) {
                when (code) {
                    "hk" -> return "香港"
                    "tw" -> return "台湾"
                }
            }
            return Locale.Builder()
                .setRegion(code.uppercase(Locale.ROOT))
                .build()
                .getDisplayCountry(locale)
                .ifBlank { code.uppercase(Locale.ROOT) }
        }
    }
}

/** A storefront to ask for names, and the language to ask it in (the `l` parameter). */
data class OriginalNameTarget(val storefront: String, val language: String) {
    companion object {
        val Japan = OriginalNameTarget("jp", "ja")
        val Korea = OriginalNameTarget("kr", "ko")
        val MainlandChina = OriginalNameTarget("cn", "zh-Hans-CN")
        val UnitedStates = OriginalNameTarget("us", "en-US")
        private val Thailand = OriginalNameTarget("th", "th")
        private val Russia = OriginalNameTarget("ru", "ru")
        private val Ukraine = OriginalNameTarget("ua", "uk")
        private val SaudiArabia = OriginalNameTarget("sa", "ar")
        private val Israel = OriginalNameTarget("il", "he")
        private val India = OriginalNameTarget("in", "hi")
        private val Greece = OriginalNameTarget("gr", "el")
        private val Bulgaria = OriginalNameTarget("bg", "bg")

        private val LATIN_ISRC_COUNTRIES = setOf(
            "US", "QM", "QZ", "GB", "UK", "CA", "AU", "NZ", "IE",
            "SE", "NO", "DK", "FI", "IS", "NL", "BE", "DE", "AT", "CH",
            "FR", "IT", "ES", "PT", "BR", "MX", "AR", "CO", "CL", "PL", "CZ", "ZA",
        )

        /**
         * The storefront [region] asks for an item's names, or null when it keeps the shown ones. [Original]
         * goes by the item's own language ([detect]); a fixed [AppleMusicNameRegion.Storefront] asks its
         * storefront for everything.
         */
        fun forRegion(
            region: AppleMusicNameRegion,
            title: String?,
            artist: String?,
            genre: String?,
            isrc: String?,
        ): OriginalNameTarget? = when (region) {
            AppleMusicNameRegion.Off -> null
            AppleMusicNameRegion.Original -> detect(title, artist, genre, isrc)
            is AppleMusicNameRegion.Storefront -> OriginalNameTarget(region.storefront, region.language)
        }

        /**
         * The language a song was released in, as well as its metadata tells: its genre first (J-Pop,
         * Mandopop…, in English or in Chinese as the CN storefront writes them), then the country its
         * ISRC was registered in (including Western/Latin countries when the shown text was transliterated
         * into Katakana/Hangul/Han), then the script its shown names are written in. Null when nothing
         * points at a different script/storefront.
         */
        fun detect(title: String?, artist: String?, genre: String?, isrc: String?): OriginalNameTarget? {
            val shown = "${title.orEmpty()} ${artist.orEmpty()}"
            forGenre(genre)?.let { return it }
            forIsrc(isrc)?.let { target ->
                if (target != UnitedStates || nonLatinLetters(shown) > 0) return target
                return null
            }
            // Albums (and ISRC-less items) on Japan's storefront transliterate multi-word Western names
            // with Katakana + middle dot ('・') and no Hiragana/Han (e.g. "テイラー・スウィフト").
            if (looksLikeKatakanaTransliteration(shown)) return UnitedStates
            return forScript(shown)
        }

        fun forGenre(genre: String?): OriginalNameTarget? {
            val g = genre?.trim()?.lowercase().orEmpty()
            if (g.isEmpty()) return null
            fun has(vararg markers: String) = markers.any { it in g }
            return when {
                has("j-pop", "japanese", "anime", "日本流行", "日语流行", "日語流行", "动漫", "動漫") -> Japan
                has("k-pop", "korean", "韩国流行", "韓國流行", "韩语流行", "韓語流行") -> Korea
                has(
                    "mandopop", "cantopop", "chinese", "hong kong", "c-pop",
                    "国语流行", "國語流行", "华语流行", "華語流行", "中文流行", "粤语流行", "粵語流行",
                ) -> MainlandChina
                has("thai") -> Thailand
                has("russian") -> Russia
                has("ukrain") -> Ukraine
                has("arab", "khaleeji") -> SaudiArabia
                has("israel", "hebrew") -> Israel
                has("bollywood", "indian") -> India
                has("greek") -> Greece
                has("bulgar") -> Bulgaria
                else -> null
            }
        }

        /** The ISRC's first two letters name the country of its registrant, which mostly is the artist's. */
        fun forIsrc(isrc: String?): OriginalNameTarget? {
            val prefix = isrc?.trim()?.take(2)?.uppercase() ?: return null
            return when (prefix) {
                "JP" -> Japan
                "KR" -> Korea
                "CN", "HK", "MO", "TW" -> MainlandChina
                in LATIN_ISRC_COUNTRIES -> UnitedStates
                else -> null
            }
        }

        /** Kana is Japanese and Hangul Korean; Han alone is Chinese. Latin script says nothing. */
        fun forScript(text: String): OriginalNameTarget? = when {
            text.any(::isKana) -> Japan
            text.any(::isHangul) -> Korea
            hasHan(text) -> MainlandChina
            else -> null
        }

        /**
         * Whether names a storefront answered for [target] are worth showing instead of the shown ones. A
         * fixed region shows whatever its storefront calls the item. [AppleMusicNameRegion.Original] asks
         * for names in the item's own script: for [UnitedStates], accept a Latin name that removes
         * Katakana/Hangul/Han transliteration; for non-Latin targets, require a non-Latin letter (and Han
         * for Chinese).
         */
        fun accepts(
            region: AppleMusicNameRegion,
            target: OriginalNameTarget,
            shown: String?,
            resolved: String?,
        ): Boolean {
            val answer = resolved?.trim().orEmpty()
            if (answer.isEmpty() || answer == shown?.trim()) return false
            if (region != AppleMusicNameRegion.Original) return true
            if (target == UnitedStates) {
                val shownText = shown?.trim().orEmpty()
                return latinLetters(answer) > 0 && nonLatinLetters(answer) < nonLatinLetters(shownText)
            }
            if (nonLatinLetters(answer) == 0) return false
            return target != MainlandChina || hasHan(answer)
        }

        private fun isHiragana(c: Char) = c.code in 0x3040..0x309F
        private fun isKatakana(c: Char) = c.code in 0x30A1..0x30FA || c.code in 0x31F0..0x31FF
        private fun isKana(c: Char) = c.code in 0x3040..0x30FF || c.code in 0x31F0..0x31FF
        private fun isHangul(c: Char) = c.code in 0xAC00..0xD7AF || c.code in 0x1100..0x11FF || c.code in 0x3130..0x318F

        private fun looksLikeKatakanaTransliteration(text: String): Boolean {
            if (!text.contains('・')) return false
            if (text.any(::isHiragana) || hasHan(text)) return false
            return text.any(::isKatakana)
        }

        private fun hasHan(text: String): Boolean = text.codePoints().anyMatch {
            Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
        }

        private fun latinLetters(text: String): Int = text.codePoints().filter {
            Character.isLetter(it) && Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN
        }.count().toInt()

        private fun nonLatinLetters(text: String): Int = text.codePoints().filter {
            Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
        }.count().toInt()
    }
}
