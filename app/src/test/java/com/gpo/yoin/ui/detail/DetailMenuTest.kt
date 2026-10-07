package com.gpo.yoin.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailMenuTest {

    @Test
    fun should_nameTheProvidersApp_when_theLinkIsTheirs() {
        assertEquals("Open in Spotify", openInLabel("https://open.spotify.com/album/abc"))
        assertEquals("Open in Apple Music", openInLabel("https://music.apple.com/jp/album/123"))
        assertNull(openInLabel("https://example.com/album/1"))
        assertNull(openInLabel(null))
    }

    @Test
    fun should_putTheLinkUnderTheTitle_when_sharingAPageWithALink() {
        assertEquals(
            "Album – Artist\nhttps://open.spotify.com/album/abc",
            detailShareText("Album – Artist", "https://open.spotify.com/album/abc"),
        )
        assertEquals("Album – Artist", detailShareText("Album – Artist", null))
    }

    @Test
    fun should_countTheSongs_when_confirmingAQueueAction() {
        assertEquals("Added 12 songs to the queue", queuedMessage(12, next = false))
        assertEquals("1 song will play next", queuedMessage(1, next = true))
    }
}
