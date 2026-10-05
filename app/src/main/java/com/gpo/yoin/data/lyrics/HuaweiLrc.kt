package com.gpo.yoin.data.lyrics

/**
 * 华为音乐逐行歌词（LRC）的整理与拆分，纯函数。
 *
 * 带译文的文件把简体中文译文写在原文同一行、用 `^` 隔开：`[00:27.38]原文 ^译文`，
 * `^` 前后可能有空格，也可能没有。这里拆成两份同时间轴的 LRC：
 * - **原文**里不留任何 `^译文` 尾巴——它会落进 lyrics_cache、当 Gemini 的源行、
 *   给云同步数行数；
 * - **译文**里每一行原文都有对应的一行，没翻译的（标题、制作人员、语气词）写
 *   [LyricPayload.UNTRANSLATED_LINE] 占位，保证同一时间戳下两边行数相等，可以按位置配对。
 *
 * 另外处理：BOM、CRLF、`&#数字;` 实体（韩文常这样写）、纯音乐占位（当作没词）。
 */
internal object HuaweiLrc {

    /** `lineByLineLyric_subType` 里表示"行内带译文"的值。 */
    const val SUBTYPE_TRANSLATE = "translate"

    /**
     * [subTypes] 是搜索结果里 `lineByLineLyric_subType` 按 `|` 拆开的值：
     * 含 [SUBTYPE_TRANSLATE] → 拆；已知但不含 → 不拆（原文里的 `^` 就是正文）；
     * 未知（空集）→ 文件里有"`^` 后面跟着汉字"的行才拆。
     *
     * 返回 null：文件是空的，或者只是一行纯音乐占位。
     */
    fun split(raw: String, subTypes: Set<String>): LyricPayload? {
        val lines = raw.removePrefix(BOM).lines()
        if (lines.all(String::isBlank) || isInstrumentalPlaceholder(lines)) return null

        val inline = when {
            SUBTYPE_TRANSLATE in subTypes -> true
            subTypes.isEmpty() -> lines.any { line ->
                stamped(line)?.let { splitBody(it.body).translation?.any(::isHan) } == true
            }
            else -> false
        }
        if (!inline) return LyricPayload(lyric = decodeNumericEntities(lines.joinToString("\n")))

        val original = mutableListOf<String>()
        val translation = mutableListOf<String>()
        var translatedLines = 0
        for (line in lines) {
            val stamped = stamped(line)
            if (stamped == null) {
                // 标签行（`[ti:…]`）和没有时间轴的行只属于原文。
                original += line
                continue
            }
            val parts = splitBody(stamped.body)
            // 原文是空的：解析器会丢掉这一行，译文也不能多出一行，否则同组配不上。
            if (parts.original.isEmpty()) continue
            original += stamped.stamps + parts.original
            translation += stamped.stamps + (parts.translation ?: LyricPayload.UNTRANSLATED_LINE)
            if (parts.translation != null) translatedLines += 1
        }
        return LyricPayload(
            lyric = decodeNumericEntities(original.joinToString("\n")),
            translatedLyric = translation
                .takeIf { translatedLines > 0 }
                ?.joinToString("\n")
                ?.let(::decodeNumericEntities),
        )
    }

    /**
     * 只认最后一个 `^`，而且它右边要有字母或数字才算译文：原文里偶尔出现 `^_^` 这类
     * 正文，而中文译文里几乎不会有 `^`。行尾悬空的 `^`（译文为空）直接去掉。
     */
    private fun splitBody(body: String): Parts {
        val caret = body.lastIndexOf('^')
        if (caret < 0) return Parts(body.trim(), null)
        val left = body.substring(0, caret).trim()
        val right = body.substring(caret + 1).trim()
        return when {
            right.any(Char::isLetterOrDigit) -> Parts(left, right)
            right.isEmpty() && left.lastOrNull()?.isLetterOrDigit() == true -> Parts(left, null)
            else -> Parts(body.trim(), null)
        }
    }

    private fun stamped(line: String): Stamped? {
        val trimmed = line.trim()
        val stamps = LEADING_TIMESTAMPS.find(trimmed)?.value ?: return null
        return Stamped(stamps = stamps, body = trimmed.substring(stamps.length))
    }

    /** 纯音乐只有一行 `[00:00:00]此歌曲为没有填词的纯音乐`（时间戳还是坏的），没有可同步的内容。 */
    private fun isInstrumentalPlaceholder(lines: List<String>): Boolean {
        val texts = lines
            .map { line -> line.trim().replace(LEADING_TIMESTAMPS, "").trim() }
            .filter { it.isNotEmpty() && !ID_TAG.matches(it) }
        return texts.isNotEmpty() && texts.all { it.startsWith(INSTRUMENTAL_PLACEHOLDER) }
    }

    /** `&#45684;` / `&#x1F600;` → 字符。命名实体（`&amp;` 等）留给 [LyricProvider.normalizeLyric]。 */
    private fun decodeNumericEntities(text: String): String = NUMERIC_ENTITY.replace(text) { match ->
        val (decimal, hex) = match.destructured
        val codePoint = if (decimal.isNotEmpty()) decimal.toIntOrNull() else hex.toIntOrNull(16)
        if (codePoint != null && Character.isValidCodePoint(codePoint) && codePoint != 0) {
            String(Character.toChars(codePoint))
        } else {
            match.value
        }
    }

    private fun isHan(char: Char): Boolean =
        Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN

    private class Stamped(val stamps: String, val body: String)

    private class Parts(val original: String, val translation: String?)

    private const val BOM = "﻿"
    private const val INSTRUMENTAL_PLACEHOLDER = "此歌曲为没有填词的纯音乐"

    private val LEADING_TIMESTAMPS = Regex("""^(?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+""")
    private val ID_TAG = Regex("""^\[[A-Za-z]+:[^\]]*]$""")
    private val NUMERIC_ENTITY = Regex("""&#(?:(\d{1,7})|[xX]([0-9A-Fa-f]{1,6}));""")
}
