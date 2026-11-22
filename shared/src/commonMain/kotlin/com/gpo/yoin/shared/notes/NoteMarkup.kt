package com.gpo.yoin.shared.notes

/*
 * Yoin's note text (notes revamp, owner-approved prototype 2026-11-22): song notes, album notes and album
 * reviews stay plain text in Room, with light Markdown and mentions, so the text reads as text anywhere
 * (sync, backups, NeoDB, an older app) and renders where Yoin understands it.
 *
 *   # Heading            (also ## / ###)
 *   - item               (also "* " and "• ")
 *   - [ ] to-do / - [x] done
 *   **bold**, _italic_ (or *italic*)
 *   [@Title](yoin:<kind>/<provider>:<id>?a=<artist>&al=<album>)
 *
 * A mention's kind is track / album / artist ("song" is read as track), its provider is a MediaId provider
 * token and its id the raw id. Query values and the id are percent-encoded (UTF-8); `\`, `[` and `]` in the
 * title are backslash-escaped. Anything that doesn't parse stays text: a broken link, an unclosed `**`,
 * an unknown kind — nothing is ever dropped.
 *
 * Round trip: every token keeps the exact characters it was read from (Text is literal, delimiters and
 * block markers are recorded), so serialize(parse(s)) == s for any s whose mentions are in the canonical
 * form [serializeMention] writes. Two spellings are normalised on a re-serialise: a hand-written mention
 * (other encoding, other parameter order, "song") and an empty to-do written without its trailing space
 * ("- [ ]" comes back as "- [ ] "). Stored text is never rewritten by reading it: [toNeoDb] and
 * [rewriteMentions] edit mention spans in place and leave every other character as it was.
 *
 * No general backslash escapes outside mention titles: text that looks like Markdown is Markdown.
 */

/** What a mention points at. */
enum class MentionKind(val token: String) {
    Track("track"),
    Album("album"),
    Artist("artist"),
    ;

    companion object {
        fun fromToken(token: String): MentionKind? = when (token.lowercase()) {
            "track", "song" -> Track
            "album" -> Album
            "artist" -> Artist
            else -> null
        }
    }
}

/** A mention's identity, without its display words: what a transfer maps from one account to another. */
data class MentionRef(val kind: MentionKind, val provider: String, val id: String) {
    /** `provider:id`, MediaId's string form. */
    val mediaId: String get() = "$provider:$id"
}

/**
 * One mention: the item ([kind], [provider], [id]) and the words it was written with. [title] is the name
 * shown (and what NeoDB and plain text get); [artist] and [album] are the name-matching fallback when the id
 * means nothing to the account reading it (another service, another server).
 */
data class NoteMention(
    val kind: MentionKind,
    val provider: String,
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
) {
    init {
        require(isValidProvider(provider)) { "Invalid mention provider: $provider" }
        require(id.isNotEmpty()) { "Mention id must not be empty" }
    }

    val ref: MentionRef get() = MentionRef(kind, provider, id)

    /** `provider:id`, MediaId's string form. */
    val mediaId: String get() = "$provider:$id"

    companion object {
        /** A MediaId provider token: letters, digits, `.`, `_`, `-` (never `:`, `/` or anything a URI would escape). */
        fun isValidProvider(provider: String): Boolean =
            provider.isNotEmpty() && provider.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }
}

/** Inline content of one line. */
sealed interface NoteInline {
    /** Literal characters (never empty in a parsed document). */
    data class Text(val text: String) : NoteInline

    /** `**…**`. */
    data class Bold(val children: List<NoteInline>) : NoteInline

    /** `_…_` or `*…*` ([marker] says which; the serializer falls back to `*` only where `_` would not re-read). */
    data class Italic(val children: List<NoteInline>, val marker: Char = '_') : NoteInline

    data class Mention(val mention: NoteMention) : NoteInline
}

/** One line. Lines are separated by `\n`; an empty line is an empty [Paragraph]. */
sealed interface NoteBlock {
    val inlines: List<NoteInline>

    data class Paragraph(override val inlines: List<NoteInline>) : NoteBlock

    /** `# `, `## ` or `### ` ([level] 1–3). */
    data class Heading(val level: Int, override val inlines: List<NoteInline>) : NoteBlock

    /** `- `, `* ` or `• ` ([marker]). */
    data class Bullet(override val inlines: List<NoteInline>, val marker: Char = '-') : NoteBlock

    /** `- [ ] ` / `- [x] ` ([upperX]: written `[X]`). */
    data class Checklist(
        val checked: Boolean,
        override val inlines: List<NoteInline>,
        val marker: Char = '-',
        val upperX: Boolean = false,
    ) : NoteBlock
}

data class NoteDocument(val blocks: List<NoteBlock>) {
    /** Every mention, in reading order. */
    val mentions: List<NoteMention> get() = blocks.flatMap { block -> block.inlines.mentions() }

    companion object {
        val Empty = NoteDocument(listOf(NoteBlock.Paragraph(emptyList())))
    }
}

/** Where one mention sits in a text: `text.substring(start, end)` is its link. */
data class MentionSpan(val start: Int, val end: Int, val mention: NoteMention)

object NoteMarkup {
    /** The cheap test before parsing (and the SQL LIKE pattern's needle): text without it has no mention. */
    const val MENTION_NEEDLE = "](yoin:"

    fun parse(text: String): NoteDocument = NoteDocument(text.split('\n').map(::parseLine))

    fun serialize(document: NoteDocument): String = document.blocks.joinToString("\n", transform = ::serializeBlock)

    fun serialize(inlines: List<NoteInline>): String = buildString { appendInlines(inlines, this) }

    /** The canonical link for [mention]. */
    fun serializeMention(mention: NoteMention): String = buildString {
        append("[@")
        append(escapeTitle(mention.title))
        append("](yoin:")
        append(mention.kind.token)
        append('/')
        append(mention.provider)
        append(':')
        append(PercentCoding.encode(mention.id, keep = ID_SAFE))
        var separator = '?'
        mention.artist?.takeIf(String::isNotBlank)?.let {
            append(separator).append("a=").append(PercentCoding.encode(it))
            separator = '&'
        }
        mention.album?.takeIf(String::isNotBlank)?.let {
            append(separator).append("al=").append(PercentCoding.encode(it))
        }
        append(')')
    }

    fun hasMentions(text: String?): Boolean = text != null && text.contains(MENTION_NEEDLE, ignoreCase = true)

    /** Every mention in [text], in order. */
    fun mentions(text: String?): List<NoteMention> = mentionSpans(text).map(MentionSpan::mention)

    /** Every mention link in [text] and where it sits. Finds exactly the mentions [parse] finds. */
    fun mentionSpans(text: String?): List<MentionSpan> {
        if (text == null || !hasMentions(text)) return emptyList()
        val out = ArrayList<MentionSpan>()
        var i = 0
        var lineEnd = -1
        while (i < text.length - 1) {
            if (i > lineEnd) lineEnd = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            if (text[i] == '[' && text[i + 1] == '@') {
                val match = MentionSyntax.match(text, i, lineEnd)
                if (match != null) {
                    out += MentionSpan(i, match.end, match.mention)
                    i = match.end
                    continue
                }
            }
            i++
        }
        return out
    }

    /**
     * [text] with each mention replaced by what [transform] returns for it (serialized canonically); null
     * keeps the link exactly as written. Every other character is left alone. For the Memories transfer:
     * point mentions at the other account's ids.
     */
    fun rewriteMentions(text: String, transform: (NoteMention) -> NoteMention?): String =
        replaceMentions(text) { span ->
            val replacement = transform(span.mention)
            if (replacement == null || replacement == span.mention) null else serializeMention(replacement)
        }

    /** [rewriteMentions] by identity: mentions whose ref is in [mapping] point at the mapped item, words kept. */
    fun rewriteMentions(text: String, mapping: Map<MentionRef, MentionRef>): String {
        if (mapping.isEmpty()) return text
        return rewriteMentions(text) { mention ->
            mapping[mention.ref]?.let { to -> mention.copy(kind = to.kind, provider = to.provider, id = to.id) }
        }
    }

    /**
     * What NeoDB gets (its review bodies are Markdown): the text as written, each mention flattened to its
     * name. Headings, lists, to-dos, bold and italic pass through as Markdown; no `yoin:` link survives.
     */
    fun toNeoDb(text: String): String = replaceMentions(text) { span -> span.mention.title }

    /**
     * The words alone, for search, previews, accessibility and notifications: mentions as their names,
     * no Markdown marks, line structure kept (lines joined by [lineSeparator]).
     */
    fun plainText(text: String, lineSeparator: String = "\n"): String =
        if (!mayHaveMarkup(text)) {
            if (lineSeparator == "\n") text else text.replace("\n", lineSeparator)
        } else {
            plainText(parse(text), lineSeparator)
        }

    fun plainText(document: NoteDocument, lineSeparator: String = "\n"): String =
        document.blocks.joinToString(lineSeparator) { block -> plainInlines(block.inlines) }

    /** One line for a clipping: the non-blank lines' words joined by " · ". */
    fun snippet(text: String): String = plainText(parse(text), "\n")
        .split('\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" · ")

    fun plainInlines(inlines: List<NoteInline>): String = buildString {
        fun walk(nodes: List<NoteInline>) {
            nodes.forEach { node ->
                when (node) {
                    is NoteInline.Text -> append(node.text)
                    is NoteInline.Bold -> walk(node.children)
                    is NoteInline.Italic -> walk(node.children)
                    is NoteInline.Mention -> append(node.mention.title)
                }
            }
        }
        walk(inlines)
    }

    // ── internals ─────────────────────────────────────────────────────

    private const val ID_SAFE = ":"
    private val BULLET_MARKERS = charArrayOf('-', '*', '•')

    private const val MARKUP_CHARS = "[*_#-•"

    private fun mayHaveMarkup(text: String): Boolean = text.any { it in MARKUP_CHARS }

    private fun replaceMentions(text: String, replacement: (MentionSpan) -> String?): String {
        val spans = mentionSpans(text)
        if (spans.isEmpty()) return text
        val out = StringBuilder(text.length)
        var last = 0
        spans.forEach { span ->
            val with = replacement(span) ?: return@forEach
            out.append(text, last, span.start).append(with)
            last = span.end
        }
        if (last == 0) return text
        out.append(text, last, text.length)
        return out.toString()
    }

    private fun parseLine(line: String): NoteBlock {
        val n = line.length
        if (n >= 5 && line[0] in BULLET_MARKERS && line[1] == ' ' && line[2] == '[' && line[4] == ']' &&
            line[3] in " xX" && (n == 5 || line[5] == ' ')
        ) {
            return NoteBlock.Checklist(
                checked = line[3] != ' ',
                inlines = parseInlines(if (n > 6) line.substring(6) else ""),
                marker = line[0],
                upperX = line[3] == 'X',
            )
        }
        if (n >= 2 && line[0] in BULLET_MARKERS && line[1] == ' ') {
            return NoteBlock.Bullet(parseInlines(line.substring(2)), marker = line[0])
        }
        if (n >= 2 && line[0] == '#') {
            var level = 0
            while (level < n && line[level] == '#') level++
            if (level <= 3 && level < n && line[level] == ' ') {
                return NoteBlock.Heading(level, parseInlines(line.substring(level + 1)))
            }
        }
        return NoteBlock.Paragraph(parseInlines(line))
    }

    private fun parseInlines(content: String): List<NoteInline> =
        if (content.isEmpty()) emptyList() else InlineParser(content).parse(0, content.length)

    private fun serializeBlock(block: NoteBlock): String = buildString {
        when (block) {
            is NoteBlock.Paragraph -> Unit
            is NoteBlock.Heading -> {
                repeat(block.level.coerceIn(1, 3)) { append('#') }
                append(' ')
            }
            is NoteBlock.Bullet -> append(block.marker.takeIf { it in BULLET_MARKERS } ?: '-').append(' ')
            is NoteBlock.Checklist -> {
                append(block.marker.takeIf { it in BULLET_MARKERS } ?: '-')
                append(" [")
                append(if (!block.checked) ' ' else if (block.upperX) 'X' else 'x')
                append("] ")
            }
        }
        appendInlines(block.inlines, this)
    }

    private fun appendInlines(inlines: List<NoteInline>, out: StringBuilder) {
        inlines.forEach { node ->
            when (node) {
                is NoteInline.Text -> out.append(node.text)
                is NoteInline.Bold -> {
                    out.append("**")
                    appendInlines(node.children, out)
                    out.append("**")
                }
                is NoteInline.Italic -> {
                    // `_` inside a word doesn't read as italic; `*` is the spelling that always does.
                    val before = out.lastOrNull()
                    val marker = if (node.marker == '_' && before?.isLetterOrDigit() != true) '_' else '*'
                    out.append(marker)
                    appendInlines(node.children, out)
                    out.append(marker)
                }
                is NoteInline.Mention -> out.append(serializeMention(node.mention))
            }
        }
    }

    /**
     * `[` and `]` always take a backslash; a backslash only where it would otherwise read as one (before
     * `\\`, `[`, `]`, or last), so a hand-written `a\\b` stays exactly as it is.
     */
    private fun escapeTitle(title: String): String = buildString(title.length) {
        title.forEachIndexed { i, c ->
            when (c) {
                '[', ']' -> append('\\').append(c)
                '\\' -> {
                    val next = title.getOrNull(i + 1)
                    if (next == null || next.isTitleEscapable()) append('\\')
                    append(c)
                }
                '\n', '\r' -> append(' ')
                else -> append(c)
            }
        }
    }
}

/** What a backslash escapes in a mention title. */
private fun Char.isTitleEscapable(): Boolean = this == '\\' || this == '[' || this == ']'

private fun List<NoteInline>.mentions(): List<NoteMention> = flatMap { node ->
    when (node) {
        is NoteInline.Mention -> listOf(node.mention)
        is NoteInline.Bold -> node.children.mentions()
        is NoteInline.Italic -> node.children.mentions()
        is NoteInline.Text -> emptyList()
    }
}

/** Inline parsing of one line's content; every token covers exactly the characters it was read from. */
private class InlineParser(private val s: String) {
    fun parse(start: Int, end: Int): List<NoteInline> {
        val out = ArrayList<NoteInline>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                out += NoteInline.Text(text.toString())
                text.clear()
            }
        }
        var i = start
        while (i < end) {
            val c = s[i]
            if (c == '[') {
                val match = MentionSyntax.match(s, i, end)
                if (match != null) {
                    flush()
                    out += NoteInline.Mention(match.mention)
                    i = match.end
                    continue
                }
            }
            if (c == '*' && i + 1 < end && s[i + 1] == '*') {
                val close = findBoldClose(i + 2, end)
                if (close > i + 2) {
                    flush()
                    out += NoteInline.Bold(parse(i + 2, close))
                    i = close + 2
                    continue
                }
            }
            if ((c == '_' || c == '*') && canOpenItalic(i, end, c)) {
                val close = findItalicClose(i + 1, end, c)
                if (close > i + 1) {
                    flush()
                    out += NoteInline.Italic(parse(i + 1, close), marker = c)
                    i = close + 1
                    continue
                }
            }
            text.append(c)
            i++
        }
        flush()
        return out
    }

    private fun findBoldClose(from: Int, end: Int): Int {
        var j = from
        while (j < end - 1) {
            if (s[j] == '[') {
                val match = MentionSyntax.match(s, j, end)
                if (match != null) {
                    j = match.end
                    continue
                }
            }
            if (s[j] == '*' && s[j + 1] == '*') return j
            j++
        }
        return -1
    }

    private fun canOpenItalic(i: Int, end: Int, marker: Char): Boolean {
        if (i + 1 >= end) return false
        val next = s[i + 1]
        if (next.isWhitespace() || next == marker) return false
        val prev = if (i > 0) s[i - 1] else null
        return prev == null || (!prev.isLetterOrDigit() && prev != marker)
    }

    /** The first [marker] after the opener (mentions skipped) if it can close, else -1: italic holds no [marker]. */
    private fun findItalicClose(from: Int, end: Int, marker: Char): Int {
        var j = from
        while (j < end) {
            val c = s[j]
            if (c == '[') {
                val match = MentionSyntax.match(s, j, end)
                if (match != null) {
                    j = match.end
                    continue
                }
            }
            if (c == marker) {
                if (s[j - 1].isWhitespace()) return -1
                val next = if (j + 1 < s.length) s[j + 1] else null
                return if (next == null || (!next.isLetterOrDigit() && next != marker)) j else -1
            }
            j++
        }
        return -1
    }
}

/** The `[@Title](yoin:…)` link. */
internal object MentionSyntax {
    class Match(val mention: NoteMention, val end: Int)

    private const val SCHEME = "yoin:"

    /** The mention starting at [start] (a `[`) and ending at or before [end], or null when it isn't one. */
    fun match(s: String, start: Int, end: Int): Match? {
        if (start + 1 >= end || s[start] != '[' || s[start + 1] != '@') return null
        val title = StringBuilder()
        var j = start + 2
        while (j < end) {
            val c = s[j]
            if (c == '\n') return null
            if (c == '\\' && j + 1 < end && s[j + 1].isTitleEscapable()) {
                title.append(s[j + 1])
                j += 2
                continue
            }
            if (c == ']') break
            // An unescaped `[` means this isn't one (a mention may start right after it).
            if (c == '[') return null
            title.append(c)
            j++
        }
        if (j >= end || title.isBlank()) return null
        val open = j + 1
        if (open >= end || s[open] != '(' || !s.regionMatches(open + 1, SCHEME, 0, SCHEME.length, ignoreCase = true)) {
            return null
        }
        val destStart = open + 1 + SCHEME.length
        var close = destStart
        while (close < end && s[close] != ')' && s[close] != '\n') close++
        if (close >= end || s[close] != ')') return null
        val mention = parseDestination(s.substring(destStart, close), title.toString()) ?: return null
        return Match(mention, close + 1)
    }

    private fun parseDestination(dest: String, title: String): NoteMention? {
        val queryAt = dest.indexOf('?')
        val path = if (queryAt < 0) dest else dest.substring(0, queryAt)
        val slash = path.indexOf('/')
        if (slash <= 0) return null
        val kind = MentionKind.fromToken(path.substring(0, slash)) ?: return null
        val rest = path.substring(slash + 1)
        val colon = rest.indexOf(':')
        if (colon <= 0) return null
        val provider = rest.substring(0, colon)
        if (!NoteMention.isValidProvider(provider)) return null
        val id = PercentCoding.decode(rest.substring(colon + 1).trim())
        if (id.isEmpty()) return null
        var artist: String? = null
        var album: String? = null
        if (queryAt >= 0) {
            dest.substring(queryAt + 1).split('&').forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@forEach
                val value = PercentCoding.decode(pair.substring(eq + 1)).takeIf(String::isNotBlank)
                when (pair.substring(0, eq)) {
                    "a" -> artist = value
                    "al" -> album = value
                }
            }
        }
        return NoteMention(kind = kind, provider = provider, id = id, title = title, artist = artist, album = album)
    }
}

/** RFC 3986 percent-encoding over UTF-8, in common code. */
internal object PercentCoding {
    private const val HEX = "0123456789ABCDEF"

    private fun isUnreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'

    /** Everything but unreserved characters (and those in [keep]) as %XX. */
    fun encode(value: String, keep: String = ""): String {
        if (value.all { isUnreserved(it) || it in keep }) return value
        val out = StringBuilder(value.length + 8)
        value.encodeToByteArray().forEach { byte ->
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (b < 0x80 && (isUnreserved(c) || c in keep)) {
                out.append(c)
            } else {
                out.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
        return out.toString()
    }

    /** %XX sequences back to UTF-8 text; a `%` that isn't one stays as it is. `+` is a plus. */
    fun decode(value: String): String {
        if ('%' !in value) return value
        val out = StringBuilder(value.length)
        val pending = ArrayList<Byte>()
        fun flush() {
            if (pending.isNotEmpty()) {
                out.append(pending.toByteArray().decodeToString())
                pending.clear()
            }
        }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hi = value[i + 1].digitToIntOrNull(16)
                val lo = value[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    pending += ((hi shl 4) or lo).toByte()
                    i += 3
                    continue
                }
            }
            flush()
            out.append(c)
            i++
        }
        flush()
        return out.toString()
    }
}
