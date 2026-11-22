package com.gpo.yoin.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumStickyTest {

    @Test
    fun should_writeANewNote_onlyWithWords() {
        assertEquals(ScrapEdit.NewAlbumNote("周日下午"), stickySave(StickyEditing.NEW, "  周日下午 "))
        assertNull(stickySave(StickyEditing.NEW, "   "))
    }

    @Test
    fun should_editOrDeleteAnExistingNote() {
        assertEquals(ScrapEdit.AlbumNoteText("an1", "new words"), stickySave("an1", "new words "))
        // cleared and saved: a delete (the page offers Undo)
        assertEquals(ScrapEdit.AlbumNoteText("an1", ""), stickySave("an1", "  "))
    }

    @Test
    fun should_openOneStickyAtATime() {
        val editing = StickyEditing()
        editing.startNew()
        assertEquals(StickyEditing.NEW, editing.open)
        assertEquals("", editing.editor?.text)
        editing.startEdit(ScrapSticky("an1", "words", 1f))
        assertEquals("an1", editing.open)
        assertEquals("words", editing.editor?.text)
        editing.close()
        assertNull(editing.open)
        assertNull(editing.editor)
    }

    @Test
    fun should_markEachMentionNameInTheSnippet() {
        // only the mention's own words: the same word elsewhere in the snippet stays unmarked
        val snippet = "Intro 像 Intro"
        val marked = highlightMentions(snippet, listOf(8 until 13), androidx.compose.ui.graphics.Color.Yellow)
        assertEquals(1, marked.spanStyles.size)
        assertEquals(8, marked.spanStyles.single().start)
        assertEquals(13, marked.spanStyles.single().end)
    }
}
