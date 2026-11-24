package com.gpo.yoin.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RediscoverNoteSnippetTest {

    @Test
    fun should_showTheNotesWords_notItsMarkup() {
        val note = "# 副歌\n**这一句**像 [@New York](yoin:track/subsonic:t1?a=St.%20Vincent) 的 _尾奏_"

        val snippet = rediscoverNoteSnippet(note)!!

        assertEquals(false, snippet.contains("yoin:"))
        assertEquals(false, snippet.contains("**"))
        assertEquals(false, snippet.contains("[@"))
        assertEquals(true, snippet.contains("New York"))
        assertEquals(true, snippet.contains("这一句"))
    }

    @Test
    fun should_haveNoSnippet_forABlankNote() {
        assertNull(rediscoverNoteSnippet(null))
        assertNull(rediscoverNoteSnippet("  "))
    }
}
