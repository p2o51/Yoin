package com.gpo.yoin.ui.nowplaying

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("Select lines", lyricsSelectionLabel(0))
        assertEquals("1 / 15", lyricsSelectionLabel(1))
        assertEquals("3 / 15", lyricsSelectionLabel(3))
        assertEquals("Select lines", lyricsSelectionLabel(0, resources = resources))
        assertEquals("1 / 15", lyricsSelectionLabel(1, resources = resources))
        assertEquals("3 / 15", lyricsSelectionLabel(3, resources = resources))
    }

    @Test
    fun should_flagTheCap_when_theRunIsFullOrATapWasRefused() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("15 / 15", lyricsSelectionLabel(15))
        assertEquals("15 / 15", lyricsSelectionLabel(15, limitNudge = true))
        assertEquals("15 / 15", lyricsSelectionLabel(15, resources = resources))
        assertEquals("15 / 15", lyricsSelectionLabel(15, limitNudge = true, resources = resources))
    }

    // --- W1 (owner 2026-10-05): spotoolfy's free pick, its 15-line cap ---

    @Test
    fun should_addALine_when_anUnpickedLineIsTapped() {
        assertEquals(LyricSelectionTap(setOf(4)), nextLyricSelection(emptySet(), 4))
        assertEquals(setOf(4, 9), nextLyricSelection(setOf(4), 9).selected)
    }

    @Test
    fun should_letGo_when_aPickedLineIsTapped() {
        assertEquals(setOf(4, 6), nextLyricSelection(setOf(4, 5, 6), 5).selected)
        assertEquals(emptySet<Int>(), nextLyricSelection(setOf(4), 4).selected)
    }

    @Test
    fun should_refuseToAdd_when_thePickIsFull() {
        val full = (0 until MaxSelectedLyricLines).map { it * 2 }.toSet()

        val tap = nextLyricSelection(full, 1)

        assertTrue(tap.hitLimit)
        assertEquals(full, tap.selected)
        // Letting a line go is still allowed at the cap.
        assertFalse(nextLyricSelection(full, 0).hitLimit)
    }

    @Test
    fun should_keepAnyLines_when_tappedThroughTheState() {
        val selection = LyricsSelectionState(maxLines = 3)
        selection.enter()

        assertTrue(selection.toggle(5))
        assertTrue(selection.toggle(12))
        assertTrue(selection.toggle(1))
        assertEquals(setOf(1, 5, 12), selection.selected)

        assertFalse(selection.toggle(7))
        assertEquals(setOf(1, 5, 12), selection.selected)

        assertTrue(selection.toggle(5))
        assertEquals(setOf(1, 12), selection.selected)
    }

    @Test
    fun should_classifyEveryLine_when_linesArePicked() {
        val picked = setOf(4, 9)

        assertEquals(LyricSelectionRole.Idle, lyricSelectionRole(2, emptySet()))
        assertEquals(LyricSelectionRole.Picked, lyricSelectionRole(9, picked))
        assertEquals(LyricSelectionRole.Open, lyricSelectionRole(20, picked))
        assertEquals(LyricSelectionRole.Outside, lyricSelectionRole(20, picked, maxLines = 2))
    }

    @Test
    fun should_startANewPassage_when_thePickSkipsLines() {
        val song = (0 until 10).map { LyricLine(startMs = 1_000L * it, text = "line $it") }

        // picked 1,2 | 5 | 7,8 → positions 2 and 3 open passages; stale 40 drops out
        assertEquals(setOf(2, 3), lyricPassageStarts(song, setOf(8, 1, 5, 2, 7, 40)))
        assertEquals(emptySet<Int>(), lyricPassageStarts(song, setOf(3, 4, 5)))
    }

    @Test
    fun should_copyABlankLineBetweenPassages_when_thePickSkipsLines() {
        val text = lyricsClipboardText(
            selectedLyricLines(lyrics, setOf(0, 2)),
            includeTranslation = true,
            songTitle = "Song",
            passageStarts = lyricPassageStarts(lyrics, setOf(0, 2)),
        )

        assertEquals("one\n一\n\nthree\n三\n\n— Song", text)
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
