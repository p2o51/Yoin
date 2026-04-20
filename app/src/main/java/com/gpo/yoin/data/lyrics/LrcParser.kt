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
 */
object LrcParser {

    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val METADATA_TAG_REGEX = Regex("""^\[[a-zA-Z]+:[^\]]*\]$""")

    fun parse(raw: String): Lyrics {
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

        return if (synced.isNotEmpty()) {
            Lyrics.Synced(lines = synced.sortedBy { it.startMs })
        } else {
            Lyrics.Unsynced(text = raw.trim())
        }
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
