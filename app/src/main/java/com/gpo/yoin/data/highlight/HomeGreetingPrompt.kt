package com.gpo.yoin.data.highlight

import java.time.DayOfWeek
import java.time.Month

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
) {
    fun textFor(slot: HomeGreetingSlot): String? = when (slot) {
        HomeGreetingSlot.MORNING -> morning
        HomeGreetingSlot.AFTERNOON -> afternoon
        HomeGreetingSlot.EVENING -> evening
    }

    fun hasAny(): Boolean = morning != null || afternoon != null || evening != null
}

/**
 * Prompt builder and strict validator for Home's 3-slot daily greetings (pure Kotlin, fully unit-tested).
 *
 * Tuned on real Galaxy Z Fold 8 hardware (`nano-v4-fast`, 2026-11-22):
 * - When local play history has a recent artist/album, uses a 2-shot **listening-anchored** prompt (Recipe B)
 *   so one slot naturally nods to what the listener actually played while the other two match the day/time.
 *   Anchoring eliminates Nano's weekday/evening cross-slot drift and generic boilerplate.
 * - When play history is empty (fresh install), falls back to a 3-shot **everyday Yoin** prompt (Recipe A)
 *   grounded in month + weekday music moments.
 * - Each slot is validated for language, length (single line above "Home"), and tone before being saved.
 */
object HomeGreetingPrompt {

    /** A recent local play used to anchor greetings in what the listener actually listens to. */
    data class RecentPlay(val artist: String, val album: String = "")

    /** Output budget: three short phrases in structured/JSON format (~65–95 tokens). */
    const val MAX_OUTPUT_TOKENS = 96

    /** Hard length bounds so greetings stay single-line above "Home" even on narrow cover screens. */
    const val MIN_ZH_CHARS = 2
    const val MAX_ZH_CHARS = 12
    const val MIN_EN_CHARS = 2
    const val MAX_EN_CHARS = 26
    const val MAX_EN_WORDS = 4

    /** Normalizes any BCP-47 tag into the prompt language bucket ("zh" or "en"). */
    fun normalizeLocale(languageTag: String): String =
        if (languageTag.lowercase().startsWith("zh")) "zh" else "en"

    /**
     * System instruction paired with [userPrompt] for ML Kit GenAI Structured Output (`HomeGreetingSchema`).
     * Uses Recipe B (listening-anchored) whenever [hasRecentPlays] is true, and Recipe A (3-shot everyday Yoin)
     * when the listener has no local play history yet.
     */
    fun systemInstruction(hasRecentPlays: Boolean, localeTag: String): String {
        val lang = normalizeLocale(localeTag)
        return if (lang == "zh") {
            if (hasRecentPlays) {
                """
                你是音乐播放器 Yoin 的首页问候作者。根据日期和用户最近播过的唱片，写今天早、午、晚三句极短中文问候（每句 4 到 10 个字，绝不超过 12 字，无表情无感叹号）。
                规则：其中一句自然提起最近听过的艺人或专辑，另外两句贴合当天星期与时段；全围绕听歌，语气像懂音乐的朋友，平实克制。
                示例：
                输入：十一月 · 周五 | 最近播过：Perfume《LEVEL3》
                输出：{"morning":"周五早，来点电子提神","afternoon":"下午接着听 Perfume","evening":"周五夜，放张整砖"}
                输入：十一月 · 周日 | 最近播过：坂本龙一《Async》
                输出：{"morning":"周日不着急，慢慢醒","afternoon":"午后适合再听坂本龙一","evening":"晚安，一首钢琴收尾"}
                """.trimIndent()
            } else {
                """
                你是音乐播放器 Yoin 的首页短问候作者。为今天早、午、晚各写一句 4 到 10 个字的中文短句（绝不超过 12 字）。
                风格：平实、克制、只围绕日常随手点开听歌的瞬间，不带表情和感叹号。
                参考示例：
                输入：十一月 · 周二
                输出：{"morning":"周二早，先泡杯热的听歌","afternoon":"下午了，挑张专辑听","evening":"天黑了，放点安静的"}
                输入：四月 · 周五
                输出：{"morning":"周五早上，快到周末了","afternoon":"再过半天就周末了","evening":"周五夜，把音量开大"}
                输入：十月 · 周日
                输出：{"morning":"周日不着急，慢慢听","afternoon":"午后晒着太阳听整砖","evening":"周日晚，留一首歌收尾"}
                """.trimIndent()
            }
        } else {
            if (hasRecentPlays) {
                """
                Write three short Home header greetings (morning, afternoon, evening; 2-4 words, under 26 chars, no emojis) for music player Yoin.
                One slot may naturally nod to the recently played artist; the other two fit the day and time of day. Plain, tasteful tone.
                Examples:
                Input: November · Friday | Recently played: Perfume - LEVEL3
                Output: {"morning":"Friday boost, press play","afternoon":"Back to Perfume","evening":"Friday night full album"}
                Input: November · Sunday | Recently played: Bill Evans - Waltz for Debby
                Output: {"morning":"Easy Sunday morning","afternoon":"Spin Bill Evans again","evening":"One last track tonight"}
                """.trimIndent()
            } else {
                """
                You write tiny 2-to-4-word Home header greetings (morning, afternoon, evening; under 26 chars) for the music player Yoin.
                Style: plain, grounded, everyday moments of pressing play matching the day of the week. No emojis, no exclamation marks.
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

    /** Compact user input paired with [systemInstruction] (e.g. `十一月 · 周五 | 最近播过：宇多田光《First Love》`). */
    fun userPrompt(
        recentPlays: List<RecentPlay>,
        dayOfWeek: DayOfWeek,
        month: Month? = null,
        localeTag: String,
    ): String {
        val lang = normalizeLocale(localeTag)
        val plays = recentPlays
            .map { RecentPlay(it.artist.trim(), it.album.trim()) }
            .filter { it.artist.isNotEmpty() }
            .distinctBy { it.artist.lowercase() }
            .take(2)
        return if (lang == "zh") {
            buildString {
                if (month != null) {
                    append(zhMonth(month)).append(" · ")
                }
                append(zhDayOfWeek(dayOfWeek))
                if (plays.isNotEmpty()) {
                    append(" | 最近播过：")
                    append(
                        plays.joinToString("、") { play ->
                            if (play.album.isNotEmpty()) "${play.artist}《${play.album}》" else play.artist
                        },
                    )
                }
            }
        } else {
            buildString {
                if (month != null) {
                    append(enMonth(month)).append(" · ")
                }
                append(enDayOfWeek(dayOfWeek))
                if (plays.isNotEmpty()) {
                    append(" | Recently played: ")
                    append(
                        plays.joinToString(", ") { play ->
                            if (play.album.isNotEmpty()) "${play.artist} - ${play.album}" else play.artist
                        },
                    )
                }
            }
        }
    }

    /**
     * Builds the combined plain-text fallback prompt (used when structured output is unavailable on older AICore).
     */
    fun buildPrompt(
        recentPlays: List<RecentPlay>,
        dayOfWeek: DayOfWeek,
        month: Month? = null,
        localeTag: String,
    ): String {
        val lang = normalizeLocale(localeTag)
        val hasPlays = recentPlays.any { it.artist.isNotBlank() }
        val formatRule = if (lang == "zh") {
            "只输出 JSON：{\"morning\":\"...\",\"afternoon\":\"...\",\"evening\":\"...\"}，不要其他文字。"
        } else {
            "Answer with JSON only: {\"morning\":\"...\",\"afternoon\":\"...\",\"evening\":\"...\"}. No other text."
        }
        val inputLabel = if (lang == "zh") "输入：" else "Input: "
        return systemInstruction(hasPlays, lang) + "\n" + formatRule + "\n\n" +
            inputLabel + userPrompt(recentPlays, dayOfWeek, month, lang) + "\n"
    }

    /** Convenience overload accepting artist names only. */
    fun buildPrompt(
        recentArtists: List<String>,
        dayOfWeek: DayOfWeek,
        localeTag: String,
        month: Month? = null,
    ): String = buildPrompt(
        recentPlays = recentArtists.map { RecentPlay(it) },
        dayOfWeek = dayOfWeek,
        month = month,
        localeTag = localeTag,
    )

    private val MORNING_RE = Regex("\"?morning\"?\\s*[:=]\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val AFTERNOON_RE = Regex("\"?afternoon\"?\\s*[:=]\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val EVENING_RE = Regex("\"?evening\"?\\s*[:=]\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    private val FORBIDDEN_PREFIXES = listOf(
        "morning:", "afternoon:", "evening:", "早上:", "午后:", "下午:", "晚上:", "夜晚:",
        "早上：", "午后：", "下午：", "晚上：", "夜晚：", "here ", "sure", "好的", "以下是",
    )

    /**
     * Parses raw text JSON output and strictly validates the three daily greetings via [validateSlots].
     */
    fun parse(
        answer: String?,
        day: Long,
        localeTag: String,
        profileId: String,
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
        )
    }

    /**
     * Validates and trims three candidate slots (shared by structured output [HomeGreetingSchema] and raw-text
     * [parse]). Returns null if fewer than two valid slots survive validation.
     */
    fun validateSlots(
        morning: String?,
        afternoon: String?,
        evening: String?,
        day: Long,
        localeTag: String,
        profileId: String,
    ): DailyHomeGreetings? {
        val lang = normalizeLocale(localeTag)
        val cleanMorning = sanitize(morning, lang)
        val cleanAfternoon = sanitize(afternoon, lang)
        val cleanEvening = sanitize(evening, lang)
        val validCount = listOfNotNull(cleanMorning, cleanAfternoon, cleanEvening).size
        if (validCount < 2) return null
        return DailyHomeGreetings(
            day = day,
            localeTag = lang,
            profileId = profileId,
            morning = cleanMorning,
            afternoon = cleanAfternoon,
            evening = cleanEvening,
        )
    }

    /** Validates and trims one candidate greeting, or returns null when it violates length/script/tone rules. */
    internal fun sanitize(raw: String?, localeTag: String): String? {
        if (raw.isNullOrBlank()) return null
        val lang = normalizeLocale(localeTag)
        if ('\n' in raw || '\r' in raw) return null
        val cleaned = raw.trim()
            .trim('"', '\'', '「', '」', '『', '』')
            .trimEnd('.', '。', '!', '！')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return null
        if (containsEmojiOrSymbolNoise(cleaned)) return null
        if ('{' in cleaned || '}' in cleaned || '[' in cleaned || ']' in cleaned) return null
        val lower = cleaned.lowercase()
        if (FORBIDDEN_PREFIXES.any { lower.startsWith(it) }) return null
        if (cleaned.contains("...") || cleaned.contains("…")) return null

        return if (lang == "zh") {
            if (cleaned.length !in MIN_ZH_CHARS..MAX_ZH_CHARS) return null
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

    private fun isCjk(ch: Char): Boolean = ch in '\u4E00'..'\u9FFF'

    private fun containsEmojiOrSymbolNoise(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            if (type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt()) {
                return true
            }
            if (cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun zhMonth(month: Month): String = when (month) {
        Month.JANUARY -> "一月"
        Month.FEBRUARY -> "二月"
        Month.MARCH -> "三月"
        Month.APRIL -> "四月"
        Month.MAY -> "五月"
        Month.JUNE -> "六月"
        Month.JULY -> "七月"
        Month.AUGUST -> "八月"
        Month.SEPTEMBER -> "九月"
        Month.OCTOBER -> "十月"
        Month.NOVEMBER -> "十一月"
        Month.DECEMBER -> "十二月"
    }

    private fun enMonth(month: Month): String = when (month) {
        Month.JANUARY -> "January"
        Month.FEBRUARY -> "February"
        Month.MARCH -> "March"
        Month.APRIL -> "April"
        Month.MAY -> "May"
        Month.JUNE -> "June"
        Month.JULY -> "July"
        Month.AUGUST -> "August"
        Month.SEPTEMBER -> "September"
        Month.OCTOBER -> "October"
        Month.NOVEMBER -> "November"
        Month.DECEMBER -> "December"
    }

    private fun zhDayOfWeek(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }

    private fun enDayOfWeek(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Monday"
        DayOfWeek.TUESDAY -> "Tuesday"
        DayOfWeek.WEDNESDAY -> "Wednesday"
        DayOfWeek.THURSDAY -> "Thursday"
        DayOfWeek.FRIDAY -> "Friday"
        DayOfWeek.SATURDAY -> "Saturday"
        DayOfWeek.SUNDAY -> "Sunday"
    }
}
