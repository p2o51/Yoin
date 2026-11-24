package com.gpo.yoin.shared.notes

import com.gpo.yoin.shared.notes.YoinNoteReader.flat
import com.gpo.yoin.shared.notes.YoinNoteReader.toNoteMention

/*
 * [NoteEditorText] (the editor's text rules Android uses) for Swift, flat (iosApp/docs/shared-kmp.md): every
 * argument explicit, no IntRange, no sealed types, nothing throws. Every offset is a UTF-16 code unit index
 * into the stored text (what NSString / NSRange count), so Swift passes NSRange locations straight through.
 */

/** An inline token's kind in [YoinNoteLineLayout]. */
enum class YoinNoteInlineKind { Bold, Italic, Mention }

/** One inline token: its source range ([start]..[end]) and its words inside the delimiters. */
data class YoinNoteInlineRange(
    val kind: YoinNoteInlineKind,
    val start: Int,
    val end: Int,
    val innerStart: Int,
    val innerEnd: Int,
)

/**
 * One line as the editor styles it: [start]..[end] the line (no newline), [contentStart] after its block marker
 * (`- `, `# `, `- [ ] `); [level] 1–3 for a heading, [checked] for a done to-do.
 */
data class YoinNoteLineLayout(
    val kind: YoinNoteLineKind,
    val level: Int,
    val checked: Boolean,
    val start: Int,
    val end: Int,
    val contentStart: Int,
    val inlines: List<YoinNoteInlineRange>,
)

object YoinNoteEditor {
    /** The longest query the @ popup follows. */
    val maxQuery: Int get() = NoteEditorText.MAX_QUERY

    /**
     * The `@` the caret is typing after, or null. [composingStart]..[composingEnd] is the keyboard's marked
     * (still composing) text; pass -1, -1 when there is none.
     */
    fun trigger(text: String, caret: Int, composingStart: Int, composingEnd: Int): MentionTrigger? {
        val composing = if (composingStart in 0 until composingEnd) composingStart until composingEnd else null
        return NoteEditorText.trigger(text, caret, composing)
    }

    /** Return while the popup is open: [pick] takes the `@` token's place, the newline dropped; null otherwise. */
    fun enterPicksMention(original: String, edited: String, trigger: MentionTrigger, pick: YoinNoteMention): NoteEdit? {
        val mention = pick.toNoteMention() ?: return null
        return NoteEditorText.enterPicksMention(original, edited, trigger, mention)
    }

    /** [mention] in place of [trigger] (or at the selection when null), then a space; null for an invalid mention. */
    fun insertMention(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        trigger: MentionTrigger?,
        mention: YoinNoteMention,
    ): NoteEdit? {
        val valid = mention.toNoteMention() ?: return null
        return NoteEditorText.insertMention(text, selectionStart, selectionEnd, trigger, valid)
    }

    /** A keyboard edit corrected (mentions whole, lists continued, `[] ` / `＃` shortcuts), or null as typed. */
    fun reconcile(original: String, edited: String, editedSelectionStart: Int, editedSelectionEnd: Int): NoteEdit? =
        NoteEditorText.reconcile(original, edited, editedSelectionStart, editedSelectionEnd)

    /** The formatting row's [tool] on [text] with the selection. */
    fun apply(tool: NoteTool, text: String, selectionStart: Int, selectionEnd: Int): NoteEdit =
        NoteEditorText.apply(tool, text, selectionStart, selectionEnd)

    /** The tools that read as pressed at the selection (bold / italic around it, the caret line's block). */
    fun activeTools(text: String, selectionStart: Int, selectionEnd: Int): List<NoteTool> {
        val out = NoteEditorText.inlineAt(text, selectionStart, selectionEnd).toMutableList()
        NoteEditorText.blockAt(text, selectionStart)?.let(out::add)
        return NoteTool.entries.filter { it in out }
    }

    /** [text] with the to-do on line [lineIndex] ticked or unticked; unchanged when that line isn't one. */
    fun toggleChecklist(text: String, lineIndex: Int): String = NoteMarkup.toggleChecklist(text, lineIndex)

    /** Every mention link in [text], in order. */
    fun mentionSpans(text: String): List<YoinNoteMentionSpan> =
        NoteMarkup.mentionSpans(text).map { YoinNoteMentionSpan(it.start, it.end, flat(it.mention)) }

    /** Each line's block and inline tokens, absolute offsets (the same reading as [YoinNoteReader.read]). */
    fun layout(text: String): List<YoinNoteLineLayout> = NoteMarkup.layout(text).map { line ->
        val (kind, level, checked) = when (val block = line.block) {
            is NoteBlock.Paragraph -> Triple(YoinNoteLineKind.Paragraph, 0, false)
            is NoteBlock.Heading -> Triple(YoinNoteLineKind.Heading, block.level, false)
            is NoteBlock.Bullet -> Triple(YoinNoteLineKind.Bullet, 0, false)
            is NoteBlock.Checklist -> Triple(YoinNoteLineKind.Checklist, 0, block.checked)
        }
        YoinNoteLineLayout(
            kind = kind,
            level = level,
            checked = checked,
            start = line.start,
            end = line.end,
            contentStart = line.contentStart,
            inlines = line.inlines.map {
                val k = when (it.kind) {
                    InlineRange.Kind.Bold -> YoinNoteInlineKind.Bold
                    InlineRange.Kind.Italic -> YoinNoteInlineKind.Italic
                    InlineRange.Kind.Mention -> YoinNoteInlineKind.Mention
                }
                YoinNoteInlineRange(k, it.start, it.end, it.innerStart, it.innerEnd)
            },
        )
    }

    /**
     * A mention of an item (a picked popup row), with its canonical link; null when [provider] isn't a MediaId
     * provider token or [id] is empty. [artist] is dropped for an artist, [album] for anything but a track.
     */
    fun mention(kind: MentionKind, provider: String, id: String, title: String, artist: String?, album: String?): YoinNoteMention? {
        if (!NoteMention.isValidProvider(provider) || id.isEmpty()) return null
        return flat(
            NoteMention(
                kind = kind,
                provider = provider,
                id = id,
                title = title.ifBlank { id },
                artist = artist?.takeIf { kind != MentionKind.Artist && it.isNotBlank() },
                album = album?.takeIf { kind == MentionKind.Track && it.isNotBlank() },
            ),
        )
    }

    /** [mention] as stored text (`[@Title](yoin:…)`); null for an invalid mention. */
    fun serializeMention(mention: YoinNoteMention): String? = mention.toNoteMention()?.let(NoteMarkup::serializeMention)
}
