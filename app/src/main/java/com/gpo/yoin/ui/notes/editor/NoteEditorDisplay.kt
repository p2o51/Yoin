package com.gpo.yoin.ui.notes.editor

import com.gpo.yoin.shared.notes.InlineRange
import com.gpo.yoin.shared.notes.NoteBlock
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention

/**
 * How the editor shows the stored text: each mention link reads as one "@Title" chip, a list marker as
 * "•", a to-do's as "☐" / "☑"; Markdown delimiters stay (quietly) and their words are styled. The field
 * applies [replacements] as its output transformation (so the caret steps over a chip and Backspace takes
 * it whole) and [styles] as spans; [toDisplay] maps a stored offset to the shown one (the @ popup's anchor).
 */
data class NoteEditorDisplay(
    val replacements: List<Replacement>,
    val styles: List<StyledRange>,
) {
    /** Stored [start]..[end] shown as [shown]. */
    data class Replacement(val start: Int, val end: Int, val shown: String)

    /** A style over shown text [start]..[end]. */
    data class StyledRange(val kind: Kind, val start: Int, val end: Int, val mention: NoteMention? = null)

    enum class Kind { Chip, Bold, Italic, Heading, Marker, ListGlyph, DoneLine }

    /** A stored offset as a shown one (an offset inside a replacement goes to its end). */
    fun toDisplay(offset: Int): Int {
        var shift = 0
        for (r in replacements) {
            if (offset <= r.start) break
            if (offset < r.end) return r.start + shift + r.shown.length
            shift += r.shown.length - (r.end - r.start)
        }
        return offset + shift
    }

    companion object {
        const val BULLET = "• "
        const val TODO = "☐ "
        const val DONE = "☑ "

        fun of(text: String): NoteEditorDisplay {
            val replacements = ArrayList<Replacement>()
            // Styles collected in stored offsets first, mapped once the replacements are known.
            val stored = ArrayList<StyledRange>()
            NoteMarkup.layout(text).forEach { line ->
                when (val block = line.block) {
                    is NoteBlock.Bullet -> {
                        replacements += Replacement(line.start, line.contentStart, BULLET)
                        stored += StyledRange(Kind.ListGlyph, line.start, line.contentStart)
                    }
                    is NoteBlock.Checklist -> {
                        replacements += Replacement(line.start, line.contentStart, if (block.checked) DONE else TODO)
                        stored += StyledRange(Kind.ListGlyph, line.start, line.contentStart)
                        if (block.checked) stored += StyledRange(Kind.DoneLine, line.contentStart, line.end)
                    }
                    is NoteBlock.Heading -> {
                        stored += StyledRange(Kind.Marker, line.start, line.contentStart)
                        stored += StyledRange(Kind.Heading, line.contentStart, line.end)
                    }
                    is NoteBlock.Paragraph -> Unit
                }
                line.inlines.forEach { range ->
                    when (range.kind) {
                        InlineRange.Kind.Mention -> {
                            val mention = NoteMarkup.mentions(text.substring(range.start, range.end)).firstOrNull()
                            if (mention != null) {
                                replacements += Replacement(range.start, range.end, "@" + mention.title)
                                stored += StyledRange(Kind.Chip, range.start, range.end, mention)
                            }
                        }
                        InlineRange.Kind.Bold, InlineRange.Kind.Italic -> {
                            val kind = if (range.kind == InlineRange.Kind.Bold) Kind.Bold else Kind.Italic
                            stored += StyledRange(Kind.Marker, range.start, range.innerStart)
                            stored += StyledRange(kind, range.innerStart, range.innerEnd)
                            stored += StyledRange(Kind.Marker, range.innerEnd, range.end)
                        }
                    }
                }
            }
            replacements.sortBy { it.start }
            val shell = NoteEditorDisplay(replacements, emptyList())
            val styles = stored.mapNotNull { range ->
                val start = shell.toDisplayStart(range.start)
                val end = shell.toDisplay(range.end)
                if (end > start) range.copy(start = start, end = end) else null
            }
            return NoteEditorDisplay(replacements, styles)
        }
    }

    /** Like [toDisplay], but an offset at a replacement's start stays at its start. */
    private fun toDisplayStart(offset: Int): Int {
        var shift = 0
        for (r in replacements) {
            if (offset <= r.start) break
            if (offset < r.end) return r.start + shift
            shift += r.shown.length - (r.end - r.start)
        }
        return offset + shift
    }
}
