package com.gpo.yoin.data.highlight

import java.time.DayOfWeek

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
 * One short Nano call generates morning, afternoon, and evening greetings together. Each slot is checked for
 * language, length (so it always stays on one line above "Home"), and tone before being saved; any slot that
 * fails validation is left null so Home falls back to its static time-of-day string.
 */
object HomeGreetingPrompt {

    /** Output budget: three ultra-short phrases in a JSON object (~60–80 tokens). */
    const val MAX_OUTPUT_TOKENS = 96

    /** Hard length bounds so greetings never wrap on compact screens. */
    const val MIN_ZH_CHARS = 2
    const val MAX_ZH_CHARS = 10
    const val MIN_EN_CHARS = 2
    const val MAX_EN_CHARS = 26
    const val MAX_EN_WORDS = 4

    /** Normalizes any BCP-47 tag into the prompt language bucket ("zh" or "en"). */
    fun normalizeLocale(languageTag: String): String =
        if (languageTag.lowercase().startsWith("zh")) "zh" else "en"

    /**
     * Builds the combined prompt asking Gemini Nano for all three time-of-day greetings in one JSON object.
     * [recentArtists] is kept to at most 2 names so the prompt stays tiny and avoids awkward name-dropping.
     */
    fun buildPrompt(
        recentArtists: List<String>,
        dayOfWeek: DayOfWeek,
        localeTag: String,
    ): String {
        val lang = normalizeLocale(localeTag)
        val artists = recentArtists.map(String::trim).filter(String::isNotEmpty).distinct().take(2)
        return if (lang == "zh") {
            buildString {
                append(
                    "为音乐播放器首页写今天三个时段的极短问候语（平实自然，不抒情，不打鸡血）：\n" +
                        "- morning（早上）、afternoon（下午）、evening（晚上）\n" +
                        "要求：\n" +
                        "1. 简体中文，每句 4 到 8 个字（绝不超过 10 个字），不换行，不用表情、引号、感叹号。\n" +
                        "2. 只围绕听歌与今天的星期节奏（如周一开启、周五临近周末、周末慢听），不要提上班干活、加油或聊天。\n" +
                        "3. 禁止使用「旋律、晨光、夜色、静谧、共鸣、轻柔、入耳、漫步」等文艺套话，也不要硬塞歌手名。\n" +
                        "4. 只输出 JSON：{\"morning\":\"...\",\"afternoon\":\"...\",\"evening\":\"...\"}，不要其他文字。\n\n",
                )
                append("今天是：").append(zhDayOfWeek(dayOfWeek)).append("（").append(zhDayHint(dayOfWeek)).append("）\n")
                if (artists.isNotEmpty()) {
                    append("最近常听：").append(artists.joinToString("、")).append('\n')
                }
            }
        } else {
            buildString {
                append(
                    "Write three plain, everyday one-line greetings for a music player's Home header (morning, afternoon, evening).\n" +
                        "Rules:\n" +
                        "1. English only, 2 to 4 words each (strictly under 26 characters), single line, no emojis, quotes, or exclamation marks.\n" +
                        "2. Match the feel of the day of the week (weekday start, Friday wind-down, weekend pace); sound like a real person, not poetry.\n" +
                        "3. Do NOT use cliché words like soft, gentle, mellow, tunes, notes, flow, or unfolds, and do not force artist names in.\n" +
                        "4. Answer with JSON only: {\"morning\":\"...\",\"afternoon\":\"...\",\"evening\":\"...\"}. No other text.\n\n",
                )
                append("Today: ").append(enDayOfWeek(dayOfWeek)).append(" (").append(enDayHint(dayOfWeek)).append(")\n")
                if (artists.isNotEmpty()) {
                    append("Recently played: ").append(artists.joinToString(", ")).append('\n')
                }
            }
        }
    }

    private fun zhDayHint(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "新一周刚开始"
        DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY -> "普通工作日"
        DayOfWeek.FRIDAY -> "快到周末了"
        DayOfWeek.SATURDAY -> "周六休息日"
        DayOfWeek.SUNDAY -> "周日慢节奏"
    }

    private fun enDayHint(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "start of the week"
        DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY -> "midweek workday"
        DayOfWeek.FRIDAY -> "almost the weekend"
        DayOfWeek.SATURDAY -> "Saturday off"
        DayOfWeek.SUNDAY -> "easy Sunday"
    }

    private val MORNING_RE = Regex("\"?morning\"?\\s*[:=]\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val AFTERNOON_RE = Regex("\"?afternoon\"?\\s*[:=]\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val EVENING_RE = Regex("\"?evening\"?\\s*[:=]\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    private val FORBIDDEN_PREFIXES = listOf(
        "morning:", "afternoon:", "evening:", "早上:", "午后:", "下午:", "晚上:", "夜晚:",
        "早上：", "午后：", "下午：", "晚上：", "夜晚：", "here ", "sure", "好的", "以下是",
    )

    /**
     * Parses and strictly validates the three daily greetings. Returns null if fewer than two valid slots
     * survive validation (guarding against garbled output); any individual missing slot falls back to static copy.
     */
    fun parse(
        answer: String?,
        day: Long,
        localeTag: String,
        profileId: String,
    ): DailyHomeGreetings? {
        if (answer.isNullOrBlank()) return null
        val lang = normalizeLocale(localeTag)
        val body = answer.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .replace('“', '"')
            .replace('”', '"')
            .trim()
        val morning = sanitize(MORNING_RE.find(body)?.groupValues?.get(1), lang)
        val afternoon = sanitize(AFTERNOON_RE.find(body)?.groupValues?.get(1), lang)
        val evening = sanitize(EVENING_RE.find(body)?.groupValues?.get(1), lang)
        val validCount = listOfNotNull(morning, afternoon, evening).size
        if (validCount < 2) return null
        return DailyHomeGreetings(
            day = day,
            localeTag = lang,
            profileId = profileId,
            morning = morning,
            afternoon = afternoon,
            evening = evening,
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
