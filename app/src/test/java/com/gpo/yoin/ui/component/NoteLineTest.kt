package com.gpo.yoin.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLineTest {

    @Test
    fun should_lightOnlyCurrentLine_when_pageHasOne() {
        assertEquals(1f, noteLineAlpha(isActive = true, distance = 0, emphasis = NoteLineEmphasis.Page))
        // The page keeps every other line legible — one weak step, not a falloff.
        assertEquals(0.62f, noteLineAlpha(isActive = false, distance = 1, emphasis = NoteLineEmphasis.Page))
        assertEquals(0.62f, noteLineAlpha(isActive = false, distance = 9, emphasis = NoteLineEmphasis.Page))
    }

    @Test
    fun should_keepPageReadable_when_playheadIsInsideNoNote() {
        assertEquals(0.86f, noteLineAlpha(isActive = false, distance = null, emphasis = NoteLineEmphasis.Page))
    }

    @Test
    fun should_fallOffWithDistance_when_glancing() {
        assertEquals(0.55f, noteLineAlpha(isActive = false, distance = 1, emphasis = NoteLineEmphasis.Glance))
        assertEquals(0.40f, noteLineAlpha(isActive = false, distance = 2, emphasis = NoteLineEmphasis.Glance))
        assertEquals(0.28f, noteLineAlpha(isActive = false, distance = 3, emphasis = NoteLineEmphasis.Glance))
        assertEquals(0.28f, noteLineAlpha(isActive = false, distance = null, emphasis = NoteLineEmphasis.Glance))
    }

    @Test
    fun should_openBar_when_focusedOrHoldingWords() {
        assertFalse(noteWriteBarOpen(focused = false, draftText = ""))
        assertTrue(noteWriteBarOpen(focused = true, draftText = ""))
        // The keyboard went down mid-sentence: the words stay on show.
        assertTrue(noteWriteBarOpen(focused = false, draftText = "half a thought"))
    }

    @Test
    fun should_collapseBar_when_keyboardDismissedOverEmptyDraft() {
        assertTrue(shouldCollapseNoteWriteBar(imeWasShown = true, imeVisible = false, draftEmpty = true))
    }

    @Test
    fun should_keepBarOpen_when_wordsWaitOrKeyboardNeverShowed() {
        assertFalse(shouldCollapseNoteWriteBar(imeWasShown = true, imeVisible = false, draftEmpty = false))
        // A hardware keyboard (or focus before the IME rises) never collapses it.
        assertFalse(shouldCollapseNoteWriteBar(imeWasShown = false, imeVisible = false, draftEmpty = true))
        assertFalse(shouldCollapseNoteWriteBar(imeWasShown = true, imeVisible = true, draftEmpty = true))
    }

    @Test
    fun should_offerRealign_when_playheadWanderedTwoSeconds() {
        assertFalse(showNoteRealignHint(anchorMs = 91_000L, positionMs = 92_000L))
        assertTrue(showNoteRealignHint(anchorMs = 91_000L, positionMs = 93_000L))
        // Seeking back counts too.
        assertTrue(showNoteRealignHint(anchorMs = 91_000L, positionMs = 60_000L))
        assertFalse(showNoteRealignHint(anchorMs = null, positionMs = 60_000L))
    }

    @Test
    fun should_sayHowLongAgo_when_noteIsRecent() {
        val now = 10_000_000_000L
        assertEquals("刚刚", formatNoteDate(now - 30_000L, now))
        assertEquals("5 分钟前", formatNoteDate(now - 5 * 60_000L, now))
        assertEquals("3 小时前", formatNoteDate(now - 3 * 3_600_000L, now))
        assertEquals("2 天前", formatNoteDate(now - 2 * 86_400_000L, now))
    }
}
