package com.gpo.yoin.ui.notes.editor

import com.gpo.yoin.shared.notes.InlineRange
import com.gpo.yoin.shared.notes.NoteBlock
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention

/*
 * The note editor's text rules (notes revamp phase B), pure so they're unit-tested on their own. The editor
 * edits the stored text itself (light Markdown, mentions as `[@Title](yoin:…)` links); the field shows it
 * through [NoteEditorDisplay] (a mention reads as one "@Title" chip, list and to-do markers as glyphs) and
 * every edit passes [NoteEditorText.reconcile], which keeps a mention whole and runs the live shortcuts.
 */

/** A text and its selection ([selectionStart]..[selectionEnd], a caret when equal). */
data class NoteEdit(val text: String, val selectionStart: Int, val selectionEnd: Int = selectionStart)

/** An `@` being typed: the `@` at [start], the words after it up to the caret at [end]. */
data class MentionTrigger(val start: Int, val end: Int, val query: String)

/** What the formatting row can do. */
enum class NoteTool { Bold, Italic, Heading, Bullet, Checklist, Mention }

object NoteEditorText {
    /** The longest query the @ popup follows (as the prototype); past it the `@` is just a character. */
    const val MAX_QUERY = 24

    private val TRIGGER_CHARS = charArrayOf('@', '＠')

    /**
     * The `@` the caret is typing after, or null. The `@` must start a word (line start, or after anything but
     * an ASCII letter, digit, `_` or `.`: an e-mail address isn't a mention), the words after it hold no space
     * and no other `@`, and it isn't inside a mention link already. A space inside the keyboard's [composition]
     * (a pinyin keyboard spelling "niu yue" before it commits 纽约) doesn't end it; a committed one does.
     */
    fun trigger(text: String, caret: Int, composition: IntRange? = null): MentionTrigger? {
        if (caret < 1 || caret > text.length) return null
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        var i = caret - 1
        while (i >= lineStart && caret - i - 1 <= MAX_QUERY) {
            val c = text[i]
            if (c in TRIGGER_CHARS) {
                val before = text.getOrNull(i - 1)?.takeIf { i > lineStart }
                if (before != null && (before.isAsciiWordChar() || before == '.')) return null
                if (NoteMarkup.mentionSpans(text).any { i >= it.start && i < it.end }) return null
                return MentionTrigger(i, caret, text.substring(i + 1, caret))
            }
            val composing = composition != null && i in composition
            if ((c.isWhitespace() && !composing) || c == '\u200b' || c == '\n') return null
            i--
        }
        return null
    }

    /**
     * Enter on the soft keyboard while the @ popup is open: [pick] (the popup's top or highlighted row) takes the
     * `@` token's place and the newline is dropped. Keyboards deliver that Enter in different shapes, all taken
     * here: a bare "\n" at the token's end; the composing word committed (or auto-corrected, auto-completed)
     * together with the newline in one batch ("@Ni" → "@Nice\n"); the composing word replaced by the newline
     * ("@Ni" → "@\n"); a newline that lands next to another one already after the token. Any edit that inserts
     * a newline inside or at the end of the token, and changes nothing past it, counts. Null for any other edit,
     * which stands as typed.
     */
    fun enterPicksMention(original: String, edited: String, trigger: MentionTrigger, pick: NoteMention): NoteEdit? {
        if (trigger.end > original.length || trigger.start >= trigger.end) return null
        if (original.getOrNull(trigger.start)?.let { it in TRIGGER_CHARS } != true) return null
        // The changed span, its start held at the token's end at most (a newline typed before another newline
        // after the token reads as inserted there, not further on).
        var prefix = 0
        val maxPrefix = minOf(original.length, edited.length, trigger.end)
        while (prefix < maxPrefix && original[prefix] == edited[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(original.length, edited.length) - prefix &&
            original[original.length - 1 - suffix] == edited[edited.length - 1 - suffix]
        ) {
            suffix++
        }
        val removedEnd = original.length - suffix
        val inserted = edited.substring(prefix, edited.length - suffix)
        if ('\n' !in inserted) return null
        // Inside the token (after its `@`) up to its end, and nothing after the token touched.
        if (prefix <= trigger.start || prefix > trigger.end || removedEnd > trigger.end) return null
        return insertMention(original, trigger.end, trigger.end, trigger, pick)
    }

    /** [mention] as a link in place of [trigger] (or at the selection), followed by a space; caret after it. */
    fun insertMention(text: String, selectionStart: Int, selectionEnd: Int, trigger: MentionTrigger?, mention: NoteMention): NoteEdit {
        val link = NoteMarkup.serializeMention(mention)
        val (from, to) = if (trigger != null && trigger.end <= text.length && text.getOrNull(trigger.start)?.let { it in TRIGGER_CHARS } == true) {
            trigger.start to trigger.end
        } else {
            minOf(selectionStart, selectionEnd) to maxOf(selectionStart, selectionEnd)
        }
        val after = text.getOrNull(to)
        val spacer = if (after == ' ') "" else " "
        val out = text.substring(0, from) + link + spacer + text.substring(to)
        val caret = from + link.length + 1
        return NoteEdit(out, caret.coerceAtMost(out.length))
    }

    /**
     * The edit from ([original], its selection) to ([edited], its selection), corrected, or null when it
     * stands as typed:
     *  - a mention is atomic: an edit that removes part of one removes all of it; typing inside one lands
     *    after it;
     *  - Enter on a list item or to-do continues the list; on an empty one it leaves the list;
     *  - a space after `[] `, `[ ] `, `【】 ` or `[x] ` at a line start makes a to-do, after `＃` a heading
     *    (`- `, `* ` and `# ` already are Markdown).
     */
    fun reconcile(original: String, edited: String, editedSelectionStart: Int, editedSelectionEnd: Int): NoteEdit? {
        if (original == edited) return null
        var prefix = 0
        val maxPrefix = minOf(original.length, edited.length)
        while (prefix < maxPrefix && original[prefix] == edited[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(original.length, edited.length) - prefix &&
            original[original.length - 1 - suffix] == edited[edited.length - 1 - suffix]
        ) {
            suffix++
        }
        val removedEnd = original.length - suffix
        val inserted = edited.substring(prefix, edited.length - suffix)

        atomicMention(original, prefix, removedEnd, inserted)?.let { return it }
        if (removedEnd == prefix && inserted == "\n") continueList(original, prefix)?.let { return it }
        if (removedEnd == prefix && inserted == " ") blockShortcut(original, prefix)?.let { return it }
        return null
    }

    private fun atomicMention(original: String, from: Int, to: Int, inserted: String): NoteEdit? {
        val spans = NoteMarkup.mentionSpans(original)
        if (spans.isEmpty()) return null
        if (to > from) {
            var a = from
            var b = to
            var touched = false
            spans.forEach { span ->
                val overlaps = span.start < to && span.end > from
                val whole = from <= span.start && to >= span.end
                if (overlaps && !whole) {
                    a = minOf(a, span.start)
                    b = maxOf(b, span.end)
                    touched = true
                }
            }
            if (!touched) return null
            val out = original.substring(0, a) + inserted + original.substring(b)
            return NoteEdit(out, a + inserted.length)
        }
        val inside = spans.firstOrNull { from > it.start && from < it.end } ?: return null
        val out = original.substring(0, inside.end) + inserted + original.substring(inside.end)
        return NoteEdit(out, inside.end + inserted.length)
    }

    private fun continueList(original: String, at: Int): NoteEdit? {
        val lineStart = original.lastIndexOf('\n', at - 1) + 1
        val lineEnd = original.indexOf('\n', at).let { if (it < 0) original.length else it }
        val line = original.substring(lineStart, lineEnd)
        val marker = blockMarker(line) ?: return null
        if (marker.kind == BlockKind.Heading) return null
        val content = line.substring(marker.length)
        if (content.isBlank() && at == lineEnd) {
            // An empty item: Enter leaves the list (the marker goes, no new line).
            val out = original.substring(0, lineStart) + original.substring(lineStart + marker.length)
            return NoteEdit(out, lineStart)
        }
        val next = when (marker.kind) {
            BlockKind.Checklist -> "${line[0]} [ ] "
            else -> "${line[0]} "
        }
        val out = original.substring(0, at) + "\n" + next + original.substring(at)
        return NoteEdit(out, at + 1 + next.length)
    }

    private val SHORTCUTS = mapOf(
        "[]" to "- [ ] ",
        "[ ]" to "- [ ] ",
        "【】" to "- [ ] ",
        "[x]" to "- [x] ",
        "[X]" to "- [x] ",
        "＃" to "# ",
    )

    private fun blockShortcut(original: String, at: Int): NoteEdit? {
        val lineStart = original.lastIndexOf('\n', at - 1) + 1
        val typed = original.substring(lineStart, at)
        val replacement = SHORTCUTS[typed] ?: return null
        val out = original.substring(0, lineStart) + replacement + original.substring(at)
        return NoteEdit(out, lineStart + replacement.length)
    }

    /** The formatting row's action on [text] with the given selection. */
    fun apply(tool: NoteTool, text: String, selectionStart: Int, selectionEnd: Int): NoteEdit {
        val from = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val to = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        return when (tool) {
            NoteTool.Bold -> wrap(text, from, to, "**")
            NoteTool.Italic -> {
                val before = text.getOrNull(from - 1)
                val after = text.getOrNull(to)
                val marker = if (before?.isAsciiLetterOrDigit() == true || after?.isAsciiLetterOrDigit() == true) "*" else "_"
                wrap(text, from, to, marker)
            }
            NoteTool.Heading -> setBlock(text, from, to, BlockKind.Heading)
            NoteTool.Bullet -> setBlock(text, from, to, BlockKind.Bullet)
            NoteTool.Checklist -> setBlock(text, from, to, BlockKind.Checklist)
            NoteTool.Mention -> {
                val before = text.getOrNull(from - 1)
                val at = if (before != null && (before.isAsciiWordChar() || before == '.')) " @" else "@"
                NoteEdit(text.substring(0, from) + at + text.substring(to), from + at.length)
            }
        }
    }

    /** Which block the line at [caret] is (for the row's pressed state). */
    fun blockAt(text: String, caret: Int): NoteTool? {
        val at = caret.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        return when (blockMarker(text.substring(lineStart, lineEnd))?.kind) {
            BlockKind.Heading -> NoteTool.Heading
            BlockKind.Bullet -> NoteTool.Bullet
            BlockKind.Checklist -> NoteTool.Checklist
            null -> null
        }
    }

    /** Whether the caret / selection sits in bold or italic words (for the row's pressed state). */
    fun inlineAt(text: String, selectionStart: Int, selectionEnd: Int): Set<NoteTool> {
        val from = minOf(selectionStart, selectionEnd)
        val to = maxOf(selectionStart, selectionEnd)
        val out = HashSet<NoteTool>()
        NoteMarkup.layout(text).forEach { line ->
            line.inlines.forEach { range ->
                if (from >= range.innerStart && to <= range.innerEnd) {
                    when (range.kind) {
                        InlineRange.Kind.Bold -> out += NoteTool.Bold
                        InlineRange.Kind.Italic -> out += NoteTool.Italic
                        InlineRange.Kind.Mention -> Unit
                    }
                }
            }
        }
        return out
    }

    private fun wrap(text: String, from: Int, to: Int, marker: String): NoteEdit {
        val n = marker.length
        // Already wrapped exactly: unwrap.
        if (from >= n && to + n <= text.length && text.substring(from - n, from) == marker &&
            text.substring(to, to + n) == marker && (marker != "*" || text.getOrNull(from - n - 1) != '*')
        ) {
            val out = text.substring(0, from - n) + text.substring(from, to) + text.substring(to + n)
            return NoteEdit(out, from - n, to - n)
        }
        val out = text.substring(0, from) + marker + text.substring(from, to) + marker + text.substring(to)
        return NoteEdit(out, from + n, to + n)
    }

    private fun setBlock(text: String, from: Int, to: Int, kind: BlockKind): NoteEdit {
        val firstLine = text.lastIndexOf('\n', from - 1) + 1
        val lastLineEnd = text.indexOf('\n', to).let { if (it < 0) text.length else it }
        val lines = text.substring(firstLine, lastLineEnd).split('\n')
        val all = lines.all { blockMarker(it)?.kind == kind }
        var delta = 0
        var firstDelta = 0
        val rebuilt = lines.mapIndexed { index, line ->
            val marker = blockMarker(line)
            val content = marker?.let { line.substring(it.length) } ?: line
            val prefix = if (all) "" else kind.prefix
            val out = prefix + content
            val change = out.length - line.length
            if (index == 0) firstDelta = change
            delta += change
            out
        }.joinToString("\n")
        val out = text.substring(0, firstLine) + rebuilt + text.substring(lastLineEnd)
        val start = (from + firstDelta).coerceIn(firstLine, out.length)
        val end = (to + delta).coerceIn(start, out.length)
        return NoteEdit(out, start, end)
    }

    internal enum class BlockKind(val prefix: String) { Heading("# "), Bullet("- "), Checklist("- [ ] ") }

    internal data class BlockMarker(val kind: BlockKind, val length: Int)

    /** The line's block marker as [NoteMarkup] reads it, or null for a paragraph. */
    internal fun blockMarker(line: String): BlockMarker? = when (val block = NoteMarkup.parse(line).blocks.single()) {
        is NoteBlock.Paragraph -> null
        is NoteBlock.Heading -> BlockMarker(BlockKind.Heading, block.level + 1)
        is NoteBlock.Bullet -> BlockMarker(BlockKind.Bullet, 2)
        is NoteBlock.Checklist -> BlockMarker(BlockKind.Checklist, if (line.length > 5) 6 else 5)
    }

    private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun Char.isAsciiWordChar() = isAsciiLetterOrDigit() || this == '_'
}
