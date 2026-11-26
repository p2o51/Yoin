package com.gpo.yoin.ui.nowplaying

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.model.LyricCreditRole
import com.gpo.yoin.data.model.LyricCredits
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.shared.share.ShareAboutEntry
import com.gpo.yoin.shared.share.ShareAboutLabels
import com.gpo.yoin.shared.share.ShareAboutSources
import com.gpo.yoin.shared.share.ShareCardContent
import com.gpo.yoin.shared.share.ShareExcerpt
import com.gpo.yoin.shared.share.ShareLyricLine
import com.gpo.yoin.shared.share.ShareLyrics
import com.gpo.yoin.shared.share.ShareVerticalPlan
import com.gpo.yoin.shared.share.YoinShareCards
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/*
 * Share as image (owner 2026-11-25, prototypes design/hifi/src/components/share.js): one sheet, three tabs
 * — the lyrics, the song's About (AI / objective text, tertiary) and one of the listener's notes (their own
 * words, primary) — each with its own layouts. This file is the model: what each tab shares and which layouts
 * it offers. The rules themselves live in :shared ([YoinShareCards], com.gpo.yoin.shared.share) so iOS and
 * HarmonyOS answer alike (golden vectors: shared/src/commonTest/resources/golden/share-cards.json); here they
 * are adapted to Now Playing's types. ShareCards.kt draws the images, ShareSheet.kt the sheet.
 */

/** The sheet's tabs, in order: 歌词 / 关于 / 笔记 ([com.gpo.yoin.shared.share.ShareTab]). */
internal typealias ShareTab = com.gpo.yoin.shared.share.ShareTab

/** Every layout of every tab, in each tab's 版式 order; each tab's first is its default. */
internal typealias ShareLayout = com.gpo.yoin.shared.share.ShareLayout

/** The tab's name on the sheet: the Now Playing tabs' words. */
@get:StringRes
internal val ShareTab.label: Int
    get() = when (this) {
        ShareTab.Lyrics -> R.string.np_tab_lyrics
        ShareTab.About -> R.string.np_tab_about
        ShareTab.Note -> R.string.np_tab_note
    }

/** The layout's name under its thumbnail. */
@get:StringRes
internal val ShareLayout.label: Int
    get() = when (this) {
        ShareLayout.LyricsClassic -> R.string.np_share_layout_classic
        ShareLayout.LyricsPoster -> R.string.np_share_layout_poster
        ShareLayout.LyricsPlayer -> R.string.np_share_layout_player
        ShareLayout.LyricsVertical -> R.string.np_share_layout_vertical
        ShareLayout.AboutReceipt -> R.string.np_share_layout_receipt
        ShareLayout.AboutQuote -> R.string.np_share_layout_quote
        ShareLayout.AboutLiner -> R.string.np_share_layout_liner
        ShareLayout.NoteBig -> R.string.np_share_layout_big
        ShareLayout.NoteClipping -> R.string.np_share_layout_clipping
        ShareLayout.NoteTimeline -> R.string.np_share_layout_timeline
    }

/** The song an image is about. [ShareSong.durationMs] 0 = unknown. */
internal typealias ShareSong = com.gpo.yoin.shared.share.ShareSong

/** The lyric lines to share: the select-mode pick, or the current line. */
@Immutable
internal data class LyricsSharePick(
    val lines: List<LyricLine>,
    /** Positions in [lines] that start a new passage (the pick skipped part of the song there). */
    val passageStarts: Set<Int> = emptySet(),
    val showTranslation: Boolean = false,
) {
    fun toShared(): ShareLyrics = ShareLyrics(
        lines = lines.map(LyricLine::toShared),
        passageStarts = passageStarts.sorted(),
        showTranslation = showTranslation,
    )
}

internal fun LyricLine.toShared(): ShareLyricLine =
    ShareLyricLine(startMs = startMs, text = text, translation = translation)

internal fun ShareLyricLine.toLyricLine(): LyricLine =
    LyricLine(startMs = startMs, text = text, translation = translation)

/**
 * One line on an About image ("作词" / "吴青峰"): [ShareCredit.ai] the AI wrote it, [ShareCredit.fact] a plain
 * fact about the release (the year) — on its own not worth an About image.
 */
internal typealias ShareCredit = com.gpo.yoin.shared.share.ShareCredit

/**
 * About as shared (owner 2026-11-25): the credits and ONE paragraph, nothing else — none of the server's
 * file facts and none of the listener's own About entries. [ShareAbout.aiContent]: only then does the image
 * say "由 X 整理".
 */
internal typealias AboutSharePick = com.gpo.yoin.shared.share.ShareAbout

/** One of the listener's notes (one note per image); its content is the stored Markdown. */
internal typealias NoteSharePick = com.gpo.yoin.shared.share.ShareNote

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
    fun toShared(): ShareCardContent =
        ShareCardContent(song = song, lyrics = lyrics?.toShared(), about = about, note = note)

    fun has(tab: ShareTab): Boolean = YoinShareCards.has(toShared(), tab)

    /** The tabs this song has something for, in order. */
    val tabs: List<ShareTab> get() = YoinShareCards.tabs(toShared())
}

/** The layouts [tab] offers for this content, in 版式 order (empty when the tab has nothing). */
internal fun ShareContent.layoutsFor(tab: ShareTab): List<ShareLayout> = YoinShareCards.layouts(toShared(), tab)

/** [remembered] when this content offers it, else the tab's first layout. */
internal fun ShareContent.resolveLayout(tab: ShareTab, remembered: ShareLayout?): ShareLayout? =
    YoinShareCards.resolveLayout(toShared(), tab, remembered)

// ---------------------------------------------------------------------------
// Lyrics rules.
// ---------------------------------------------------------------------------

/** Whether [text] is CJK all through ([YoinShareCards.isCjk]): 竖排's gate. */
internal fun isCjkLyric(text: String): Boolean = YoinShareCards.isCjk(text)

/** The most lines a Poster shows: the first big, up to three more small (owner 2026-11-25). */
internal const val PosterMaxLines = YoinShareCards.posterMaxLines

/** What a Poster draws: [lead] at display size, [rest] small, [translation] as one quiet line at the very bottom. */
@Immutable
internal data class PosterLines(val lead: LyricLine, val rest: List<LyricLine>, val translation: String?)

internal fun posterLines(pick: LyricsSharePick): PosterLines? = YoinShareCards.poster(pick.toShared())?.let { poster ->
    PosterLines(
        lead = poster.lead.toLyricLine(),
        rest = poster.rest.map { it.toLyricLine() },
        translation = poster.translation,
    )
}

/** The lead line's size on a Poster: the shorter the line, the larger (owner's "big single line"). */
internal fun posterLeadSizeSp(text: String): Float = YoinShareCards.posterLeadSizeSp(text)

/** Where the excerpt sits on the Player's progress bar, as fractions of the song. */
internal typealias PlayerExcerpt = ShareExcerpt

/** The span of [pick] on a [durationMs]-long song, at least 6% of the bar ([YoinShareCards.playerExcerpt]). */
internal fun playerExcerpt(pick: LyricsSharePick, durationMs: Long): PlayerExcerpt? =
    YoinShareCards.playerExcerpt(pick.toShared(), durationMs)

internal const val PlayerExcerptMinFraction = YoinShareCards.playerExcerptMinFraction

/** How a 竖排 image sets its lines: each column's characters, top to bottom, and the type size. */
internal typealias VerticalPlan = ShareVerticalPlan

/** [lines] in columns [columnHeightDp] tall within [widthDp] ([YoinShareCards.verticalPlan]). */
internal fun verticalLyricsPlan(lines: List<String>, columnHeightDp: Float, widthDp: Float): VerticalPlan =
    YoinShareCards.verticalPlan(lines, columnHeightDp, widthDp)

/** One character's height in a column, × the type size. */
internal const val VerticalCharAdvance = YoinShareCards.verticalCharAdvance

/** One column's width with the gap after it, × the type size. */
internal const val VerticalColumnAdvance = YoinShareCards.verticalColumnAdvance

/** A character as vertical text sets it ([YoinShareCards.verticalForm]). */
internal fun verticalForm(c: Char): Char = YoinShareCards.verticalForm(c)

/**
 * The line the playhead is in for a share opened without a pick (owner 2026-11-25: "lyrics tab with no
 * selection = current line"): the last timed line started by [positionMs], else the first line.
 */
internal fun currentLyricsPick(lyrics: List<LyricLine>, positionMs: Long, showTranslation: Boolean): LyricsSharePick? =
    YoinShareCards.currentLine(lyrics.map(LyricLine::toShared), positionMs, showTranslation)?.let { pick ->
        LyricsSharePick(lines = pick.lines.map { it.toLyricLine() }, showTranslation = pick.showTranslation)
    }

// ---------------------------------------------------------------------------
// About rules.
// ---------------------------------------------------------------------------

/**
 * About's share content ([YoinShareCards.aboutPick]) from Now Playing's sources: the lyrics' headline credits,
 * the server's composer and year (no other fact crosses over), the About rows, and the row long-pressed
 * ([focus]). Null when there is nothing to share.
 */
internal fun aboutSharePick(
    entries: List<SongAboutEntry>,
    credits: LyricCredits?,
    facts: SpineSourceFacts?,
    labels: AboutLabels,
    reviewLabel: String,
    focus: SongAboutEntry? = null,
): AboutSharePick? = YoinShareCards.aboutPick(
    entries = entries.map { ShareAboutEntry(it.kind, it.entryKey, it.promptText, it.titleText, it.answerText) },
    sources = ShareAboutSources(
        lyricist = credits?.namesFor(LyricCreditRole.Lyricist),
        composer = credits?.namesFor(LyricCreditRole.Composer),
        arranger = credits?.namesFor(LyricCreditRole.Arranger),
        producer = credits?.namesFor(LyricCreditRole.Producer),
        serverComposer = facts?.composer,
        year = facts?.year,
    ),
    labels = ShareAboutLabels(
        lyricist = labels.about(SongAboutEntry.CANON_LYRICIST),
        composer = labels.about(SongAboutEntry.CANON_COMPOSER),
        arranger = labels.arranger,
        producer = labels.about(SongAboutEntry.CANON_PRODUCER),
        creationTime = labels.about(SongAboutEntry.CANON_CREATION_TIME),
        creationLocation = labels.about(SongAboutEntry.CANON_CREATION_LOCATION),
        year = labels.year,
        review = reviewLabel,
    ),
    focusKind = focus?.kind,
    focusKey = focus?.entryKey,
)

// ---------------------------------------------------------------------------
// Note rules.
// ---------------------------------------------------------------------------

/**
 * The note an image shares: [noteId] when given, else the note the playhead is in, else the newest one.
 */
internal fun noteSharePick(notes: List<SongNote>, noteId: String?, positionMs: Long): NoteSharePick? =
    YoinShareCards.notePick(notes.map { it.toSharePick() }, noteId, positionMs)

internal fun SongNote.toSharePick(): NoteSharePick =
    NoteSharePick(noteId = id, content = content, positionMs = positionMs, createdAt = createdAt)

/** The note's date on an image: the full date ("2026年7月18日", "July 18, 2026") — a relative one would age. */
internal fun shareNoteDate(epochMs: Long, locale: Locale = Locale.getDefault()): String =
    DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(epochMs))

/** "1:05" for a moment in the song. */
internal fun shareTime(ms: Long): String = YoinShareCards.time(ms)

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
