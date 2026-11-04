package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.album.AlbumScrapbookAboutRef
import com.gpo.yoin.data.local.SongAboutEntry
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
        assertEquals("b", ScrapbookFocusRequest.takeFor(listOf("a", "b"))?.songId)
        assertNull(ScrapbookFocusRequest.takeFor(listOf("a", "b")))
    }

    @Test
    fun should_waitForItsAlbum_when_anotherAlbumAsksFirst() {
        ScrapbookFocusRequest.request("c")
        assertNull(ScrapbookFocusRequest.takeFor(listOf("a", "b")))
        assertEquals("c", ScrapbookFocusRequest.takeFor(listOf("c"))?.songId)
    }

    @Test
    fun should_carryTheRowToEdit_when_nowPlayingAsksToManageIt() {
        val ref = AlbumScrapbookAboutRef("song", "artist", "album", SongAboutEntry.KIND_ASK, "who sampled it")
        ScrapbookFocusRequest.request("a", edit = ref)
        val taken = ScrapbookFocusRequest.takeFor(listOf("a"))
        assertEquals("a", taken?.songId)
        assertEquals(ref, taken?.edit)
    }
}
