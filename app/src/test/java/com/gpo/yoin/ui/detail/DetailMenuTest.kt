package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
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

    @Test
    fun should_leaveImportsOutOfTheQueue_when_aPageMixesThem() {
        val playable = track(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "1440857781"))
        val tracks = listOf(import("a"), playable, import("b"))

        assertEquals(listOf(playable), queueableSongs(tracks))
    }

    @Test
    fun should_stillConfirm_when_everySongOnThePageIsAnImport() {
        // 'Language' (22 of 22 imports): Add to queue used to do nothing without a word.
        val songs = queueableSongs(List(22) { import("i$it") })

        assertEquals(emptyList<Track>(), songs)
        assertEquals("Added 0 songs to the queue", queuedMessage(songs!!.size, next = false))
        assertEquals("0 songs will play next", queuedMessage(songs.size, next = true))
    }

    @Test
    fun should_sayNothing_when_thePageHasNoSongsYet() {
        assertNull(queueableSongs(emptyList()))
    }

    private fun import(id: String) = track(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:i.$id"))

    private fun track(id: MediaId) = Track(
        id = id,
        title = "Track $id",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = null,
        coverArt = null,
        durationSec = 180,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )
}
