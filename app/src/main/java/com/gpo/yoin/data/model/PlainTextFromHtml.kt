package com.gpo.yoin.data.model

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
        .replace(' ', ' ')
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
        code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
    }
    // &amp; last, so "&amp;lt;" reads as the text "&lt;", not "<".
    NamedEntities.filterKeys { it != "&amp;" }.forEach { (entity, char) -> out = out.replace(entity, char) }
    return out.replace("&amp;", "&")
}
