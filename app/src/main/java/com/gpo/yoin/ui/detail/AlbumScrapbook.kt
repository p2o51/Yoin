package com.gpo.yoin.ui.detail

import androidx.compose.runtime.Immutable
import com.gpo.yoin.data.album.AlbumScrapbookAsk
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookFact
import com.gpo.yoin.data.album.AlbumScrapbookNote
import com.gpo.yoin.data.local.SongAboutEntry
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
 * track notes). An album has one album note (owner, 2026-10-05): at most one sticky, the latest row — the
 * same one the Memories diary shows. Irregular but stable: every tilt comes from a hash of the album id and
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
    ) : ScrapPiece {
        override val key: String get() = "opening"
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
data class ScrapTag(val label: String, val value: String, val tilt: Float)

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
    val PaperTilts = floatArrayOf(-1.5f, -1f, -0.5f, 0.5f, 1f, 1.5f)
    val CardTilts = floatArrayOf(-1f, -0.6f, 0.6f, 1f)
    val TicketTilts = floatArrayOf(-2f, -1.25f, 1.25f, 2f)
    val StickerTilts = floatArrayOf(-12f, -8f, -5f, 5f, 8f, 12f)
    val CoverTilts = floatArrayOf(-3f, 3f)
    val TagTilts = floatArrayOf(-3f, -2f, 2f, 3f)
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
): AlbumScrapbook {
    val seed = content.albumId
    fun tilt(key: String, set: FloatArray): Float = set[(scrapHash("$seed/$key") % set.size).toInt()]

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
    pieces += ScrapPiece.Opening(
        review = review,
        coverTilt = tilt("cover", ScrapbookRules.CoverTilts),
        reviewTilt = reviewTilt,
        albumNote = albumNote,
        albumNoteTilt = albumNote?.let { opposite(reviewTilt, tilt("albumnote", ScrapbookRules.PaperTilts)) } ?: 0f,
        title = title ?: scrapTitle(user = data.memoryTitleUser, ai = data.memoryTitle),
        reviewAt = content.userReviewAt?.takeIf { review != null }?.let { relativeDayLabel(it, nowMillis, zone) },
    )

    val best = tracks
        .filter { it.score != null && it.score >= ScrapbookRules.BestFrom }
        .maxByOrNull { it.score ?: 0f }
        ?.key
    val startMirror = (scrapHash(seed) and 1L) == 1L
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
    // the album's one album note counts with the track notes (D3's receipt)
    val albumNotes = if (data.albumNote?.content.isNullOrBlank()) 0 else 1
    add("Notes" to (trackNotes + albumNotes).toString()) // i18n-allow: AlbumScrapbookTest asserts this receipt English
    val askCount = data.about.entries.filter { it.key.toString() in songIds }.sumOf { it.value.asks.size }
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

/** FNV-1a over UTF-16 units, as an unsigned 32-bit value (the prototype's `hash`). */
internal fun scrapHash(text: String): Long {
    var h = 0x811C9DC5.toInt()
    for (c in text) {
        h = h xor c.code
        h *= 16777619
    }
    return h.toLong() and 0xFFFFFFFFL
}

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

private fun AlbumScrapbookFact.toTag(tilt: Float) = ScrapTag(label = factLabel(key), value = value, tilt = tilt)

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
