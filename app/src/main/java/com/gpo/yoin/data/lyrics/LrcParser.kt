package com.gpo.yoin.data.lyrics

import com.gpo.yoin.data.model.LyricLine
import com.gpo.yoin.data.model.Lyrics

/**
 * 把 Provider 返回的 LRC 字符串转成 Yoin 的 [Lyrics] sealed 类型。
 *
 * 支持：
 * - `[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss]` 时间戳
 * - 一行多时间戳（展开为多个 [LyricLine]）
 * - 过滤 metadata 标签（`[ar:]`, `[ti:]`, `[al:]`, `[by:]`, `[offset:]`, `[length:]`,
 *   `[lyricist:]`, `[composer:]`）
 *
 * 若解析出 >= 1 条带时间戳的行 → [Lyrics.Synced]（按 startMs 排序）；
 * 否则把原文当 unsynced 返回。
 *
 * 歌词开头 / 结尾的制作信息（作词、作曲、混音、版权声明……）由
 * [LyricCreditsExtractor] 移进 [Lyrics.credits]，不留在歌词行里。所有读歌词的地方
 * （显示、翻译对齐、同步行数）都走这里，所以行号口径一致。
 */
object LrcParser {

    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val METADATA_TAG_REGEX = Regex("""^\[[a-zA-Z]+:[^\]]*\]$""")

    /**
     * [extractCredits] false keeps every timed line, for checks of a provider's
     * own line-for-line output (its translation file has no credits to take).
     */
    fun parse(raw: String, extractCredits: Boolean = true): Lyrics {
        val synced = mutableListOf<LyricLine>()

        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // 纯 metadata 行（`[ti:xxx]`）：整行匹配、没有时间戳形态 → 跳过
            if (METADATA_TAG_REGEX.matches(line) && !isTimestampTag(line)) continue

            val matches = TIMESTAMP_REGEX.findAll(line).toList()
            if (matches.isEmpty()) continue

            val lastEnd = matches.last().range.last + 1
            val text = line.substring(lastEnd).trim()
            if (text.isEmpty()) continue

            for (m in matches) {
                val startMs = timestampToMs(m) ?: continue
                synced += LyricLine(startMs = startMs, text = text)
            }
        }

        val parsed = if (synced.isNotEmpty()) {
            Lyrics.Synced(lines = synced.sortedBy { it.startMs })
        } else {
            Lyrics.Unsynced(text = raw.trim())
        }
        return if (extractCredits) LyricCreditsExtractor.extract(parsed) else parsed
    }

    private fun isTimestampTag(line: String): Boolean =
        TIMESTAMP_REGEX.containsMatchIn(line)

    private fun timestampToMs(m: MatchResult): Long? {
        val (mm, ss, frac) = m.destructured
        val minutes = mm.toLongOrNull() ?: return null
        val seconds = ss.toLongOrNull() ?: return null
        val fractionMs = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100L // tenths
            2 -> frac.toLong() * 10L // hundredths
            3 -> frac.toLong() // millis
            else -> frac.substring(0, 3).toLongOrNull() ?: 0L
        }
        return minutes * 60_000L + seconds * 1_000L + fractionMs
    }
}
