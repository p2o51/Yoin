package com.gpo.yoin.shared.data.lyrics

import com.gpo.yoin.shared.data.model.LyricLine
import com.gpo.yoin.shared.data.model.Lyrics

/**
 * LRC text → [Lyrics]. Port of the Android app's data/lyrics/LrcParser.kt:
 *
 * - `[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss]` (also `[mm:ss:xx]`) timestamps;
 *   one fraction digit = tenths, two = hundredths, three = millis.
 * - Several timestamps on one line expand to one [LyricLine] each.
 * - Metadata tags (`[ar:]`, `[ti:]`, `[al:]`, `[by:]`, `[length:]`, …) are dropped.
 * - Timestamp-only lines (empty text) are dropped.
 * - At least one timed line → synced, sorted by time; otherwise the trimmed
 *   text comes back unsynced, one line per text line.
 *
 * One addition over Android: `[offset:±N]` (milliseconds, positive = lyrics
 * sooner, the LRC convention) is applied to every timestamp, clamped at 0.
 * Android drops the tag without applying it.
 *
 * Swift: `LrcParser.shared.parse(raw:)`.
 */
object LrcParser {

    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val METADATA_TAG_REGEX = Regex("""^\[[a-zA-Z]+:[^\]]*\]$""")
    private val OFFSET_TAG_REGEX = Regex("""^\[offset:\s*([+-]?\d+)\s*\]$""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Lyrics {
        val synced = mutableListOf<LyricLine>()
        var offsetMs = 0L

        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            OFFSET_TAG_REGEX.matchEntire(line)?.let { m ->
                offsetMs = m.groupValues[1].removePrefix("+").toLongOrNull() ?: 0L
                continue
            }

            // A whole-line metadata tag (`[ti:xxx]`) that isn't a timestamp → skip.
            if (METADATA_TAG_REGEX.matches(line) && !TIMESTAMP_REGEX.containsMatchIn(line)) continue

            val matches = TIMESTAMP_REGEX.findAll(line).toList()
            if (matches.isEmpty()) continue

            val lastEnd = matches.last().range.last + 1
            val text = line.substring(lastEnd).trim()
            if (text.isEmpty()) continue

            for (m in matches) {
                val startMs = timestampToMs(m) ?: continue
                synced += LyricLine(timeMs = startMs, text = text)
            }
        }

        return if (synced.isNotEmpty()) {
            Lyrics(
                synced = true,
                lines = synced
                    .map { if (offsetMs == 0L) it else it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0L)) }
                    .sortedBy { it.timeMs },
            )
        } else {
            val text = raw.trim()
            Lyrics(
                synced = false,
                lines = if (text.isEmpty()) emptyList() else text.lines().map { LyricLine(timeMs = 0L, text = it) },
            )
        }
    }

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
