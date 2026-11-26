package com.gpo.yoin.shared.share

/*
 * Share as image (Android PR #335, owner 2026-11-25; prototypes design/hifi/src/components/share.js): one sheet,
 * three tabs — the lyrics, the song's About (AI / objective text, tertiary) and one of the listener's notes (their
 * own words, primary) — each with its own layouts, Card / Story, and its tones. These are the plain models the
 * rules ([YoinShareCards]) read and answer; every platform draws the images itself.
 */

/** The sheet's tabs, in order: 歌词 / 关于 / 笔记. */
enum class ShareTab {
    Lyrics,
    About,
    Note,
    ;

    /** Whose words the tab's images carry, which decides their colours. */
    val role: ShareRole
        get() = when (this) {
            Lyrics -> ShareRole.Lyrics
            About -> ShareRole.Ai
            Note -> ShareRole.Own
        }

    /** The key the tab's last layout is remembered under (per device, outside sync): the tab's name. */
    val storeKey: String get() = name
}

/** Every layout of every tab, in each tab's 版式 order; each tab's first is its default. */
enum class ShareLayout(val tab: ShareTab) {
    /** Cover + title, the lines, the mark. */
    LyricsClassic(ShareTab.Lyrics),

    /** The first line at display size, up to three more small under it. */
    LyricsPoster(ShareTab.Lyrics),

    /** The lines over a progress bar marking where they sit in the song. */
    LyricsPlayer(ShareTab.Lyrics),

    /** CJK lines set top to bottom, right to left (CJK picks only). */
    LyricsVertical(ShareTab.Lyrics),

    /** The credits as a receipt (dashed rules), then the paragraph. */
    AboutReceipt(ShareTab.About),

    /** The paragraph as the hero, the credits as chips. */
    AboutQuote(ShareTab.About),

    /** Liner notes: a large title, the credits in a grid, the paragraph. */
    AboutLiner(ShareTab.About),

    /** The note in large type, like the lyrics card. */
    NoteBig(ShareTab.Note),

    /** The note as a scrapbook clipping on dot paper, with a ticket stub. */
    NoteClipping(ShareTab.Note),

    /** The note hanging from its moment on a wavy progress line. */
    NoteTimeline(ShareTab.Note),
    ;

    /** In Story this layout is the whole poster, edge to edge; the others sit as a card on the tone's backdrop. */
    val storyIsPoster: Boolean
        get() = this == LyricsPoster || this == LyricsVertical || this == AboutLiner || this == NoteClipping

    /** The value remembered for its tab ([ShareTab.storeKey]): the layout's name. */
    val storeValue: String get() = name
}

/**
 * Whose words an image carries: the lyrics keep their own pairings; About is AI / objective text and opens
 * tertiary; a note is the listener's own and opens primary. The colour rule only decides the default: every tab
 * offers both colour families (owner 2026-11-26, 「放开，这个是给用户选择的」).
 */
enum class ShareRole {
    Lyrics,
    Ai,
    Own,
    ;

    /**
     * The tones the role offers, in swatch order: the lyrics' four (primary container, its inverse, tertiary,
     * tertiary container) for every role, and for About and Note Paper after them, inked in the role's colour.
     */
    fun tones(): List<ShareTone> = when (this) {
        Lyrics -> listOf(ShareTone.Soft, ShareTone.Deep, ShareTone.Vivid, ShareTone.Mist)
        Ai, Own -> listOf(ShareTone.Soft, ShareTone.Deep, ShareTone.Vivid, ShareTone.Mist, ShareTone.Paper)
    }

    /**
     * The tone an image opens on until the listener picks one: the role's colour rule — the lyrics and a note
     * primary container (Soft), About tertiary container (Mist).
     */
    val defaultTone: ShareTone
        get() = when (this) {
            Lyrics, Own -> ShareTone.Soft
            Ai -> ShareTone.Mist
        }
}

/** An image's colour pairing ([YoinShareCards.toneColors]); the same colours on every tab, Paper aside. */
enum class ShareTone {
    /** Primary container + its on-colour: the lyrics' and a note's default. */
    Soft,

    /** The soft pair inverted. */
    Deep,

    /** Tertiary + its on-colour. */
    Vivid,

    /** Tertiary container + its on-colour: About's default. */
    Mist,

    /** A white paper card with the role's colour as ink, in dark mode too — About and Note only. */
    Paper,
}

/** Card: the image alone, 360 wide, its height following the content. Story: 9:16, 360 × 640. */
enum class ShareFormat { Card, Story }

/** The song an image is about. [durationMs] 0 = unknown. */
data class ShareSong(
    val songId: String,
    val title: String,
    val artist: String,
    val coverArtUrl: String?,
    val albumName: String = "",
    val durationMs: Long = 0L,
)

/** One lyric line as shared: its start (null = untimed), its text, its translation. */
data class ShareLyricLine(
    val startMs: Long?,
    val text: String,
    val translation: String? = null,
)

/**
 * The lyric lines to share: the select-mode pick, or the current line ([YoinShareCards.currentLine]).
 * [passageStarts]: positions in [lines] that start a new passage (the pick skipped part of the song there).
 */
data class ShareLyrics(
    val lines: List<ShareLyricLine>,
    val passageStarts: List<Int> = emptyList(),
    val showTranslation: Boolean = false,
)

/**
 * One line on an About image ("作词" / "吴青峰"). [ai]: the AI wrote it, not the lyrics' credits or the server.
 * [fact]: a plain fact about the release (the year), not a credit — on its own it isn't worth an About image.
 */
data class ShareCredit(val label: String, val value: String, val ai: Boolean = false, val fact: Boolean = false)

/**
 * About as shared: the credits and ONE paragraph, nothing else — none of the server's file facts and none of the
 * listener's own About entries. [heading]: "关于" for the review, the question's title for an answer.
 */
data class ShareAbout(
    val credits: List<ShareCredit>,
    val heading: String,
    val paragraph: String?,
) {
    /** Whether anything on the image came from the AI: only then does it say "由 X 整理". */
    val aiContent: Boolean get() = paragraph != null || credits.any { it.ai }
}

/** One of the listener's notes (one note per image). [content] is its stored Markdown (mentions as links). */
data class ShareNote(
    val noteId: String,
    val content: String,
    val positionMs: Long?,
    val createdAt: Long,
)

/** Everything the sheet can share for one song, frozen when it opens. */
data class ShareCardContent(
    val song: ShareSong,
    val lyrics: ShareLyrics?,
    val about: ShareAbout?,
    val note: ShareNote?,
)

/** One stored About row (Android `song_about_entries`), as the About rules read it. */
data class ShareAboutEntry(
    /** "canonical", "ask" or "own" ([YoinShareCards.KIND_CANONICAL] …). */
    val kind: String,
    /** A canonical row's key ("lyricist", "review" …); an ask's question key. */
    val entryKey: String,
    val promptText: String?,
    val titleText: String?,
    val answerText: String,
)

/**
 * What the song's sources say for About's credits: the lyrics' credits for the four headline roles (names joined
 * as the platform shows them, null when absent) and the server's composer and year. Nothing else from the
 * server crosses over.
 */
data class ShareAboutSources(
    val lyricist: String?,
    val composer: String?,
    val arranger: String?,
    val producer: String?,
    val serverComposer: String?,
    val year: Int?,
)

/** About's labels in the platform's language ("作词" / "Lyricist" …); [review] heads the AI's review. */
data class ShareAboutLabels(
    val lyricist: String,
    val composer: String,
    val arranger: String,
    val producer: String,
    val creationTime: String,
    val creationLocation: String,
    val year: String,
    val review: String,
)

/** What a Poster draws: [lead] at display size, [rest] small, [translation] as one quiet line at the very bottom. */
data class SharePoster(
    val lead: ShareLyricLine,
    val rest: List<ShareLyricLine>,
    val translation: String?,
    /** The lead's size on a Card (sp); a Story poster draws it [YoinShareCards.posterStoryLeadBoostSp] larger. */
    val leadSizeSp: Float,
    /** The lead line's moment ("1:05"), shown after the artist; null when untimed. */
    val leadTime: String?,
)

/** Where the excerpt sits on the Player's progress bar: fractions of the song, and its times. */
data class ShareExcerpt(val start: Float, val end: Float, val startMs: Long, val endMs: Long) {
    /** Where its time chip centres (kept inside the bar by the platform's layout). */
    val centre: Float get() = (start + end) / 2f
}

/** How a 竖排 image sets its lines: each column's characters, top to bottom, and the type size. */
data class ShareVerticalPlan(val columns: List<String>, val fontSizeSp: Float, val charsPerColumn: Int)

/** Where an image colour comes from: one of the scheme's roles, or plain white (Paper's card). */
enum class ShareInkSource {
    Primary,
    OnPrimary,
    PrimaryContainer,
    OnPrimaryContainer,
    Tertiary,
    OnTertiary,
    TertiaryContainer,
    OnTertiaryContainer,
    White,
}

/**
 * One image colour: [source] as the platform's current colour scheme has it, or — when [tone] is set — that
 * colour re-toned to HCT tone [tone], hue and chroma kept ([YoinShareCards.retone]; Paper's ink and backdrop).
 */
data class ShareInk(val source: ShareInkSource, val tone: Int?)

/**
 * A tone's colours for one role: the card, its text, and the Story's backdrop. Secondary text is [content] at
 * [YoinShareCards.secondaryAlpha].
 */
data class ShareToneColors(val card: ShareInk, val content: ShareInk, val backdrop: ShareInk)

/** A stretch of an About value or paragraph that is (or isn't) set bold ([YoinShareCards.boldRuns]). */
data class ShareTextRun(val text: String, val bold: Boolean)

/** An exported PNG's size in pixels. */
data class ShareExportSize(val width: Int, val height: Int)
