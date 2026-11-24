package com.gpo.yoin.shared.notes

/*
 * A flat reading of [NoteMarkup] for Swift (iosApp/docs/shared-kmp.md). `:shared` is a plain static
 * XCFramework, so NoteMarkup's sealed hierarchies arrive as protocols and classes to cast, `Char` as a
 * UInt16 and default arguments not at all. This facade hands iOS the same parse as plain data: each line's
 * kind and its runs of words, a run bold / italic / a mention or not. Nothing here throws and nothing is
 * defaulted. Android keeps using NoteMarkup directly.
 */

/** What a line is. */
enum class YoinNoteLineKind { Paragraph, Heading, Bullet, Checklist }

/** One mention, flat. [link] is its canonical `yoin:` destination (what a tap on it carries). */
data class YoinNoteMention(
    val kind: MentionKind,
    val provider: String,
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val link: String,
)

/**
 * A stretch of one line's words that share their marks. A mention's run is its title; [bold] / [italic]
 * say whether it sits inside `**…**` / `_…_`.
 */
data class YoinNoteRun(
    val text: String,
    val bold: Boolean,
    val italic: Boolean,
    val mention: YoinNoteMention?,
)

/** One line: [level] 1–3 for a heading (else 0); [checked] for a done to-do. Runs empty = an empty line. */
data class YoinNoteLine(
    val kind: YoinNoteLineKind,
    val level: Int,
    val checked: Boolean,
    val runs: List<YoinNoteRun>,
)

object YoinNoteReader {
    /** [text]'s lines as [NoteMarkup.parse] reads them; anything that doesn't parse is a plain run. */
    fun read(text: String): List<YoinNoteLine> = NoteMarkup.parse(text).blocks.map { block ->
        val runs = ArrayList<YoinNoteRun>()
        flatten(block.inlines, bold = false, italic = false, out = runs)
        when (block) {
            is NoteBlock.Paragraph -> YoinNoteLine(YoinNoteLineKind.Paragraph, 0, false, runs)
            is NoteBlock.Heading -> YoinNoteLine(YoinNoteLineKind.Heading, block.level, false, runs)
            is NoteBlock.Bullet -> YoinNoteLine(YoinNoteLineKind.Bullet, 0, false, runs)
            is NoteBlock.Checklist -> YoinNoteLine(YoinNoteLineKind.Checklist, 0, block.checked, runs)
        }
    }

    /** Whether [text] has any mention link at all (the cheap test). */
    fun hasMentions(text: String): Boolean = NoteMarkup.hasMentions(text)

    /** Every mention in [text], in reading order. */
    fun mentions(text: String): List<YoinNoteMention> = NoteMarkup.mentions(text).map(::flat)

    /** The words alone (mentions as names, no marks), lines kept: accessibility, search, previews. */
    fun plainText(text: String): String = NoteMarkup.plainText(text, "\n")

    /** One line: the non-blank lines' words joined by " · " (a card's excerpt). */
    fun snippet(text: String): String = NoteMarkup.snippet(text)

    /** [MentionMatcher.namesMatchTrack]; false for a mention that isn't a valid one. */
    fun namesMatchTrack(mention: YoinNoteMention, title: String?, artist: String?, album: String?): Boolean =
        mention.toNoteMention()?.let { MentionMatcher.namesMatchTrack(it, title, artist, album) } ?: false

    /** [MentionMatcher.namesMatchAlbum]; false for a mention that isn't a valid one. */
    fun namesMatchAlbum(mention: YoinNoteMention, title: String?, artist: String?): Boolean =
        mention.toNoteMention()?.let { MentionMatcher.namesMatchAlbum(it, title, artist) } ?: false

    /** [MentionMatcher.searchPrefix]: a title's start for a cheap pre-filter; null when nothing is left. */
    fun searchPrefix(title: String): String? = MentionMatcher.searchPrefix(title)

    private fun flatten(nodes: List<NoteInline>, bold: Boolean, italic: Boolean, out: MutableList<YoinNoteRun>) {
        nodes.forEach { node ->
            when (node) {
                is NoteInline.Text -> out += YoinNoteRun(node.text, bold, italic, null)
                is NoteInline.Bold -> flatten(node.children, bold = true, italic = italic, out = out)
                is NoteInline.Italic -> flatten(node.children, bold = bold, italic = true, out = out)
                is NoteInline.Mention -> out += YoinNoteRun(node.mention.title, bold, italic, flat(node.mention))
            }
        }
    }

    private fun flat(mention: NoteMention): YoinNoteMention = YoinNoteMention(
        kind = mention.kind,
        provider = mention.provider,
        id = mention.id,
        title = mention.title,
        artist = mention.artist,
        album = mention.album,
        link = NoteMarkup.serializeDestination(mention),
    )

    /** Back to a [NoteMention] (whose init checks its fields), or null when Swift built an invalid one. */
    private fun YoinNoteMention.toNoteMention(): NoteMention? =
        if (NoteMention.isValidProvider(provider) && id.isNotEmpty()) {
            NoteMention(kind, provider, id, title, artist, album)
        } else {
            null
        }
}
