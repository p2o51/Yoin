package com.gpo.yoin.data.source.applemusic

/**
 * Which storefront an Apple Music account shows song, album and artist names from, set per account
 * (Settings › the Apple Music account). An account's own storefront writes every song in its language:
 * the US storefront calls 晴る by ヨルシカ "Sunny" by "Yorushika", and Japan's calls Kim Petras キム・ペトラス.
 * The catalog serves the same song in every storefront under the same id, so asking another storefront
 * for it, in that storefront's language, brings its names from there.
 */
enum class AppleMusicNameRegion(val key: String) {
    /** The account's own storefront: names as Apple Music shows them. */
    Off("off"),

    /** Each song from the storefront of the language it was released in ([OriginalNameTarget.detect]). */
    Original("original"),

    /** Every song from China mainland's storefront, in Simplified Chinese. */
    MainlandChina("cn"),

    /** Every song from Japan's storefront, in Japanese. */
    Japan("jp");

    companion object {
        val Default = Off

        fun fromKey(key: String?): AppleMusicNameRegion = entries.firstOrNull { it.key == key } ?: Default
    }
}

/** A storefront to ask for names, and the language to ask it in (the `l` parameter). */
data class OriginalNameTarget(val storefront: String, val language: String) {
    companion object {
        val Japan = OriginalNameTarget("jp", "ja")
        val Korea = OriginalNameTarget("kr", "ko")
        val MainlandChina = OriginalNameTarget("cn", "zh-Hans-CN")
        private val Thailand = OriginalNameTarget("th", "th")
        private val Russia = OriginalNameTarget("ru", "ru")
        private val Ukraine = OriginalNameTarget("ua", "uk")
        private val SaudiArabia = OriginalNameTarget("sa", "ar")
        private val Israel = OriginalNameTarget("il", "he")
        private val India = OriginalNameTarget("in", "hi")
        private val Greece = OriginalNameTarget("gr", "el")
        private val Bulgaria = OriginalNameTarget("bg", "bg")

        /**
         * The storefront [region] asks for an item's names, or null when it keeps the shown ones. [Original]
         * goes by the item's own language ([detect]); the fixed regions ask their storefront for everything.
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
            AppleMusicNameRegion.MainlandChina -> MainlandChina
            AppleMusicNameRegion.Japan -> Japan
        }

        /**
         * The language a song was released in, as well as its metadata tells: its genre first (J-Pop,
         * Mandopop…, in English or in Chinese as the CN storefront writes them), then the country its
         * ISRC was registered in, then the script its shown names are written in. Null when nothing points
         * at a language whose storefront writes names differently from English: a song in English keeps
         * the names it has.
         */
        fun detect(title: String?, artist: String?, genre: String?, isrc: String?): OriginalNameTarget? =
            forGenre(genre) ?: forIsrc(isrc) ?: forScript("${title.orEmpty()} ${artist.orEmpty()}")

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
        fun forIsrc(isrc: String?): OriginalNameTarget? = when (isrc?.trim()?.take(2)?.uppercase()) {
            "JP" -> Japan
            "KR" -> Korea
            "CN", "HK", "MO", "TW" -> MainlandChina
            else -> null
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
         * for names in the item's own script, so it takes only an answer that is: one that differs and
         * carries a letter outside the Latin script (a storefront without the song in its own language
         * answers in English), and, for Chinese, a Han character (not a Japanese or Korean name).
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
            if (nonLatinLetters(answer) == 0) return false
            return target != MainlandChina || hasHan(answer)
        }

        private fun isKana(c: Char) = c.code in 0x3040..0x30FF || c.code in 0x31F0..0x31FF
        private fun isHangul(c: Char) = c.code in 0xAC00..0xD7AF || c.code in 0x1100..0x11FF || c.code in 0x3130..0x318F

        private fun hasHan(text: String): Boolean = text.codePoints().anyMatch {
            Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
        }

        private fun nonLatinLetters(text: String): Int = text.codePoints().filter {
            Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
        }.count().toInt()
    }
}
