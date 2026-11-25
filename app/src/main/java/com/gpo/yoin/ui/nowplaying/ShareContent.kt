package com.gpo.yoin.ui.nowplaying

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.model.LyricCredits
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.ui.component.currentAnchoredNoteId
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/*
 * Share as image (owner 2026-11-25, prototypes design/hifi/src/components/share.js): one sheet, three tabs
 * — the lyrics, the song's About (AI / objective text, tertiary) and one of the listener's notes (their own
 * words, primary) — each with its own layouts. This file is the model: what each tab shares, which layouts
 * it offers, and the pure rules behind them; ShareCards.kt draws the images, ShareSheet.kt the sheet.
 */

/** The sheet's tabs, in order: 歌词 / 关于 / 笔记. */
internal enum class ShareTab(@StringRes val label: Int) {
    Lyrics(R.string.np_tab_lyrics),
    About(R.string.np_tab_about),
    Note(R.string.np_tab_note),
    ;

    val role: ShareRole
        get() = when (this) {
            Lyrics -> ShareRole.Lyrics
            About -> ShareRole.Ai
            Note -> ShareRole.Own
        }
}

/** Every layout of every tab, in each tab's 版式 order. A's are the defaults. */
internal enum class ShareLayout(val tab: ShareTab, @StringRes val label: Int) {
    /** Today's card: cover + title, the lines, the mark. */
    LyricsClassic(ShareTab.Lyrics, R.string.np_share_layout_classic),

    /** The first line at display size, up to three more small under it. */
    LyricsPoster(ShareTab.Lyrics, R.string.np_share_layout_poster),

    /** The lines over a progress bar marking where they sit in the song. */
    LyricsPlayer(ShareTab.Lyrics, R.string.np_share_layout_player),

    /** CJK lines set top to bottom, right to left (CJK picks only). */
    LyricsVertical(ShareTab.Lyrics, R.string.np_share_layout_vertical),

    /** The credits as a receipt (dashed rules), then the paragraph. */
    AboutReceipt(ShareTab.About, R.string.np_share_layout_receipt),

    /** The paragraph as the hero, the credits as chips. */
    AboutQuote(ShareTab.About, R.string.np_share_layout_quote),

    /** Liner notes: a large title, the credits in a grid, the paragraph. */
    AboutLiner(ShareTab.About, R.string.np_share_layout_liner),

    /** The note in large type, like the lyrics card. */
    NoteBig(ShareTab.Note, R.string.np_share_layout_big),

    /** The note as a scrapbook clipping on dot paper, with a ticket stub. */
    NoteClipping(ShareTab.Note, R.string.np_share_layout_clipping),

    /** The note hanging from its moment on a wavy progress line. */
    NoteTimeline(ShareTab.Note, R.string.np_share_layout_timeline),
    ;

    /** In Story this layout is the whole poster, edge to edge; the others sit as a card on a backdrop. */
    val storyIsPoster: Boolean
        get() = this == LyricsPoster || this == LyricsVertical || this == AboutLiner || this == NoteClipping
}

/** The song an image is about. [durationMs] 0 = unknown. */
@Immutable
internal data class ShareSong(
    val songId: String,
    val title: String,
    val artist: String,
    val coverArtUrl: String?,
    val albumName: String = "",
    val durationMs: Long = 0L,
)

/** The lyric lines to share: the select-mode pick, or the current line. */
@Immutable
internal data class LyricsSharePick(
    val lines: List<LyricLine>,
    /** Positions in [lines] that start a new passage (the pick skipped part of the song there). */
    val passageStarts: Set<Int> = emptySet(),
    val showTranslation: Boolean = false,
)

/** One credit on an About image ("作词" / "吴青峰"). */
@Immutable
internal data class ShareCredit(val label: String, val value: String)

/**
 * About as shared (owner 2026-11-25): the credits and ONE paragraph, nothing else — none of the server's
 * file facts (path, bitrate, plays, date added) and none of the listener's own About entries (theirs is
 * primary, not AI).
 */
@Immutable
internal data class AboutSharePick(
    val credits: List<ShareCredit>,
    /** The paragraph's heading: "关于" for the review, the question's title for an answer. */
    val heading: String,
    val paragraph: String?,
)

/** One of the listener's notes (one note per image). */
@Immutable
internal data class NoteSharePick(
    val noteId: String,
    /** The note's stored Markdown (mentions as links). */
    val content: String,
    val positionMs: Long?,
    val createdAt: Long,
)

/** Everything the sheet can share for one song, frozen when it opens. */
@Immutable
internal data class ShareContent(
    val song: ShareSong,
    val lyrics: LyricsSharePick? = null,
    val about: AboutSharePick? = null,
    val note: NoteSharePick? = null,
    /** The AI service chosen in Settings: About's "由 X 整理". */
    val aiProvider: AiProvider = AiProvider.Gemini,
) {
    fun has(tab: ShareTab): Boolean = when (tab) {
        ShareTab.Lyrics -> lyrics != null && lyrics.lines.isNotEmpty()
        ShareTab.About -> about != null && (about.credits.isNotEmpty() || about.paragraph != null)
        ShareTab.Note -> note != null && note.content.isNotBlank()
    }

    /** The tabs this song has something for, in order. */
    val tabs: List<ShareTab> get() = ShareTab.entries.filter(::has)
}

/** The layouts [tab] offers for this content, in 版式 order (empty when the tab has nothing). */
internal fun ShareContent.layoutsFor(tab: ShareTab): List<ShareLayout> {
    if (!has(tab)) return emptyList()
    return ShareLayout.entries.filter { it.tab == tab }.filter { layout ->
        when (layout) {
            // 竖排 only when every line is CJK: Latin set on its side reads as a mistake.
            ShareLayout.LyricsVertical -> lyrics!!.lines.all { isCjkLyric(it.text) }
            // The quote needs a paragraph to quote.
            ShareLayout.AboutQuote -> about!!.paragraph != null
            // A timeline needs the note's moment and the song's length.
            ShareLayout.NoteTimeline -> note!!.positionMs != null && song.durationMs > 0L
            else -> true
        }
    }
}

/** [remembered] when this content offers it, else the tab's first layout. */
internal fun ShareContent.resolveLayout(tab: ShareTab, remembered: ShareLayout?): ShareLayout? {
    val offered = layoutsFor(tab)
    return remembered?.takeIf { it in offered } ?: offered.firstOrNull()
}

// ---------------------------------------------------------------------------
// Lyrics rules.
// ---------------------------------------------------------------------------

/**
 * Whether [text] is CJK all through: at least one Han / kana / Hangul / Bopomofo letter and no letter of
 * any other script (digits and punctuation don't count either way).
 */
internal fun isCjkLyric(text: String): Boolean {
    var cjk = false
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        if (!Character.isLetter(cp)) continue
        when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.BOPOMOFO,
            -> cjk = true
            else -> return false
        }
    }
    return cjk
}

/** The most lines a Poster shows: the first big, up to three more small (owner 2026-11-25). */
internal const val PosterMaxLines = 4

/** What a Poster draws: [lead] at display size, [rest] small, [translation] as one quiet line at the very bottom. */
@Immutable
internal data class PosterLines(val lead: LyricLine, val rest: List<LyricLine>, val translation: String?)

internal fun posterLines(pick: LyricsSharePick): PosterLines? {
    val shown = pick.lines.filter { it.text.isNotBlank() }.take(PosterMaxLines)
    val lead = shown.firstOrNull() ?: return null
    val translation = if (pick.showTranslation) {
        shown.mapNotNull { it.translation?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(PosterTranslationSeparator)
            .takeIf { it.isNotEmpty() }
    } else {
        null
    }
    return PosterLines(lead = lead, rest = shown.drop(1), translation = translation)
}

private const val PosterTranslationSeparator = "  /  "

/** The lead line's size on a Poster: the shorter the line, the larger (owner's "big single line"). */
internal fun posterLeadSizeSp(text: String): Float = when {
    text.length <= 12 -> 40f
    text.length <= 24 -> 34f
    text.length <= 40 -> 28f
    else -> 24f
}

/** Where the excerpt sits on the Player's progress bar, as fractions of the song. */
@Immutable
internal data class PlayerExcerpt(val start: Float, val end: Float, val startMs: Long, val endMs: Long)

/**
 * The span of [pick] on a [durationMs]-long song: its first line's start to its last line's start plus a
 * line's length, drawn at least [PlayerExcerptMinFraction] of the bar so a short pick still shows (owner
 * 2026-11-25). Null when the song's length or the lines' times are unknown.
 */
internal fun playerExcerpt(pick: LyricsSharePick, durationMs: Long): PlayerExcerpt? {
    if (durationMs <= 0L) return null
    val times = pick.lines.mapNotNull { it.startMs }
    if (times.isEmpty()) return null
    val startMs = times.min().coerceIn(0L, durationMs)
    val endMs = (times.max() + PlayerExcerptLineMs).coerceIn(startMs, durationMs)
    var start = startMs.toFloat() / durationMs
    var end = endMs.toFloat() / durationMs
    if (end - start < PlayerExcerptMinFraction) {
        end = (start + PlayerExcerptMinFraction).coerceAtMost(1f)
        start = (end - PlayerExcerptMinFraction).coerceAtLeast(0f)
    }
    return PlayerExcerpt(start, end, startMs, endMs)
}

internal const val PlayerExcerptMinFraction = 0.06f
private const val PlayerExcerptLineMs = 4_000L

/** How a 竖排 image sets its lines: each column's characters, top to bottom, and the type size. */
@Immutable
internal data class VerticalPlan(val columns: List<String>, val fontSizeSp: Float, val charsPerColumn: Int)

/**
 * Sets [lines] in columns [columnHeightDp] tall within [widthDp]: one line per column, a line longer than a
 * column carries on in the next. The largest size from [VerticalSizesSp] at which every line fits one column
 * and the columns fit the width; else the largest at which the columns fit; else the smallest (the image's
 * zoom takes it from there). Type is 1sp = 1dp (images lay out at font
 * scale 1).
 */
internal fun verticalLyricsPlan(lines: List<String>, columnHeightDp: Float, widthDp: Float): VerticalPlan {
    val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
    fun plan(size: Float): VerticalPlan {
        val perColumn = max(1, floor(columnHeightDp / (size * VerticalCharAdvance)).toInt())
        val columns = clean.flatMap { line -> line.replace(" ", "　").chunked(perColumn) }
        return VerticalPlan(columns, size, perColumn)
    }
    val fitting = VerticalSizesSp.map(::plan).filter { it.columns.size * it.fontSizeSp * VerticalColumnAdvance <= widthDp }
    // A line to a column reads as a poem; a line broken over two only when nothing larger fits whole.
    return fitting.firstOrNull { it.columns.size == clean.size }
        ?: fitting.firstOrNull()
        ?: plan(VerticalSizesSp.last())
}

/** Candidate sizes for 竖排, largest first. */
internal val VerticalSizesSp = listOf(28f, 26f, 24f, 22f, 20f, 17f, 15f, 13f)

/** One character's height in a column, × the type size. */
internal const val VerticalCharAdvance = 1.14f

/** One column's width with the gap after it, × the type size. */
internal const val VerticalColumnAdvance = 1.5f

/**
 * A character as vertical text sets it: CJK punctuation turns to its vertical presentation form (。， become
 * ︒︐, 「」 become ﹁﹂, dashes and ellipses stand up); everything else as it is.
 */
internal fun verticalForm(c: Char): Char = when (c) {
    '，' -> '︐'
    '、' -> '︑'
    '。' -> '︒'
    '：' -> '︓'
    '；' -> '︔'
    '！' -> '︕'
    '？' -> '︖'
    '「' -> '﹁'
    '」' -> '﹂'
    '『' -> '﹃'
    '』' -> '﹄'
    '（', '(' -> '︵'
    '）', ')' -> '︶'
    '《' -> '︽'
    '》' -> '︾'
    '〈' -> '︿'
    '〉' -> '﹀'
    '…' -> '︙'
    '—', '－', '-', 'ー' -> '︱'
    '～', '~' -> '≀'
    else -> c
}

/**
 * The line the playhead is in for a share opened without a pick (owner 2026-11-25: "lyrics tab with no
 * selection = current line"): the last timed line started by [positionMs], else the first line.
 */
internal fun currentLyricsPick(lyrics: List<LyricLine>, positionMs: Long, showTranslation: Boolean): LyricsSharePick? {
    val lines = lyrics.filter { it.text.isNotBlank() }
    if (lines.isEmpty()) return null
    val current = lines.lastOrNull { it.startMs != null && it.startMs <= positionMs } ?: lines.first()
    return LyricsSharePick(lines = listOf(current), showTranslation = showTranslation)
}

// ---------------------------------------------------------------------------
// About rules.
// ---------------------------------------------------------------------------

/**
 * About's share content: the credits (the lyrics' credits first, then the server's composer, then the AI's
 * lyricist / composer / producer, creation time and place — the receipt's "who wrote it, when and where" —
 * and the year), and one paragraph: [focus] when it is the AI's answer or review, else the review. Never
 * the server's file facts, never the listener's own entries. Null when there is nothing to share.
 */
internal fun aboutSharePick(
    entries: List<SongAboutEntry>,
    credits: LyricCredits?,
    facts: SpineSourceFacts?,
    labels: AboutLabels,
    reviewLabel: String,
    focus: SongAboutEntry? = null,
): AboutSharePick? {
    // Only the composer (a credit) and the year cross over from the server; every other fact stays home.
    val creditFacts = facts?.let { SpineSourceFacts(composer = it.composer, year = it.year) }
    val rows = aboutReceiptRows(entries, credits, creditFacts, labels).map { ShareCredit(it.label, it.value) }
    val aiParagraphs = aboutParagraphs(entries, reviewLabel).filter { it.entry.kind != SongAboutEntry.KIND_OWN }
    val chosen = focus?.let { f -> aiParagraphs.firstOrNull { it.entry.kind == f.kind && it.entry.entryKey == f.entryKey } } ?: aiParagraphs.firstOrNull {
        it.entry.kind == SongAboutEntry.KIND_CANONICAL
    }
    if (rows.isEmpty() && chosen == null) return null
    return AboutSharePick(
        credits = rows,
        heading = chosen?.label ?: reviewLabel,
        paragraph = chosen?.text,
    )
}

// ---------------------------------------------------------------------------
// Note rules.
// ---------------------------------------------------------------------------

/**
 * The note an image shares: [noteId] when given, else the note the playhead is in, else the newest one.
 */
internal fun noteSharePick(notes: List<SongNote>, noteId: String?, positionMs: Long): NoteSharePick? {
    val live = notes.filter { it.content.isNotBlank() }
    val note = noteId?.let { id -> live.firstOrNull { it.id == id } }
        ?: currentAnchoredNoteId(live, positionMs)?.let { id -> live.firstOrNull { it.id == id } }
        ?: live.maxByOrNull { it.createdAt }
        ?: return null
    return note.toSharePick()
}

internal fun SongNote.toSharePick(): NoteSharePick =
    NoteSharePick(noteId = id, content = content, positionMs = positionMs, createdAt = createdAt)

/** The note's date on an image: the full date ("2026年7月18日", "July 18, 2026") — a relative one would age. */
internal fun shareNoteDate(epochMs: Long, locale: Locale = Locale.getDefault()): String =
    DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(epochMs))

/** "1:05" for a moment in the song. */
internal fun shareTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(Locale.ROOT, total / 60L, total % 60L)
}

/**
 * The AI service's name as About's sign reads it ("由 Gemini 整理"). A minimal stand-in for PR #326's
 * `ui/component/AiProviderName.kt` (not on main yet): reconcile onto `aiProviderNameRes` once it lands.
 */
@StringRes
internal fun shareAiProviderName(provider: AiProvider): Int = when (provider) {
    AiProvider.Gemini -> R.string.ai_provider_gemini
    AiProvider.DeepSeek -> R.string.ai_provider_deepseek
    AiProvider.Qwen -> R.string.ai_provider_qwen
    AiProvider.Custom -> R.string.np_share_ai_generic
}
