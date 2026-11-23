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
        val Taiwan = OriginalNameTarget("tw", "zh-Hant-TW")
        val HongKong = OriginalNameTarget("hk", "zh-Hant-HK")
        val UnitedStates = OriginalNameTarget("us", "en-US")
        private val Thailand = OriginalNameTarget("th", "th")
        private val Russia = OriginalNameTarget("ru", "ru")
        private val Ukraine = OriginalNameTarget("ua", "uk")
        private val SaudiArabia = OriginalNameTarget("sa", "ar")
        private val Israel = OriginalNameTarget("il", "he")
        private val India = OriginalNameTarget("in", "hi")
        private val Greece = OriginalNameTarget("gr", "el")
        private val Bulgaria = OriginalNameTarget("bg", "bg")

        private val COMPILATION_ARTISTS = setOf(
            "various artists", "various", "ヴァリアス・アーティスト", "群星", "群星/合辑", "合辑", "合輯",
        )

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
         * Mandopop…, in English, Chinese, or Japanese as the storefront writes them), then the country its
         * ISRC was registered in (including Western/Latin countries when the shown text was transliterated
         * into Katakana/Hangul/Han), then Katakana transliteration of Western names, then the script its
         * shown names are written in. Null when nothing points at a different script/storefront.
         */
        fun detect(title: String?, artist: String?, genre: String?, isrc: String?): OriginalNameTarget? {
            val shown = "${title.orEmpty()} ${artist.orEmpty()}"
            val byGenre = forGenre(genre)
            val byIsrc = forIsrc(isrc)
            if (byGenre != null) {
                val resolved = if (isChinese(byGenre) && byIsrc != null && isChinese(byIsrc)) byIsrc else byGenre
                return if (isChinese(resolved) && !needsChineseRecovery(title, artist)) null else resolved
            }
            if (byIsrc != null) {
                if (byIsrc == UnitedStates) return if (nonLatinLetters(shown) > 0) UnitedStates else null
                if (isChinese(byIsrc) && !needsChineseRecovery(title, artist)) return null
                return byIsrc
            }
            // Albums and artists on Japan's storefront transliterate Western names into Katakana with no
            // Hiragana/Han/Hangul (both multi-word "テイラー・スウィフト" and single-word "ミューズ", "ビョーク").
            // Japanese Katakana artists/songs (e.g. "ヨルシカ") are matched as Japan above via genre/ISRC.
            if (looksLikeKatakanaTransliteration(shown)) return UnitedStates
            return forScript(shown)
        }

        fun forGenre(genre: String?): OriginalNameTarget? {
            val g = genre?.trim()?.lowercase().orEmpty()
            if (g.isEmpty()) return null
            fun has(vararg markers: String) = markers.any { it in g }
            return when {
                has(
                    "j-pop", "j-rock", "japanese", "anime", "enka", "kayokyoku",
                    "日本流行", "日语流行", "日語流行", "动漫", "動漫",
                    "アニメ", "邦楽", "日本のポップ", "演歌", "歌謡曲",
                ) -> Japan
                has("k-pop", "korean", "韩国流行", "韓國流行", "韩语流行", "韓語流行", "韓国") -> Korea
                has("cantopop", "hong kong", "粤语流行", "粵語流行", "カントポップ", "広東ポップ") -> HongKong
                has(
                    "mandopop", "chinese", "c-pop",
                    "国语流行", "國語流行", "华语流行", "華語流行", "中文流行", "マンドポップ",
                ) -> Taiwan
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
                "CN" -> MainlandChina
                "TW" -> Taiwan
                "HK", "MO" -> HongKong
                in LATIN_ISRC_COUNTRIES -> UnitedStates
                else -> null
            }
        }

        /**
         * Kana is Japanese and Hangul Korean. Han alone is shared across Japanese Kanji (e.g. "藤井 風"),
         * Traditional Chinese ("張懸"), and Simplified Chinese, so Han without Chinese genre/ISRC stays put.
         */
        fun forScript(text: String): OriginalNameTarget? = when {
            text.any(::isKana) -> Japan
            text.any(::isHangul) -> Korea
            else -> null
        }

        /**
         * Whether names a storefront answered for [target] are worth showing instead of the shown ones. A
         * fixed region shows whatever its storefront calls the item. [AppleMusicNameRegion.Original] asks
         * for names in the item's own script: for [UnitedStates], accept a Latin name that removes
         * Katakana/Hangul/Han transliteration; for [Korea] artist names, preserve official international
         * names (e.g. NewJeans, ROSÉ, IVE, ILLIT) instead of Hangul transliterations; for Chinese targets,
         * only replace text that does not already contain Han characters (protecting Japanese Kanji and
         * Traditional Chinese from being simplified).
         */
        fun accepts(
            region: AppleMusicNameRegion,
            target: OriginalNameTarget,
            shown: String?,
            resolved: String?,
            isArtist: Boolean = false,
        ): Boolean {
            val answer = resolved?.trim().orEmpty()
            val shownText = shown?.trim().orEmpty()
            if (answer.isEmpty() || answer == shownText) return false
            if (region != AppleMusicNameRegion.Original) return true
            if (isArtist && (isCompilationArtist(shownText) || isCompilationArtist(answer))) {
                return isCompilationArtist(shownText) && isCompilationArtist(answer) &&
                    latinLetters(answer) > 0 && nonLatinLetters(answer) < nonLatinLetters(shownText)
            }
            if (target == UnitedStates) {
                return latinLetters(answer) > 0 && nonLatinLetters(answer) < nonLatinLetters(shownText)
            }
            if (isArtist && target == Korea) return false
            if (nonLatinLetters(answer) == 0) return false
            if (isChinese(target)) {
                return hasHan(answer) && !hasHan(shownText)
            }
            return true
        }

        fun isCompilationArtist(name: String?): Boolean {
            val normalized = name?.trim()?.lowercase().orEmpty()
            return normalized.isNotEmpty() && normalized in COMPILATION_ARTISTS
        }

        /**
         * True when [detect] routed to [UnitedStates] solely from pure-Katakana script without genre or ISRC
         * confirmation — the resolver verifies against Apple's track/top-song ISRCs so native Japanese Katakana
         * acts (e.g. ヨルシカ, サカナクション, ササノマリイ) are never romanized.
         */
        fun isKatakanaSpeculative(title: String?, artist: String?, genre: String?, isrc: String?): Boolean {
            if (forGenre(genre) != null || forIsrc(isrc) != null) return false
            return looksLikeKatakanaTransliteration("${title.orEmpty()} ${artist.orEmpty()}")
        }

        /**
         * True when [detect] routed to [Taiwan] solely via a generic Mandopop genre without an explicit
         * `CN`/`TW`/`HK` ISRC — the resolver inspects nested tracks/top-songs ISRCs so Mainland China
         * artists/albums (`CN` ISRCs, e.g. 单依纯, 新裤子, 陈粒) stay Simplified Chinese while Taiwan acts
         * stay Traditional Chinese.
         */
        fun isMandopopGeneric(genre: String?, isrc: String?): Boolean {
            if (forIsrc(isrc)?.let(::isChinese) == true) return false
            return forGenre(genre) == Taiwan
        }

        fun hasJapaneseScript(text: String): Boolean = text.any(::isHiragana) || hasHan(text)

        fun isChinese(target: OriginalNameTarget) =
            target == MainlandChina || target == Taiwan || target == HongKong

        fun hasNonLatin(text: String): Boolean = nonLatinLetters(text) > 0

        fun hasHanText(text: String): Boolean = hasHan(text)

        private fun needsChineseRecovery(title: String?, artist: String?): Boolean {
            val parts = listOfNotNull(
                title?.trim()?.takeIf { it.isNotEmpty() },
                artist?.trim()?.takeIf { it.isNotEmpty() },
            )
            return parts.isEmpty() || parts.any { !hasHan(it) }
        }

        private fun isHiragana(c: Char) = c.code in 0x3040..0x309F
        private fun isKatakana(c: Char) = c.code in 0x30A1..0x30FA || c.code in 0x31F0..0x31FF
        private fun isKana(c: Char) = c.code in 0x3040..0x30FF || c.code in 0x31F0..0x31FF
        private fun isHangul(c: Char) = c.code in 0xAC00..0xD7AF || c.code in 0x1100..0x11FF || c.code in 0x3130..0x318F

        private fun looksLikeKatakanaTransliteration(text: String): Boolean {
            if (hasHan(text) || text.any(::isHangul)) return false
            var katakana = 0
            var hiragana = 0
            for (c in text) {
                when {
                    isKatakana(c) -> katakana++
                    isHiragana(c) -> hiragana++
                }
            }
            if (katakana == 0) return false
            if (hiragana == 0) return true
            // Apple Music JP occasionally spells a Western Katakana name with a single Hiragana typo
            // alongside a Katakana middle dot (e.g. "べス・ギボンズ" for Beth Gibbons, using Hiragana べ).
            return hiragana == 1 && katakana >= 2 && text.contains('・')
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
