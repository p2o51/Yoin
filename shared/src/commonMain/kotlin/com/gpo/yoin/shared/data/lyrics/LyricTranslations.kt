package com.gpo.yoin.shared.data.lyrics

import com.gpo.yoin.shared.data.model.Lyrics

/**
 * Pairing of a lyrics source's own translation LRC with the original lines.
 * Port of Android's `buildProviderTranslations` (YoinRepository.kt) and the
 * `cleanLineTranslation` it calls (GeminiService.kt).
 */
internal object LyricTranslations {

    /**
     * 把歌词源的译文 LRC 对到原文行号上，按时间戳对齐。同一时间戳下常有多行并排
     * （华为、QQ 的文件开头：标题、"词："、"曲："，有时还有第一句歌词）：
     * - 两边行数相等 → 组内按位置一一配对；
     * - 行数不等 → 沿用旧口径，整组都用该时间戳下最后一句译文。
     * 译文是 [LyricPayload.UNTRANSLATED_LINE] 占位的行算没有翻译。
     * 一个时间戳都对不上时退回按序号配对。
     *
     * The key of the result is the index into [lyrics].lines (for unsynced lyrics: into the
     * non-empty trimmed lines).
     */
    fun align(lyrics: Lyrics, translatedLrc: String): Map<Int, String> {
        val translated = LrcParser.parse(translatedLrc)
        if (lyrics.synced && translated.synced) {
            // LrcParser sorts stably, so the order inside a group is the file order.
            val offeredByStart = translated.lines.groupBy { it.timeMs }
            val exact = mutableMapOf<Int, String>()
            lyrics.lines.withIndex().groupBy { it.value.timeMs }.forEach { (startMs, originals) ->
                val offered = offeredByStart[startMs] ?: return@forEach
                if (offered.size == originals.size) {
                    originals.zip(offered).forEach { (original, line) ->
                        line.text.toProviderLineTranslation()?.let { exact[original.index] = it }
                    }
                } else {
                    val shared = offered.lastOrNull { it.text.isNotBlank() }?.text?.toProviderLineTranslation()
                        ?: return@forEach
                    originals.forEach { original -> exact[original.index] = shared }
                }
            }
            if (exact.isNotEmpty()) return exact
        }
        return lineTexts(lyrics)
            .zip(lineTexts(translated))
            .mapIndexedNotNull { index, pair ->
                pair.second.toProviderLineTranslation()?.let { index to it }
            }
            .toMap()
    }

    private fun lineTexts(lyrics: Lyrics): List<String> =
        if (lyrics.synced) {
            lyrics.lines.map { it.text }
        } else {
            lyrics.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        }

    private fun String.toProviderLineTranslation(): String? =
        takeUnless { it.trim() == LyricPayload.UNTRANSLATED_LINE }
            ?.let(::cleanLineTranslation)
            ?.takeIf(String::isNotBlank)

    private val TAG_MARKER_REGEX = Regex("""\[\s*/?\s*[A-Z][A-Z0-9_]*\s*\]""", RegexOption.IGNORE_CASE)
    private val LEADING_LINE_MARKER_REGEX = Regex(
        """^(?:[-*•]\s*)?(?:(?:[\[【(]\s*\d+\s*[]】)])(?:[.)：:、])?|\d+[.)：:、])\s*""",
    )

    /** Strips `[TAG]` markers and leading `1.` / `(2)` / `- ` line markers a translator may have left. */
    fun cleanLineTranslation(text: String): String {
        var cleaned = text.replace(TAG_MARKER_REGEX, "").trim().trim()
        repeat(3) {
            cleaned = cleaned.replace(LEADING_LINE_MARKER_REGEX, "").trim()
        }
        return cleaned
    }
}
