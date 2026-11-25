package com.gpo.yoin.ui.detail

import androidx.compose.runtime.Immutable
import com.gpo.yoin.data.album.AlbumAboutAsk
import com.gpo.yoin.data.album.AlbumScrapbookAboutRef
import com.gpo.yoin.data.album.AlbumScrapbookAsk
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookFact
import com.gpo.yoin.data.album.AlbumScrapbookNote
import com.gpo.yoin.data.local.AlbumAboutEntry
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.shared.detail.ScrapbookTilt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor
import kotlin.math.sign

/*
 * The album page's second page: a scrapbook of what the listener left on this album, pasted in track order
 * on dotted paper (D3, owner-approved 2026-10-05 with changes: the score is the Memories groove emblem; the
 * listener's review is told apart by colour only — no "your review" label, no rail; the rail is reserved for
 * track notes). An album holds any number of album notes (owner 2026-11-22; one until then): one sticky
 * each, oldest first, then a dashed one to write the next. Irregular but stable: every tilt comes from a hash of the album id and
 * the piece's key, never from Random, so a page reopens exactly the same.
 *
 * This file is the model and its pure builder; AlbumScrapbookPage.kt draws it.
 */

sealed interface AlbumScrapbookUiState {
    data object Loading : AlbumScrapbookUiState

    data class Ready(val book: AlbumScrapbook) : AlbumScrapbookUiState
}

@Immutable
data class AlbumScrapbook(val pieces: List<ScrapPiece>)

/** One masonry item. [fullLine] items span every lane; the rest go to the shortest lane. */
@Immutable
sealed interface ScrapPiece {
    val key: String
    val fullLine: Boolean

    /**
     * Cover (with the score emblem hanging off its corner) beside the review, or a blank clipping to write
     * one; the album's one album note, when it has one, is a sticky pressed under them.
     */
    data class Opening(
        val review: String?,
        val coverTilt: Float,
        val reviewTilt: Float,
        val albumNote: String? = null,
        /** Leans against the review clipping (opposite sign); 0 without a note. */
        val albumNoteTilt: Float = 0f,
        /** The album's Memory title, beside the cover (owner 2026-10-05); null when it has none yet. */
        val title: ScrapTitle? = null,
        /** When [review] was written ("Today", "Oct 5"…); null without a review or a date. */
        val reviewAt: String? = null,
        /**
         * Every album note as a sticky, oldest first (an album holds any number, owner 2026-11-22); the
         * first leans like [albumNoteTilt], the rest alternate.
         */
        val stickies: List<ScrapSticky> = emptyList(),
        /**
         * The credits line under the title beside the cover (owner + Carbon 2026-11-25; it took Last
         * Play's place there): the known items only, in order ([albumCreditItems]); empty = no line.
         */
        val credits: List<AlbumCreditItem> = emptyList(),
    ) : ScrapPiece {
        override val key: String get() = "opening"
        override val fullLine: Boolean get() = true
    }

    /**
     * The album's About (owner + Carbon 2026-11-25): the editorial paragraph (Apple Music's own notes when
     * the service has them, else the AI's) on a tertiary-role card like the Ask cards,
     * right after the opening and before the tracks, a few lines on the paper; a tap opens the full text,
     * Regenerate and Ask Gemini. [text] is null while the AI writes it for the first time ([pending]), and
     * when it was asked and knew nothing (not [pending]): then the paper shows only a dashed ✦ circle.
     */
    data class AlbumAbout(
        val text: String?,
        val pending: Boolean,
        val tilt: Float,
        /** Questions asked about the album, newest first (the sheet lists them). */
        val asks: List<AlbumAboutAsk> = emptyList(),
        /**
         * [text] is Apple Music's own editorial notes, not the AI's: attributed to Apple Music, and its
         * Regenerate only redoes the credits.
         */
        val fromService: Boolean = false,
    ) : ScrapPiece {
        override val key: String get() = "albumabout"
        override val fullLine: Boolean get() = true
    }

    /** A track the listener left something on beyond a score: its ticket, notes, question and About. */
    data class TrackCluster(
        val track: ScrapTrack,
        /** Which side the ticket sits on; the next piece takes the other side. */
        val mirror: Boolean,
        /** The album's best score (≥ [ScrapbookRules.BestFrom]), at most one per album. */
        val best: Boolean,
        val ticketTilt: Float,
        /** Every note, timeline order; the clipping shows the first [ScrapbookRules.NotesShown]. */
        val notes: List<ScrapNoteLine>,
        /** Notes past the clipping ("+N notes" opens them all). */
        val hiddenNotes: Int,
        val notesTilt: Float,
        val ask: AlbumScrapbookAsk?,
        val moreAsks: Int,
        /** The About paragraph's first sentence, only when the track has no question. */
        val aboutLine: String?,
        val cardTilt: Float,
        val tags: List<ScrapTag>,
        val hiddenTags: Int,
        /** Everything Ask / About holds for this track (the full-text sheet). */
        val sheet: ScrapAboutSheet?,
    ) : ScrapPiece {
        override val key: String get() = "track:${track.key}"
        override val fullLine: Boolean get() = false
    }

    /** One or two tracks that only carry a score, side by side as small tickets. */
    data class ContactSheet(
        val tracks: List<ScrapTrack>,
        val tilts: List<Float>,
        val mirror: Boolean,
    ) : ScrapPiece {
        override val key: String get() = "sheet:${tracks.first().key}"
        override val fullLine: Boolean get() = false
    }

    /** An album with nothing on any track yet: one quiet line saying what page 2 collects. */
    data class NotYet(
        val tracks: List<ScrapNotYet>,
        /** Nothing on any track: the caption explains the page instead of counting. */
        val all: Boolean,
    ) : ScrapPiece {
        override val key: String get() = "notyet"
        override val fullLine: Boolean get() = true
    }

    /** Plays in Yoin, only for an album played here at least once. */
    data class Receipt(
        val header: String,
        val lines: List<Pair<String, String>>,
        val tilt: Float,
    ) : ScrapPiece {
        override val key: String get() = "receipt"
        override val fullLine: Boolean get() = true
    }
}

/** One album note on the page: its row id, its words (stored Markdown) and its lean. */
@Immutable
data class ScrapSticky(val noteId: String, val text: String, val tilt: Float)

@Immutable
data class ScrapTrack(
    val songId: String,
    /** Unique within the page (a provider can list one track twice). */
    val key: String,
    val number: Int,
    val title: String,
    val durationSec: Int?,
    val plays: Int,
    val score: Float?,
    val starred: Boolean,
    val heartTilt: Float,
)

@Immutable
data class ScrapNoteLine(
    val noteId: String,
    val songId: String,
    val positionMs: Long?,
    val text: String,
)

@Immutable
data class ScrapTag(
    val label: String,
    val value: String,
    val tilt: Float,
    /** The About row behind it, for editing; null in previews and tests. */
    val ref: AlbumScrapbookAboutRef? = null,
)

@Immutable
data class ScrapNotYet(val songId: String, val number: Int, val title: String, val tilt: Float)

/** The Ask / About sheet of one track: every question (newest first) and every fact. */
@Immutable
data class ScrapAboutSheet(
    val trackNumber: Int,
    val trackTitle: String,
    val asks: List<AlbumScrapbookAsk>,
    val facts: List<ScrapTag>,
)

/**
 * What the listener changes from page 2's track sheet (owner 2026-10-29: manage the AI text and the scores
 * in the scrapbook; 2026-11-02: and the notes, and new entries). Written straight to Room; the page re-reads
 * its data, so nothing here is optimistic.
 */
sealed interface ScrapEdit {
    /** A track's score, 0–10 (0.0 is a score); null clears it back to "not rated" (the sheet's Clear rating). */
    data class Score(val songId: String, val score: Float?) : ScrapEdit

    /**
     * New text for one Ask / About row: [title] only applies to a question. A blank [answer] removes the
     * row from the page (and from Now Playing's About).
     */
    data class About(val ref: AlbumScrapbookAboutRef, val title: String?, val answer: String) : ScrapEdit

    /** Ask Gemini about a track from its sheet; the answer joins the track's About like one asked in NP. */
    data class Ask(val songId: String, val question: String) : ScrapEdit

    /** The listener's own About entry: a heading and their text (no Gemini). */
    data class Write(val songId: String, val heading: String, val text: String) : ScrapEdit

    /**
     * Ask Gemini for a track's About from nothing (owner 2026-11-05: an empty sheet offers it, rather than
     * leaving the listener to think of a question).
     */
    data class Generate(val songId: String) : ScrapEdit

    /** Ask Gemini for one row again (owner 2026-10-29: what it said can go out of date). */
    data class Regenerate(val ref: AlbumScrapbookAboutRef) : ScrapEdit

    /** New text for a note; blank deletes it ([RestoreNote] brings it back). */
    data class NoteText(val noteId: String, val text: String) : ScrapEdit

    /** A note written from the sheet, at [positionMs] in the song (a lyric line's moment), or none. */
    data class NewNote(val songId: String, val positionMs: Long?, val text: String) : ScrapEdit

    /** Undo of a deleted note: the same note, back at its moment. */
    data class RestoreNote(val noteId: String) : ScrapEdit

    /** A new album note (an album holds any number, owner 2026-11-22); blank writes nothing. */
    data class NewAlbumNote(val text: String) : ScrapEdit

    /** New text for an album note; blank deletes it ([RestoreAlbumNote] brings it back). */
    data class AlbumNoteText(val noteId: String, val text: String) : ScrapEdit

    /** Undo of a deleted album note. */
    data class RestoreAlbumNote(val noteId: String) : ScrapEdit

    /** A tap on a to-do's box: line [lineIndex] of song note [noteId] ([album]: of album note [noteId]). */
    data class ToggleChecklist(val noteId: String, val lineIndex: Int, val album: Boolean = false) : ScrapEdit

    /**
     * The page came to rest: write the album's About if it was never written (one AI call per album, then
     * cached). Quiet: no key, or a failure, simply leaves the card off.
     */
    data object EnsureAlbumAbout : ScrapEdit

    /** "Regenerate" in the album's About sheet (undoable). */
    data object RegenerateAlbumAbout : ScrapEdit

    /** Undo of [RegenerateAlbumAbout]: the album's About rows as they were. */
    data class RestoreAlbumAbout(val rows: List<AlbumAboutEntry>) : ScrapEdit

    /** Ask Gemini about the album from its About sheet. */
    data class AskAlbum(val question: String) : ScrapEdit
}

/** The page's fixed rules (D3 §5). */
internal object ScrapbookRules {
    /** A track score at or above this can be the album's best. */
    const val BestFrom = 9.0f

    /** Notes shown on a clipping before "+N notes". */
    const val NotesShown = 3

    /** About facts shown as tags under a card before the rest go to the sheet. */
    const val TagsShown = 3

    /** Rating-only tracks per small-ticket row. */
    const val ContactSheetSize = 2

    /** Weighted characters (CJK counts 2) a question card shows on a phone / a wider lane. */
    const val AnswerBudget = 130
    const val AnswerBudgetWide = 160

    /** Answers show at most this many whole sentences on the card, never an ellipsis. */
    const val AnswerSentences = 2

    // Tilt sets in degrees; no 0 (a 0° piece reads as an alignment slip).
    // (Shared with iOS: com.gpo.yoin.shared.detail.ScrapbookTilt.)
    val PaperTilts = ScrapbookTilt.PAPER
    val CardTilts = ScrapbookTilt.CARD
    val TicketTilts = ScrapbookTilt.TICKET
    val StickerTilts = ScrapbookTilt.STICKER
    val CoverTilts = ScrapbookTilt.COVER
    val TagTilts = ScrapbookTilt.TAG
}

/**
 * Builds the scrapbook of [content] from [data] (pure: the same inputs always give the same page).
 * [nowMillis] / [zone] only feed the receipt's "days since" and "last played".
 */
internal fun buildAlbumScrapbook(
    content: AlbumDetailUiState.Content,
    data: AlbumScrapbookData,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    // The shared Memory-title resolver's answer (user > AI); null = read from [data].
    title: ScrapTitle? = null,
    // The AI is writing the album's About for the first time: its card holds the place with Gemini's dots.
    aboutPending: Boolean = false,
): AlbumScrapbook {
    val seed = content.albumId
    fun tilt(key: String, set: FloatArray): Float = ScrapbookTilt.tilt(seed, key, set)

    val ratings = data.ratings.mapKeys { it.key.toString() }
    val notes = data.notes.mapKeys { it.key.toString() }
    val about = data.about.mapKeys { it.key.toString() }
    val plays = data.playCounts.mapKeys { it.key.toString() }

    val tracks = content.songs.mapIndexed { index, song ->
        val number = song.trackNumber ?: (index + 1)
        ScrapTrack(
            songId = song.id,
            key = "${song.id}#$index",
            number = number,
            title = song.title,
            durationSec = song.duration,
            plays = plays[song.id] ?: 0,
            score = ratings[song.id],
            starred = song.isStarred,
            heartTilt = tilt("$number/heart", ScrapbookRules.StickerTilts),
        )
    }
    fun touched(track: ScrapTrack): Boolean {
        val a = about[track.songId]
        return track.score != null ||
            !notes[track.songId].isNullOrEmpty() ||
            (a != null && (a.asks.isNotEmpty() || a.facts.isNotEmpty()))
    }

    val pieces = mutableListOf<ScrapPiece>()

    val review = content.userReview.trim().takeIf(String::isNotEmpty)
    val reviewTilt = tilt(if (review != null) "review" else "ghost", ScrapbookRules.CardTilts)
    val albumNote = data.albumNote?.content?.trim()?.takeIf(String::isNotEmpty)
    val albumNoteTilt = albumNote?.let { opposite(reviewTilt, tilt("albumnote", ScrapbookRules.PaperTilts)) } ?: 0f
    val noteRows = data.albumNotes.ifEmpty { listOfNotNull(data.albumNote) }.filter { it.content.isNotBlank() }
    var previousStickyTilt: Float? = null
    val stickies = noteRows.mapIndexed { index, note ->
        val lean = if (index == 0) {
            opposite(reviewTilt, tilt("albumnote", ScrapbookRules.PaperTilts))
        } else {
            opposite(previousStickyTilt, tilt("albumnote/${note.id}", ScrapbookRules.PaperTilts))
        }
        previousStickyTilt = lean
        ScrapSticky(noteId = note.id, text = note.content.trim(), tilt = lean)
    }
    pieces += ScrapPiece.Opening(
        review = review,
        coverTilt = tilt("cover", ScrapbookRules.CoverTilts),
        reviewTilt = reviewTilt,
        albumNote = albumNote,
        albumNoteTilt = albumNoteTilt,
        stickies = stickies,
        title = title ?: scrapTitle(user = data.memoryTitleUser, ai = data.memoryTitle),
        reviewAt = content.userReviewAt?.takeIf { review != null }?.let { relativeDayLabel(it, nowMillis, zone) },
        credits = albumCreditItems(
            sourceLabels = content.recordLabels,
            releaseDate = content.releaseDate,
            originalReleaseDate = content.originalReleaseDate,
            ai = data.albumAbout.credits,
        ),
    )
    // The service's own notes (Apple Music) before the AI's paragraph.
    val serviceNotes = content.editorialNotes?.trim()?.takeIf(String::isNotEmpty)
    val blurb = serviceNotes ?: data.albumAbout.blurb
    // Asked before and nothing came back (the AI knew nothing): the piece stays as a quiet way back in (a
    // dashed circle, like the blank review), to generate again or ask.
    if (blurb != null || aboutPending || data.albumAbout.generated) {
        pieces += ScrapPiece.AlbumAbout(
            text = blurb,
            pending = aboutPending && blurb == null,
            tilt = tilt("albumabout", ScrapbookRules.CardTilts),
            asks = data.albumAbout.asks,
            fromService = serviceNotes != null,
        )
    }

    val best = tracks
        .filter { it.score != null && it.score >= ScrapbookRules.BestFrom }
        .maxByOrNull { it.score ?: 0f }
        ?.key
    val startMirror = ScrapbookTilt.startsMirrored(seed)
    var clusterIndex = 0
    val run = mutableListOf<ScrapTrack>()
    fun mirrorNow(): Boolean = ((clusterIndex % 2) == 1) != startMirror

    fun flushRun() {
        if (run.isEmpty()) return
        run.chunked(ScrapbookRules.ContactSheetSize).forEach { chunk ->
            var previous: Float? = null
            val tilts = chunk.map { track ->
                opposite(previous, tilt("${track.number}/tk", ScrapbookRules.TicketTilts)).also { previous = it }
            }
            pieces += ScrapPiece.ContactSheet(tracks = chunk, tilts = tilts, mirror = mirrorNow())
        }
        clusterIndex++
        run.clear()
    }

    for (track in tracks) {
        if (!touched(track)) continue
        val trackNotes = notes[track.songId].orEmpty()
        val trackAbout = about[track.songId]
        val asks = trackAbout?.asks.orEmpty()
        val facts = trackAbout?.facts.orEmpty()
        val isBest = track.key == best
        if (trackNotes.isEmpty() && asks.isEmpty() && facts.isEmpty() && !isBest) {
            run += track
            continue
        }
        flushRun()
        val ticketTilt = tilt("${track.number}/tk", ScrapbookRules.TicketTilts)
        var previous: Float = ticketTilt
        val notesTilt = if (trackNotes.isNotEmpty()) {
            opposite(previous, tilt("${track.number}/nt", ScrapbookRules.PaperTilts)).also { previous = it }
        } else {
            0f
        }
        val paragraph = facts.firstOrNull { it.key == SongAboutEntry.CANON_REVIEW }?.value
        val aboutLine = if (asks.isEmpty() && paragraph != null) {
            answerExcerpt(paragraph, ScrapbookRules.AnswerBudget, maxSentences = 1).text
        } else {
            null
        }
        val cardTilt = if (asks.isNotEmpty() || aboutLine != null) {
            opposite(previous, tilt("${track.number}/qa", ScrapbookRules.CardTilts))
        } else {
            0f
        }
        val allTags = facts
            .filter { it.key != SongAboutEntry.CANON_REVIEW }
            .mapIndexed { i, fact -> fact.toTag(tilt("${track.number}/fact$i", ScrapbookRules.TagTilts)) }
        val sheetFacts = facts.mapIndexed { i, fact -> fact.toTag(tilt("${track.number}/fact$i", ScrapbookRules.TagTilts)) }
        pieces += ScrapPiece.TrackCluster(
            track = track,
            mirror = mirrorNow(),
            best = isBest,
            ticketTilt = ticketTilt,
            notes = trackNotes.map { it.toLine(track.songId) },
            hiddenNotes = (trackNotes.size - ScrapbookRules.NotesShown).coerceAtLeast(0),
            notesTilt = notesTilt,
            ask = asks.firstOrNull(),
            moreAsks = (asks.size - 1).coerceAtLeast(0),
            aboutLine = aboutLine,
            cardTilt = cardTilt,
            tags = allTags.take(ScrapbookRules.TagsShown),
            hiddenTags = (allTags.size - ScrapbookRules.TagsShown).coerceAtLeast(0),
            sheet = if (asks.isNotEmpty() || facts.isNotEmpty()) {
                ScrapAboutSheet(
                    trackNumber = track.number,
                    trackTitle = track.title,
                    asks = asks,
                    facts = sheetFacts,
                )
            } else {
                null
            },
        )
        clusterIndex++
    }
    flushRun()

    // Only an album with nothing on it at all gets the "Not yet" hint: once
    // anything is left, page 2 shows just what was left (owner 2026-10-05:
    // "Not yet" read as unexplained; the receipt already says "Rated 2 / 4").
    val untouched = tracks.filterNot(::touched)
    if (untouched.isNotEmpty() && untouched.size == tracks.size) {
        pieces += ScrapPiece.NotYet(
            tracks = untouched.map { track ->
                ScrapNotYet(
                    songId = track.songId,
                    number = track.number,
                    title = track.title,
                    tilt = tilt("${track.number}/ny", ScrapbookRules.StickerTilts) / 2f,
                )
            },
            all = untouched.size == tracks.size,
        )
    }

    if (data.plays.count > 0) {
        pieces += ScrapPiece.Receipt(
            header = "${content.albumName} — ${content.artistName}".uppercase(Locale.ROOT),
            lines = receiptLines(content, data, nowMillis, zone),
            tilt = tilt("receipt", ScrapbookRules.PaperTilts),
        )
    }
    return AlbumScrapbook(pieces)
}

private fun receiptLines(
    content: AlbumDetailUiState.Content,
    data: AlbumScrapbookData,
    nowMillis: Long,
    zone: ZoneId,
): List<Pair<String, String>> = buildList {
    // Only the songs on this page count (a score on a track the provider no longer lists stays out).
    val songIds = content.songs.mapTo(HashSet()) { it.id }
    // What the album is, before what Yoin knows of it: when it came out and how long it runs.
    content.releaseDate?.let { add(ReceiptReleasedLabel to receiptReleaseDate(it)) }
    val length = content.totalDuration?.takeIf { it > 0 }
        ?: content.songs.sumOf { it.duration ?: 0 }.takeIf { it > 0 }
    receiptAlbumLength(length)?.let { add(ReceiptLengthLabel to it) }
    add("Plays in Yoin" to data.plays.count.toString()) // i18n-allow: AlbumScrapbookTest asserts this receipt English
    data.plays.firstPlayedAt?.let { first ->
        val daysSince = daysBetween(first, nowMillis, zone).toString()
        // i18n-allow: AlbumScrapbookTest asserts this receipt English
        add("Days since first play" to daysSince)
    }
    data.plays.lastPlayedAt?.let { last ->
        // i18n-allow: AlbumScrapbookTest asserts this receipt English
        add("Last played" to relativeDayLabel(last, nowMillis, zone))
    }
    val rated = data.ratings.keys.count { it.toString() in songIds }
    add("Rated" to "$rated / ${content.trackTotal}") // i18n-allow: AlbumScrapbookTest asserts this receipt English
    val trackNotes = data.notes.entries.filter { it.key.toString() in songIds }.sumOf { it.value.size }
    // every album note counts with the track notes (D3's receipt)
    val albumNotes = data.albumNotes.count { it.content.isNotBlank() }
        .takeIf { it > 0 } ?: if (data.albumNote?.content.isNullOrBlank()) 0 else 1
    add("Notes" to (trackNotes + albumNotes).toString()) // i18n-allow: AlbumScrapbookTest asserts this receipt English
    // Questions about the songs and about the album itself (its About sheet's Ask) alike.
    val askCount = data.about.entries
        .filter { it.key.toString() in songIds }
        .sumOf { (_, about) -> about.asks.count { !it.own } } + data.albumAbout.asks.size
    add("Asked" to askCount.toString()) // i18n-allow: AlbumScrapbookTest asserts this receipt English
}

/** Whole calendar days from [fromMillis] to [nowMillis] in [zone] (0 on the same day). */
internal fun daysBetween(fromMillis: Long, nowMillis: Long, zone: ZoneId): Long {
    val from = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(from, now).coerceAtLeast(0)
}

/** "Today" / "Yesterday" / "3 days ago" / "Mar 14", like page 1's Last Play. */
internal fun relativeDayLabel(epochMillis: Long, nowMillis: Long, zone: ZoneId): String {
    val days = daysBetween(epochMillis, nowMillis, zone)
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return when {
        days <= 0L -> "Today" // i18n-allow: AlbumScrapbookTest asserts this receipt English
        days == 1L -> "Yesterday" // i18n-allow: AlbumScrapbookTest asserts this receipt English
        days < 7L -> "$days days ago" // i18n-allow: AlbumScrapbookTest asserts this receipt English
        // Another year says which one.
        // i18n-allow: AlbumScrapbookTest asserts this receipt English
        date.year != today.year -> date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH))
        // i18n-allow: AlbumScrapbookTest asserts this receipt English
        else -> date.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
    }
}

/** The excerpt a card shows and whether anything was left for the sheet. */
data class AnswerExcerpt(val text: String, val truncated: Boolean)

/**
 * Whole sentences of [answer], never an ellipsis: the first sentence always, then more while they fit
 * [budget] weighted characters (CJK counts 2, `**` markers count 0) up to [maxSentences].
 */
internal fun answerExcerpt(
    answer: String,
    budget: Int,
    maxSentences: Int = ScrapbookRules.AnswerSentences,
): AnswerExcerpt {
    val sentences = splitSentences(answer.trim())
    if (sentences.isEmpty()) return AnswerExcerpt("", truncated = false)
    val shown = StringBuilder()
    var weight = 0
    var count = 0
    for (sentence in sentences) {
        val w = weightedLength(sentence)
        if (count < maxSentences && (count == 0 || weight + w <= budget)) {
            shown.append(sentence)
            weight += w
            count++
        } else {
            break
        }
    }
    return AnswerExcerpt(shown.toString().trim(), truncated = count < sentences.size)
}

private val SentenceRegex = Regex("[^。！？.!?]+[。！？.!?]+[\"”’）)]*|[^。！？.!?]+$")

internal fun splitSentences(text: String): List<String> =
    SentenceRegex.findAll(text).map { it.value }.filter { it.isNotBlank() }.toList()

/** Characters with CJK / full-width counting 2 and markdown bold markers counting 0. */
internal fun weightedLength(text: String): Int =
    text.replace("**", "").sumOf { c -> if (c.code in 0x3000..0x9FFF || c.code in 0xFF00..0xFFEF) 2 else 1 }

/** Neighbours lean opposite ways: [angle] flipped when it leans the same way as [previous]. */
internal fun opposite(previous: Float?, angle: Float): Float =
    if (previous != null && sign(previous) == sign(angle)) -angle else angle

/** One-decimal score text with the Memories rounding (9.95 → "10.0"), so tickets agree with the emblem. */
internal fun scrapScoreText(score: Float): String {
    val tenths = floor(score.toString().toDouble() * 10 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

internal fun factLabel(key: String): String = when (key) {
    SongAboutEntry.CANON_CREATION_TIME -> "Created" // i18n-allow: AlbumScrapbookTest asserts this English
    SongAboutEntry.CANON_CREATION_LOCATION -> "Recorded in" // i18n-allow: AlbumScrapbookTest asserts this English
    SongAboutEntry.CANON_LYRICIST -> "Lyricist" // i18n-allow: AlbumScrapbookTest asserts this English
    SongAboutEntry.CANON_COMPOSER -> "Composer" // i18n-allow: AlbumScrapbookTest asserts this English
    SongAboutEntry.CANON_PRODUCER -> "Producer" // i18n-allow: AlbumScrapbookTest asserts this English
    SongAboutEntry.CANON_REVIEW -> "About" // i18n-allow: AlbumScrapbookTest asserts this English
    else -> key
}

private fun AlbumScrapbookFact.toTag(tilt: Float) =
    ScrapTag(label = factLabel(key), value = value, tilt = tilt, ref = ref)

private fun AlbumScrapbookNote.toLine(songId: String) =
    ScrapNoteLine(noteId = id, songId = songId, positionMs = positionMs, text = content)

/** What page 1 and the scrapbook's emblem show: the score rule of [albumScore] plus which tracks are rated. */
@Immutable
data class AlbumEmblemSpec(
    val score: AlbumScore,
    /** Album order (track 1 first). */
    val trackRated: List<Boolean>,
)

fun AlbumDetailUiState.Content.emblemSpec(): AlbumEmblemSpec = AlbumEmblemSpec(
    score = albumScore(),
    trackRated = songs.map { it.id in ratedSongIds },
)

/**
 * The album's Memory title as page 2 shows it: the user's own name when they
 * gave one, else Yoin's (AI) title. [edited] = the user's; [canRestore] = an
 * AI title is there to go back to.
 */
@Immutable
data class ScrapTitle(
    val text: String,
    val edited: Boolean,
    val canRestore: Boolean,
    /** "Restore AI title" / "Restore Yoin's title" (the shared resolver's wording). */
    val restoreLabel: String = "Restore AI title",
    /** A title the user or the AI wrote reads in the serif; Yoin's motif in the app face. */
    val serif: Boolean = true,
    /** What the rename field starts with. */
    val draftSeed: String = text,
)

internal fun scrapTitle(user: String?, ai: String?): ScrapTitle? {
    val mine = user?.trim()?.takeIf(String::isNotEmpty)
    val generated = ai?.trim()?.takeIf(String::isNotEmpty)
    return when {
        mine != null -> ScrapTitle(mine, edited = true, canRestore = generated != null)
        generated != null -> ScrapTitle(generated, edited = false, canRestore = false)
        else -> null
    }
}
