package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.model.LyricLine
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.ui.component.remainingQuickPrompts
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumScrapbookSheetModelTest {

    /** "L0" at 0:00 … "L19" at 3:10, ten seconds apart. */
    private val lines = (0..19).map { ScrapLyricLine(it * 10_000L, "L$it") }

    @Test
    fun should_findTheLinePlaying_when_aMomentIsGiven() {
        assertEquals(-1, lyricIndexAt(listOf(ScrapLyricLine(5_000L, "first")), 1_000L))
        assertEquals(0, lyricIndexAt(lines, 0L))
        assertEquals(6, lyricIndexAt(lines, 61_000L))
        assertEquals(19, lyricIndexAt(lines, 999_000L))
    }

    @Test
    fun should_showThreeLinesEachSide_when_aNoteSitsMidSong() {
        val rows = scrapNoteRows(lines, listOf(note("a", 61_000L)), expanded = false)

        assertEquals(
            listOf("L3", "L4", "L5", "L6", "note a", "L7", "L8", "L9"),
            rows.map(::label),
        )
        assertEquals(listOf(6), rows.filterIsInstance<ScrapNoteRow.Lyric>().filter { it.noted }.map { it.index })
    }

    @Test
    fun should_mergePassages_when_notesAreClose_andGapThemOtherwise() {
        val notes = listOf(note("a", 21_000L), note("b", 81_000L), note("c", 181_000L))

        val rows = scrapNoteRows(lines, notes, expanded = false)

        // a (line 2) → 0..5 and b (line 8) → 5..11 touch: one passage; c (line 18) → 15..19 after a gap
        assertEquals(
            "L0|L1|L2|note a|L3|L4|L5|L6|L7|L8|note b|L9|L10|L11|gap|L15|L16|L17|L18|note c|L19",
            rows.joinToString("|", transform = ::label),
        )
    }

    @Test
    fun should_putEveryLineInPlace_when_expanded() {
        val rows = scrapNoteRows(lines, listOf(note("a", 61_000L)), expanded = true)

        assertEquals(lines.size + 1, rows.size)
        assertEquals("note a", label(rows[7]))
    }

    @Test
    fun should_listNotesAlone_when_theSongHasNoTimedLyrics() {
        val rows = scrapNoteRows(emptyList(), listOf(note("a", 61_000L), note("b", null)), expanded = false)

        assertEquals(listOf("note a", "note b"), rows.map(::label))
    }

    @Test
    fun should_keepUnanchoredNotesLast_afterAGap() {
        val rows = scrapNoteRows(lines, listOf(note("a", 1_000L), note("b", null)), expanded = false)

        assertEquals(listOf("L0", "note a", "L1", "L2", "L3", "gap", "note b"), rows.map(::label))
    }

    @Test
    fun should_placeTheDraftUnderItsLine_withItsOwnPassage() {
        val rows = scrapNoteRows(lines, emptyList(), expanded = false, draft = ScrapNoteRow.Draft(150_000L))

        assertEquals(listOf("L12", "L13", "L14", "L15", "draft", "L16", "L17", "L18"), rows.map(::label))
    }

    @Test
    fun should_putANoteBeforeTheFirstLine_when_itWasTakenInTheIntro() {
        val late = listOf(ScrapLyricLine(20_000L, "first"), ScrapLyricLine(30_000L, "second"))

        val rows = scrapNoteRows(late, listOf(note("intro", 5_000L)), expanded = false)

        assertEquals(listOf("note intro", "first", "second"), rows.map(::label))
    }

    @Test
    fun should_dropBlankAndUntimedLyrics_when_readForTheSheet() {
        val synced = Lyrics.Synced(
            listOf(LyricLine(2_000L, " two "), LyricLine(1_000L, "one"), LyricLine(3_000L, "  ")),
        )

        assertEquals(
            listOf(ScrapLyricLine(1_000L, "one"), ScrapLyricLine(2_000L, "two")),
            ScrapLyrics.from(synced).lines,
        )
        assertEquals(ScrapLyrics.None, ScrapLyrics.from(Lyrics.Unsynced("words")))
        assertEquals(ScrapLyrics.None, ScrapLyrics.from(null))
    }

    @Test
    fun should_offerOnlyQuestionsNotAskedYet() {
        val prompts = listOf("Who sampled this song?", "What are the lyrics about?")

        assertEquals(
            listOf("What are the lyrics about?"),
            remainingQuickPrompts(prompts, asked = listOf("who sampled this song?")),
        )
    }

    private fun note(id: String, at: Long?) = ScrapNoteLine(noteId = id, songId = "s", positionMs = at, text = id)

    private fun label(row: ScrapNoteRow): String = when (row) {
        is ScrapNoteRow.Lyric -> row.line.text
        is ScrapNoteRow.Note -> "note ${row.note.noteId}"
        is ScrapNoteRow.Draft -> "draft"
        is ScrapNoteRow.Gap -> "gap"
    }
}
