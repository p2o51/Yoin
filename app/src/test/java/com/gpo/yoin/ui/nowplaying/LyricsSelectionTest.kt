package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSelectionTest {

    private val lyrics = listOf(
        LyricLine(startMs = 1_000L, text = "one", translation = "一"),
        LyricLine(startMs = 2_000L, text = "two", translation = null),
        LyricLine(startMs = 3_000L, text = "three", translation = "三"),
    )

    @Test
    fun should_returnPickedLinesInLyricOrder_when_tappedOutOfOrder() {
        val lines = selectedLyricLines(lyrics, setOf(2, 0))

        assertEquals(listOf("one", "three"), lines.map { it.text })
    }

    @Test
    fun should_dropStaleIndices_when_theLyricsGotShorter() {
        assertEquals(listOf("two"), selectedLyricLines(lyrics, setOf(1, 7)).map { it.text })
    }

    @Test
    fun should_copyOneLyricPerLine_when_translationIsHidden() {
        val text = lyricsClipboardText(selectedLyricLines(lyrics, setOf(0, 1, 2)), includeTranslation = false)

        assertEquals("one\ntwo\nthree", text)
    }

    @Test
    fun should_copyEachTranslationUnderItsLine_when_translationIsShowing() {
        val text = lyricsClipboardText(selectedLyricLines(lyrics, setOf(0, 1, 2)), includeTranslation = true)

        assertEquals("one\n一\ntwo\nthree\n三", text)
    }

    @Test
    fun should_toggleLinesOnlyWhileSelecting_when_tapped() {
        val selection = LyricsSelectionState()
        selection.toggle(1)
        assertTrue(selection.selected.isEmpty())

        selection.enter()
        selection.toggle(1)
        selection.toggle(2)
        selection.toggle(1)
        assertEquals(setOf(2), selection.selected)
    }

    @Test
    fun should_openShareOnlyWithAPick_when_shareIsRequested() {
        val selection = LyricsSelectionState()
        selection.enter()
        selection.openShare()
        assertFalse(selection.sharing)

        selection.toggle(0)
        selection.openShare()
        assertTrue(selection.sharing)

        selection.closeShare()
        assertFalse(selection.sharing)
        assertTrue(selection.active)
    }

    @Test
    fun should_clearEverything_when_leavingSelectMode() {
        val selection = LyricsSelectionState()
        selection.enter()
        selection.toggle(0)
        selection.openShare()

        selection.exit()

        assertFalse(selection.active)
        assertFalse(selection.sharing)
        assertTrue(selection.selected.isEmpty())
    }

    @Test
    fun should_labelTheCount_when_linesArePicked() {
        assertEquals("Tap lines to select", lyricsSelectionLabel(0))
        assertEquals("1 line selected", lyricsSelectionLabel(1))
        assertEquals("3 lines selected", lyricsSelectionLabel(3))
    }

    @Test
    fun should_flagTheCap_when_theRunIsFullOrATapWasRefused() {
        assertEquals("15 lines selected · max", lyricsSelectionLabel(15))
        assertEquals("Up to 15 lines at a time", lyricsSelectionLabel(15, limitNudge = true))
    }

    // --- L6: Spotify's contiguous run, spotoolfy's 15-line cap ---

    @Test
    fun should_startARun_when_nothingIsPicked() {
        assertEquals(LyricSelectionTap(4..4), nextLyricSelection(null, 4))
    }

    @Test
    fun should_growTheRun_when_theLineJustAboveOrBelowIsTapped() {
        assertEquals(4..6, nextLyricSelection(4..5, 6).range)
        assertEquals(3..5, nextLyricSelection(4..5, 3).range)
    }

    @Test
    fun should_letGoOfThatEnd_when_anEndOfTheRunIsTapped() {
        assertEquals(5..7, nextLyricSelection(4..7, 4).range)
        assertEquals(4..6, nextLyricSelection(4..7, 7).range)
    }

    @Test
    fun should_clearThePick_when_itsOnlyLineIsTapped() {
        assertNull(nextLyricSelection(4..4, 4).range)
    }

    @Test
    fun should_endTheRunThere_when_aLineInsideItIsTapped() {
        assertEquals(4..6, nextLyricSelection(4..9, 6).range)
    }

    @Test
    fun should_startOver_when_aLineAwayFromTheRunIsTapped() {
        assertEquals(12..12, nextLyricSelection(4..6, 12).range)
        assertEquals(0..0, nextLyricSelection(4..6, 0).range)
    }

    @Test
    fun should_refuseToGrow_when_theRunIsFull() {
        val full = 0 until MaxSelectedLyricLines

        val tap = nextLyricSelection(full, MaxSelectedLyricLines)

        assertTrue(tap.hitLimit)
        assertEquals(full, tap.range)
        // Shrinking or starting over is still allowed at the cap.
        assertFalse(nextLyricSelection(full, full.last).hitLimit)
        assertEquals(30..30, nextLyricSelection(full, 30).range)
    }

    @Test
    fun should_keepOneContiguousRun_when_tappedThroughTheState() {
        val selection = LyricsSelectionState(maxLines = 3)
        selection.enter()

        assertTrue(selection.toggle(5))
        assertTrue(selection.toggle(6))
        assertTrue(selection.toggle(4))
        assertEquals(setOf(4, 5, 6), selection.selected)

        assertFalse(selection.toggle(7))
        assertEquals(4..6, selection.range)

        assertTrue(selection.toggle(10))
        assertEquals(setOf(10), selection.selected)
    }

    @Test
    fun should_classifyEveryLine_when_aRunIsPicked() {
        val run = 4..6

        assertEquals(LyricSelectionRole.Idle, lyricSelectionRole(2, null))
        assertEquals(LyricSelectionRole.Picked, lyricSelectionRole(5, run))
        assertEquals(LyricSelectionRole.Reachable, lyricSelectionRole(3, run))
        assertEquals(LyricSelectionRole.Reachable, lyricSelectionRole(7, run))
        assertEquals(LyricSelectionRole.Outside, lyricSelectionRole(8, run))
        assertEquals(LyricSelectionRole.Outside, lyricSelectionRole(7, run, maxLines = 3))
    }

    @Test
    fun should_spanTheRun_when_givenThePickedSet() {
        assertNull(lyricSelectionRange(emptySet()))
        assertEquals(3..5, lyricSelectionRange(setOf(5, 3, 4)))
    }

    @Test
    fun should_appendTheCredit_when_copyingASongWithATitle() {
        val text = lyricsClipboardText(
            selectedLyricLines(lyrics, setOf(0, 1)),
            includeTranslation = true,
            songTitle = "That's So True",
            artist = "Gracie Abrams",
        )

        assertEquals("one\n一\ntwo\n\n— That's So True · Gracie Abrams", text)
    }

    @Test
    fun should_creditTheTitleAlone_when_theArtistIsBlank() {
        assertEquals("Song", lyricsCredit(" Song ", "  "))
        assertNull(lyricsCredit("  ", "Artist"))
        assertEquals("one", lyricsClipboardText(lyrics.take(1), includeTranslation = false, songTitle = " "))
    }

    @Test
    fun should_countTheLines_when_confirmingACopy() {
        assertEquals("Copied 1 line", lyricsCopiedMessage(1))
        assertEquals("Copied 4 lines", lyricsCopiedMessage(4))
    }
}
