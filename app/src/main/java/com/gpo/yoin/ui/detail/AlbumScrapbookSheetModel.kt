package com.gpo.yoin.ui.detail

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.gpo.yoin.data.album.AlbumScrapbookAboutRef
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.ui.common.UiText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/*
 * The track sheet's model beyond the page (owner-approved prototype v3, 2026-11-02): one song's timed lyrics
 * for the Note tab, Gemini's work in flight, what it says when it is done, and the pure rules the sheet
 * draws by — which lyric lines go around each note.
 */

/** One song's lyrics as the Note tab reads them: timed lines only (an untimed text has no moments). */
@Immutable
data class ScrapLyrics(val lines: List<ScrapLyricLine>, val loading: Boolean = false) {
    companion object {
        val None = ScrapLyrics(emptyList())
        val Loading = ScrapLyrics(emptyList(), loading = true)

        /** Blank timed lines (instrumental gaps) are dropped; untimed lyrics give no lines. */
        fun from(lyrics: Lyrics?): ScrapLyrics = when (lyrics) {
            is Lyrics.Synced -> ScrapLyrics(
                lyrics.lines
                    .filter { it.text.isNotBlank() }
                    .sortedBy { it.startMs }
                    .map { ScrapLyricLine(startMs = it.startMs, text = it.text.trim()) },
            )
            else -> None
        }
    }
}

@Immutable
data class ScrapLyricLine(val startMs: Long, val text: String)

/** Gemini's work in flight from page 2: rows being regenerated, questions not answered yet. */
@Immutable
data class ScrapGemini(
    val regenerating: Set<AlbumScrapbookAboutRef> = emptySet(),
    val asking: List<ScrapAsking> = emptyList(),
    /** Tracks whose About Gemini is writing from nothing ([ScrapEdit.Generate]), by song id. */
    val generating: Set<String> = emptySet(),
)

@Immutable
data class ScrapAsking(val songId: String, val question: String)

/** What the sheet says once page 2's Gemini work is done. */
sealed interface ScrapEvent {
    /** A row came back new; [restore] puts the old text back (Undo). */
    data class Regenerated(val restore: ScrapEdit.About) : ScrapEvent

    data class Failed(val message: UiText) : ScrapEvent
}

/** What the track sheet reads from its host beyond the page: lyrics per song, Gemini's state and outcomes. */
@Stable
class ScrapSheetFeeds(
    val lyrics: (songId: String) -> Flow<ScrapLyrics>,
    val gemini: StateFlow<ScrapGemini>,
    val events: Flow<ScrapEvent>,
) {
    companion object {
        /** A host without them (previews, tests): no lyrics, Gemini idle. */
        val None = ScrapSheetFeeds(
            lyrics = { flowOf(ScrapLyrics.None) },
            gemini = MutableStateFlow(ScrapGemini()),
            events = emptyFlow(),
        )
    }
}

/** One row of the Note tab, top to bottom. */
@Immutable
sealed interface ScrapNoteRow {
    val key: String

    /** A lyric line; [noted] when a note was taken on it. */
    data class Lyric(val index: Int, val line: ScrapLyricLine, val noted: Boolean) : ScrapNoteRow {
        override val key: String get() = "line:$index"
    }

    data class Note(val note: ScrapNoteLine) : ScrapNoteRow {
        override val key: String get() = "note:${note.noteId}"
    }

    /** The note being written from a lyric line (or, without timed lyrics, from nothing). */
    data class Draft(val positionMs: Long?) : ScrapNoteRow {
        override val key: String get() = "draft"
    }

    /** Lines left out between two passages ("· · ·"). */
    data class Gap(val after: Int) : ScrapNoteRow {
        override val key: String get() = "gap:$after"
    }
}

/** Lyric lines shown above and below a note (owner 2026-11-02: three each way). */
internal const val ScrapNoteContextLines = 3

/** The lyric line playing at [positionMs]: the last one started by then; -1 before the first. */
internal fun lyricIndexAt(lines: List<ScrapLyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].startMs <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

/**
 * The Note tab's rows. Each timed note sits under the line it was taken on, with [context] lines above and
 * below; passages that touch merge into one, so no line shows twice, and a [ScrapNoteRow.Gap] separates the
 * rest. [expanded] shows every line instead. Notes without a moment, or a song without timed lyrics, list
 * the notes alone. [draft] is the note being written, placed like a note at that moment.
 */
internal fun scrapNoteRows(
    lines: List<ScrapLyricLine>,
    notes: List<ScrapNoteLine>,
    expanded: Boolean,
    draft: ScrapNoteRow.Draft? = null,
    context: Int = ScrapNoteContextLines,
): List<ScrapNoteRow> {
    if (lines.isEmpty()) {
        return buildList {
            notes.forEach { add(ScrapNoteRow.Note(it)) }
            draft?.let(::add)
        }
    }
    // Anchored items (a note, or the draft) by the line they follow; -1 = before the first line.
    val atLine = HashMap<Int, MutableList<ScrapNoteRow>>()
    val loose = mutableListOf<ScrapNoteRow>()
    notes.forEach { note ->
        val at = note.positionMs?.let { lyricIndexAt(lines, it) }
        val row = ScrapNoteRow.Note(note)
        if (at == null) loose += row else atLine.getOrPut(at) { mutableListOf() } += row
    }
    draft?.let { row ->
        val at = row.positionMs?.let { lyricIndexAt(lines, it) }
        if (at == null) loose += row else atLine.getOrPut(at) { mutableListOf() } += row
    }
    val noted = notes.mapNotNullTo(HashSet()) { note -> note.positionMs?.let { lyricIndexAt(lines, it) } }

    val shownRanges: List<IntRange> = if (expanded) {
        listOf(0..lines.lastIndex)
    } else {
        val ranges = mutableListOf<IntRange>()
        atLine.keys.sorted().forEach { at ->
            val from = (at - context).coerceAtLeast(0)
            val to = (at + context).coerceIn(0, lines.lastIndex)
            val last = ranges.lastOrNull()
            if (last != null && from <= last.last + 1) {
                ranges[ranges.lastIndex] = last.first..maxOf(last.last, to)
            } else {
                ranges += from..to
            }
        }
        ranges
    }
    return buildList {
        shownRanges.forEachIndexed { i, range ->
            if (i > 0) add(ScrapNoteRow.Gap(after = shownRanges[i - 1].last))
            if (range.first == 0) atLine[-1]?.let(::addAll)
            for (index in range) {
                add(ScrapNoteRow.Lyric(index = index, line = lines[index], noted = index in noted))
                atLine[index]?.let(::addAll)
            }
        }
        if (loose.isNotEmpty() && shownRanges.isNotEmpty()) add(ScrapNoteRow.Gap(after = lines.lastIndex))
        addAll(loose)
    }
}
