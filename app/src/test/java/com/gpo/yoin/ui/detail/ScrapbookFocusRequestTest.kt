package com.gpo.yoin.ui.detail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrapbookFocusRequestTest {

    @After
    fun clear() {
        ScrapbookFocusRequest.takeFor(listOf("a", "b", "c"))
    }

    @Test
    fun should_beTakenOnce_when_theAlbumListsTheSong() {
        ScrapbookFocusRequest.request("b")
        assertEquals("b", ScrapbookFocusRequest.takeFor(listOf("a", "b")))
        assertNull(ScrapbookFocusRequest.takeFor(listOf("a", "b")))
    }

    @Test
    fun should_waitForItsAlbum_when_anotherAlbumAsksFirst() {
        ScrapbookFocusRequest.request("c")
        assertNull(ScrapbookFocusRequest.takeFor(listOf("a", "b")))
        assertEquals("c", ScrapbookFocusRequest.takeFor(listOf("c")))
    }
}
