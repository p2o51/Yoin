package com.gpo.yoin.shared.albumabout

/*
 * Apple Music's editorial notes as the album's About paragraph (51, 2026-11-25): `standard`, else `short`,
 * as plain text. Moved from Android's data/model/PlainTextFromHtml.kt and AppleMusicSource.appleEditorialNotes.
 */
internal object EditorialNotes {

    /** Apple's `editorialNotes` as plain text: `standard`, else `short`; null when neither has words. */
    fun pick(standard: String?, short: String?): String? =
        listOf(standard, short).firstNotNullOfOrNull { it?.let(::plainTextFromHtml) }

    /**
     * A service's light HTML (Apple Music's editorial notes: `<i>`, `<b>`, `<br />`, `<p>`, entities) as plain
     * text: line and paragraph breaks kept as newlines (at most one blank line), every other tag dropped,
     * entities decoded, runs of spaces folded. Null when nothing readable is left.
     */
    fun plainTextFromHtml(html: String): String? {
        val text = html
            .replace(BreakTag, "\n")
            .replace(ParagraphTag, "\n\n")
            .replace(AnyTag, "")
            .let(::decodeEntities)
            .replace('\u00A0', ' ')
            .lines()
            .joinToString("\n") { it.replace(Spaces, " ").trim() }
            .replace(BlankLines, "\n\n")
            .trim()
        return text.takeIf(String::isNotEmpty)
    }

    private val BreakTag = Regex("<\\s*br\\s*/?\\s*>", RegexOption.IGNORE_CASE)
    private val ParagraphTag = Regex("<\\s*/?\\s*p(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val AnyTag = Regex("<[^>]*>")
    private val Spaces = Regex("[ \\t]+")
    private val BlankLines = Regex("\\n{3,}")
    private val NumericEntity = Regex("&#(x[0-9a-fA-F]+|\\d+);")
    private val NamedEntities = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&#39;" to "'",
        "&nbsp;" to " ", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…",
        "&lsquo;" to "‘", "&rsquo;" to "’", "&ldquo;" to "“", "&rdquo;" to "”",
    )

    private fun decodeEntities(text: String): String {
        var out = NumericEntity.replace(text) { match ->
            val value = match.groupValues[1]
            val code = if (value.startsWith("x")) value.drop(1).toIntOrNull(16) else value.toIntOrNull()
            code?.takeIf { it in 0..0x10FFFF }?.let(::codePointString) ?: match.value
        }
        // &amp; last, so "&amp;lt;" reads as the text "&lt;", not "<".
        NamedEntities.filterKeys { it != "&amp;" }.forEach { (entity, char) -> out = out.replace(entity, char) }
        return out.replace("&amp;", "&")
    }

    /** Java's `String(Character.toChars(code))`: one UTF-16 unit below U+10000 (a lone surrogate too), else a pair. */
    private fun codePointString(code: Int): String = if (code < 0x10000) {
        code.toChar().toString()
    } else {
        val offset = code - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
    }
}
