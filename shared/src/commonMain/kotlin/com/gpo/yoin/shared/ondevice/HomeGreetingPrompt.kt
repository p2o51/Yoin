package com.gpo.yoin.shared.ondevice

/**
 * The three daily greeting slots pre-generated in one Gemini Nano call so Home's greeting is instant on cold
 * start with zero model-load wait (owner 2026-11-22).
 */
enum class HomeGreetingSlot { MORNING, AFTERNOON, EVENING }

/** One day's cached greetings for a specific language and account. */
data class DailyHomeGreetings(
    val day: Long,
    val localeTag: String,
    val profileId: String,
    val morning: String?,
    val afternoon: String?,
    val evening: String?,
    val cuesFingerprint: String = "",
    val refreshCount: Int = 0,
) {
    fun textFor(slot: HomeGreetingSlot): String? = when (slot) {
        HomeGreetingSlot.MORNING -> morning
        HomeGreetingSlot.AFTERNOON -> afternoon
        HomeGreetingSlot.EVENING -> evening
    }

    fun hasAny(): Boolean = morning != null || afternoon != null || evening != null
}

/**
 * Prompt builder and strict validator for Home's 3-slot daily greetings (pure Kotlin, fully unit-tested). Moved
 * from Android's `data/highlight/` to :shared on 2026-11-27 (parity p5) unchanged, so iOS asks Apple Intelligence
 * the same words; days and months are ISO numbers here (Monday = 1, January = 1) where Android passed `java.time`
 * enums (the app's `HomeGreetingPrompt.kt` keeps those overloads). Pinned by `golden/ondevice-ai.json`.
 *
 * Tuned on real Galaxy Z Fold 8 hardware (`nano-v4-fast`, 2026-11-22) with team UX review:
 * - **Distinct per-slot cues ([SlotCues])**: morning anchors on the latest play, afternoon on the week's top
 *   artist/album, and evening on a rediscover/older favorite so the three slots never repeat the same record.
 * - **De-prescriptive Yoin voice**: observational music notes instead of wellness-app commands ("放松一下").
 * - **Visual width measurement**: CJK/full-width = 2, ASCII = 1, so mixed ZH + English band names like
 *   `这周常听 Radiohead 的《In Rainbows》` are never falsely dropped.
 * - **Grounding & 7-day dedup**: blocks few-shot example bleed (unless the listener actually played that artist)
 *   and skips exact phrases shown in the last 7 days.
 */
object HomeGreetingPrompt {

    /** A local play record used to anchor a greeting slot in real listening history. */
    data class RecentPlay(val artist: String, val album: String = "") {
        fun clean(): RecentPlay? {
            val cleanArtist = artist.trim()
            return if (cleanArtist.isEmpty()) null else RecentPlay(cleanArtist, album.trim())
        }
    }

    /** Up to three distinct listening cues — one per time-of-day slot. */
    data class SlotCues(
        val morning: RecentPlay? = null,
        val afternoon: RecentPlay? = null,
        val evening: RecentPlay? = null,
    ) {
        fun allPlays(): List<RecentPlay> = listOfNotNull(morning, afternoon, evening)
        fun hasAny(): Boolean = morning != null || afternoon != null || evening != null

        fun fingerprint(): String = listOf(morning, afternoon, evening).joinToString("|") { play ->
            if (play == null) "" else "${play.artist.lowercase()}::${play.album.lowercase()}"
        }
    }

    /** Output budget: three natural short phrases in structured/JSON format (~75–120 tokens). */
    const val MAX_OUTPUT_TOKENS = 128

    /** Sampling for the greeting call (Android HomeGreetingStore, tuned on the Fold 8). */
    const val TEMPERATURE = 0.55f
    const val TOP_K = 24

    /** Visual-width bounds for ZH (CJK/full-width = 2, ASCII = 1) so English band names fit cleanly. */
    const val MIN_ZH_WIDTH = 4
    const val MAX_ZH_WIDTH = 38

    /** Character and word bounds for EN greetings. */
    const val MIN_EN_CHARS = 3
    const val MAX_EN_CHARS = 44
    const val MAX_EN_WORDS = 8

    /** Normalizes any BCP-47 tag into the prompt language bucket ("zh" or "en"). */
    fun normalizeLocale(languageTag: String): String =
        if (languageTag.lowercase().startsWith("zh")) "zh" else "en"

    /** Computes approximate half-width visual units (CJK / full-width = 2, ASCII / half-width = 1). */
    fun visualWidth(text: String): Int {
        var width = 0
        for (ch in text) {
            width += if (ch.code >= 0x1100) 2 else 1
        }
        return width
    }

    /**
     * System instruction paired with [userPrompt] for ML Kit GenAI Structured Output (`HomeGreetingSchema`).
     * Uses distinct per-slot listening cues when [hasAnyCue] is true, and everyday weekday music examples when
     * the listener has no local listening history yet.
     */
    fun systemInstruction(hasAnyCue: Boolean, localeTag: String): String {
        val lang = normalizeLocale(localeTag)
        return if (lang == "zh") {
            if (hasAnyCue) {
                """
                你是音乐播放器 Yoin 的首页短问候作者，为早(morning)、午(afternoon)、晚(evening)各写一句简短中文（无表情、无感叹号）。
                风格：平实克制，像朋友随口提起你听过的唱片；不要劝人放松或说教（不要写「听点舒缓的」「放松一下」「放慢节奏」），午间那句不要写「下午」。
                规则：
                1. 若输入给了某时段的听歌线索，就自然围绕它写，句式尽量变化，不要生硬照搬标签词；没给线索的时段就结合当天星期写一句日常听歌短句。
                2. 英文艺人名和专辑名必须保留原文，不要翻译成中文；绝对不要编造输入里没出现过的艺人或专辑。
                示例：
                输入：十一月 · 周五
                早·刚听过：Perfume《LEVEL3》
                午·这周常听：坂本龙一《Async》
                晚·好久没放：Bill Evans《Waltz for Debby》
                输出：{"morning":"昨晚的《LEVEL3》还没听完","afternoon":"这周一直在听坂本龙一","evening":"好久没放 Bill Evans 了"}
                输入：十月 · 周日
                早·刚听过：陈绮贞《华丽的冒险》
                输出：{"morning":"周日早，继续听《华丽的冒险》","afternoon":"午间适合放一整张专辑","evening":"周日晚，留一首歌收尾"}
                """.trimIndent()
            } else {
                """
                你是音乐播放器 Yoin 的首页短问候作者，为今天早(morning)、午(afternoon)、晚(evening)各写一句简短中文（无表情、无感叹号）。
                风格：平实、克制、只围绕日常随手点开听歌的瞬间；不要劝人放松或说教，午间那句不要写「下午」。
                参考示例：
                输入：十一月 · 周二
                输出：{"morning":"周二早，先泡杯热的听歌","afternoon":"午间挑张专辑听","evening":"天黑了，放张喜欢的唱片"}
                输入：四月 · 周五
                输出：{"morning":"周五早上，快到周末了","afternoon":"再过半天就周末了","evening":"周五夜，把音量开大"}
                输入：十月 · 周日
                输出：{"morning":"周日不着急，慢慢听","afternoon":"午后晒着太阳听整砖","evening":"周日晚，留一首歌收尾"}
                """.trimIndent()
            }
        } else {
            if (hasAnyCue) {
                """
                Write three short, grounded Home header greetings (morning, afternoon, evening; under 42 chars, no emojis, no exclamation marks) for music player Yoin.
                Tone: plain, observational, like a friend noticing what's on the turntable. Never tell the listener to relax or unwind, and avoid forced puns.
                Rules:
                1. Use each slot's cue when given; for any slot without a cue, write a plain everyday music line matching the day of the week.
                2. Keep artist and album names exact; never invent names not in the input.
                Examples:
                Input: November · Friday
                Morning (just played): Perfume - LEVEL3
                Afternoon (played often): Ryuichi Sakamoto - Async
                Evening (a favorite from before): Bill Evans - Waltz for Debby
                Output: {"morning":"Still spinning LEVEL3","afternoon":"Ryuichi Sakamoto all week","evening":"Been a while since Bill Evans"}
                Input: October · Sunday
                Morning (just played): The Beatles - Abbey Road
                Output: {"morning":"Sunday starts with Abbey Road","afternoon":"Time for a full album","evening":"One last track tonight"}
                """.trimIndent()
            } else {
                """
                Write three short Home header greetings (morning, afternoon, evening; 2-6 words, under 40 chars) for the music player Yoin.
                Style: plain, grounded, everyday moments of pressing play matching the day of the week. No emojis, no exclamation marks, never tell the listener to relax.
                Examples:
                Input: November · Tuesday
                Output: {"morning":"Tuesday coffee, press play","afternoon":"Pick an album now","evening":"Dark out, music on"}
                Input: April · Friday
                Output: {"morning":"Friday at last","afternoon":"Almost the weekend","evening":"Friday night, turn up"}
                Input: October · Sunday
                Output: {"morning":"No rush this Sunday","afternoon":"Lazy Sunday full album","evening":"One last record tonight"}
                """.trimIndent()
            }
        }
    }

    /** Compact user input paired with [systemInstruction], omitting any slot that has no cue. */
    fun userPrompt(
        cues: SlotCues,
        dayOfWeek: Int,
        month: Int? = null,
        localeTag: String,
        avoidRecent: Collection<String> = emptyList(),
    ): String {
        val lang = normalizeLocale(localeTag)
        val avoid = avoidRecent.map(String::trim).filter(String::isNotEmpty).distinct().take(3)
        return if (lang == "zh") {
            buildString {
                if (month != null) {
                    append(zhMonth(month)).append(" · ")
                }
                append(zhDayOfWeek(dayOfWeek))
                cues.morning?.clean()?.let {
                    append("\n早·刚听过：").append(formatZhPlay(it))
                }
                cues.afternoon?.clean()?.let {
                    append("\n午·这周常听：").append(formatZhPlay(it))
                }
                cues.evening?.clean()?.let {
                    append("\n晚·好久没放：").append(formatZhPlay(it))
                }
                if (avoid.isNotEmpty()) {
                    append("\n最近用过（换种说法）：").append(avoid.joinToString(" / "))
                }
            }
        } else {
            buildString {
                if (month != null) {
                    append(enMonth(month)).append(" · ")
                }
                append(enDayOfWeek(dayOfWeek))
                cues.morning?.clean()?.let {
                    append("\nMorning (just played): ").append(formatEnPlay(it))
                }
                cues.afternoon?.clean()?.let {
                    append("\nAfternoon (played often): ").append(formatEnPlay(it))
                }
                cues.evening?.clean()?.let {
                    append("\nEvening (a favorite from before): ").append(formatEnPlay(it))
                }
                if (avoid.isNotEmpty()) {
                    append("\nUsed recently (vary phrasing): ").append(avoid.joinToString(" / "))
                }
            }
        }
    }

    /** Convenience overload accepting a flat list of recent plays (mapped into distinct slots). */
    fun userPrompt(
        recentPlays: List<RecentPlay>,
        dayOfWeek: Int,
        month: Int? = null,
        localeTag: String,
    ): String = userPrompt(
        cues = cuesFromFlatList(recentPlays),
        dayOfWeek = dayOfWeek,
        month = month,
        localeTag = localeTag,
    )

    /** Builds the combined plain-text fallback prompt (used when structured output is unavailable). */
    fun buildPrompt(
        cues: SlotCues,
        dayOfWeek: Int,
        month: Int? = null,
        localeTag: String,
        avoidRecent: Collection<String> = emptyList(),
    ): String {
        val lang = normalizeLocale(localeTag)
        val formatRule = if (lang == "zh") {
            "只输出 JSON：{\"morning\":\"...\",\"afternoon\":\"...\",\"evening\":\"...\"}，不要其他文字。"
        } else {
            "Answer with JSON only: {\"morning\":\"...\",\"afternoon\":\"...\",\"evening\":\"...\"}. No other text."
        }
        val inputLabel = if (lang == "zh") "输入：" else "Input: "
        return systemInstruction(cues.hasAny(), lang) + "\n" + formatRule + "\n\n" +
            inputLabel + userPrompt(cues, dayOfWeek, month, lang, avoidRecent) + "\n"
    }

    /** Convenience overload accepting artist names only. */
    fun buildPrompt(
        recentArtists: List<String>,
        dayOfWeek: Int,
        localeTag: String,
        month: Int? = null,
    ): String = buildPrompt(
        cues = cuesFromFlatList(recentArtists.map { RecentPlay(it) }),
        dayOfWeek = dayOfWeek,
        month = month,
        localeTag = localeTag,
    )

    private fun cuesFromFlatList(recentPlays: List<RecentPlay>): SlotCues {
        val clean = recentPlays
            .mapNotNull(RecentPlay::clean)
            .distinctBy { it.artist.lowercase() }
            .take(3)
        return SlotCues(
            morning = clean.getOrNull(0),
            afternoon = clean.getOrNull(1),
            evening = clean.getOrNull(2),
        )
    }

    private fun formatZhPlay(play: RecentPlay): String =
        if (play.album.isNotEmpty()) "${play.artist}《${play.album}》" else play.artist

    private fun formatEnPlay(play: RecentPlay): String =
        if (play.album.isNotEmpty()) "${play.artist} - ${play.album}" else play.artist

    private val WHITESPACE = Regex("\\s+")

    private val MORNING_RE = Regex("\"?morning\"?\\s*[:=]\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val AFTERNOON_RE = Regex("\"?afternoon\"?\\s*[:=]\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val EVENING_RE = Regex("\"?evening\"?\\s*[:=]\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    private val FORBIDDEN_PREFIXES = listOf(
        "morning:", "afternoon:", "evening:", "早上:", "午后:", "下午:", "晚上:", "夜晚:",
        "早上：", "午后：", "下午：", "晚上：", "夜晚：", "here ", "sure", "好的", "以下是",
    )

    private val FORBIDDEN_PHRASES = listOf(
        "听点舒缓", "放松一下", "准备放松", "放慢节奏", "无特定", "暂无",
        "is missing", "week top",
    )

    /** Few-shot example names that must never leak into real output unless actually in the listener's cues. */
    private val EXAMPLE_ENTITIES = listOf(
        "perfume", "level3", "坂本龙一", "ryuichi sakamoto", "async",
        "bill evans", "waltz for debby", "陈绮贞", "华丽的冒险",
        "the beatles", "abbey road", "方大同", "橙月",
        "norah jones", "come away with me", "miles davis", "kind of blue",
    )

    /** Parses raw text JSON output and strictly validates the three daily greetings via [validateSlots]. */
    fun parse(
        answer: String?,
        day: Long,
        localeTag: String,
        profileId: String,
        cues: SlotCues = SlotCues(),
        avoidRecent: Collection<String> = emptyList(),
    ): DailyHomeGreetings? {
        if (answer.isNullOrBlank()) return null
        val body = answer.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .replace('“', '"')
            .replace('”', '"')
            .trim()
        return validateSlots(
            morning = MORNING_RE.find(body)?.groupValues?.get(1),
            afternoon = AFTERNOON_RE.find(body)?.groupValues?.get(1),
            evening = EVENING_RE.find(body)?.groupValues?.get(1),
            day = day,
            localeTag = localeTag,
            profileId = profileId,
            cues = cues,
            avoidRecent = avoidRecent,
        )
    }

    /**
     * Validates and trims three candidate slots (shared by structured output [HomeGreetingSchema] and raw-text
     * [parse]). Drops slots that fail length, script, tone, example-bleed, or 7-day repeat checks. Returns null
     * only when fewer than two valid slots survive (or zero when 7-day dedup filtered repeats).
     */
    fun validateSlots(
        morning: String?,
        afternoon: String?,
        evening: String?,
        day: Long,
        localeTag: String,
        profileId: String,
        cues: SlotCues = SlotCues(),
        avoidRecent: Collection<String> = emptyList(),
    ): DailyHomeGreetings? {
        val lang = normalizeLocale(localeTag)
        val allowedNames = cues.allPlays()
            .flatMap { listOf(it.artist.lowercase(), it.album.lowercase()) }
            .filter(String::isNotEmpty)
            .toSet()
        val recentNorm = avoidRecent.mapTo(HashSet(), ::normalizePhrase)

        fun check(raw: String?): String? {
            val clean = sanitize(raw, lang) ?: return null
            if (leakedExampleEntity(clean, allowedNames)) return null
            val norm = normalizePhrase(clean)
            if (norm.isNotEmpty() && norm in recentNorm) return null
            return clean
        }

        val cleanMorning = check(morning)
        val cleanAfternoon = check(afternoon)
        val cleanEvening = check(evening)
        val validCount = listOfNotNull(cleanMorning, cleanAfternoon, cleanEvening).size
        if (validCount < 2) return null
        return DailyHomeGreetings(
            day = day,
            localeTag = lang,
            profileId = profileId,
            morning = cleanMorning,
            afternoon = cleanAfternoon,
            evening = cleanEvening,
            cuesFingerprint = cues.fingerprint(),
        )
    }

    /** True when [text] mentions a few-shot example artist/album that was NOT in the user's own cues. */
    fun leakedExampleEntity(text: String, allowedNamesLower: Set<String>): Boolean {
        val lower = text.lowercase()
        return EXAMPLE_ENTITIES.any { entity ->
            entity in lower && allowedNamesLower.none { allowed -> entity in allowed || allowed in entity }
        }
    }

    /** True when a middle-slot greeting says "下午"/"afternoon" and should not be shown at Noon (11:00–13:00). */
    fun conflictsWithNoon(text: String): Boolean {
        val lower = text.lowercase()
        return "下午" in text || "afternoon" in lower
    }

    /** Validates and trims one candidate greeting, or returns null when it violates width/script/tone rules. */
    fun sanitize(raw: String?, localeTag: String): String? {
        if (raw.isNullOrBlank()) return null
        val lang = normalizeLocale(localeTag)
        if ('\n' in raw || '\r' in raw) return null
        val cleaned = raw.trim()
            .trim('"', '\'', '「', '」', '『', '』')
            .trimEnd('.', '。', '!', '！')
            .replace(WHITESPACE, " ")
            .trim()
        if (cleaned.isEmpty()) return null
        if (containsEmojiOrSymbolNoise(cleaned)) return null
        if ('{' in cleaned || '}' in cleaned || '[' in cleaned || ']' in cleaned) return null
        val lower = cleaned.lowercase()
        if (FORBIDDEN_PREFIXES.any { lower.startsWith(it) }) return null
        if (FORBIDDEN_PHRASES.any { it in lower }) return null
        if (cleaned.contains("...") || cleaned.contains("…")) return null

        return if (lang == "zh") {
            val width = visualWidth(cleaned)
            if (width !in MIN_ZH_WIDTH..MAX_ZH_WIDTH) return null
            val cjkCount = cleaned.count(::isCjk)
            if (cjkCount < 2) return null
            cleaned
        } else {
            if (cleaned.length !in MIN_EN_CHARS..MAX_EN_CHARS) return null
            if (cleaned.any(::isCjk)) return null
            if (cleaned.none { it in 'a'..'z' || it in 'A'..'Z' }) return null
            val words = cleaned.split(' ').filter(String::isNotEmpty)
            if (words.size !in 1..MAX_EN_WORDS) return null
            cleaned
        }
    }

    fun normalizePhrase(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private fun isCjk(ch: Char): Boolean = ch in '\u4E00'..'\u9FFF'

    private fun containsEmojiOrSymbolNoise(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val low = if (i + 1 < text.length) text[i + 1] else null
            val cp = if (ch.isHighSurrogate() && low != null && low.isLowSurrogate()) {
                0x10000 + ((ch.code - 0xD800) shl 10) + (low.code - 0xDC00)
            } else {
                ch.code
            }
            // A lone surrogate (the JVM's Character.SURROGATE) is noise too.
            if (cp in 0xD800..0xDFFF || isOtherSymbolCodePoint(cp)) return true
            if (cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF) return true
            i += if (cp >= 0x10000) 2 else 1
        }
        return false
    }

    private val ZH_MONTHS = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")
    private val EN_MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val ZH_DAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val EN_DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    /** [month] 1..12 (January = 1). */
    private fun zhMonth(month: Int): String = ZH_MONTHS[month - 1]

    private fun enMonth(month: Int): String = EN_MONTHS[month - 1]

    /** [day] ISO 1..7 (Monday = 1), as `java.time.DayOfWeek.value`. */
    private fun zhDayOfWeek(day: Int): String = ZH_DAYS[day - 1]

    private fun enDayOfWeek(day: Int): String = EN_DAYS[day - 1]
}
