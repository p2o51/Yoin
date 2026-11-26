package com.gpo.yoin.data.local

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ContentRating
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Jump Back In's persisted pools keep the explicit / clean mark of their albums and songs. */
class HomeGridPoolContentRatingTest {

    @Test
    fun should_keepTheMark_when_poolRowsRoundTrip() {
        val album = Album(
            id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "1"),
            name = "DAMN.",
            artist = "Kendrick Lamar",
            artistId = null,
            coverArt = null,
            songCount = 14,
            durationSec = null,
            year = 2017,
            genre = null,
            contentRating = ContentRating.Explicit,
        )
        val track = Track(
            id = MediaId(MediaId.PROVIDER_SPOTIFY, "t1"),
            title = "HUMBLE.",
            artist = "Kendrick Lamar",
            artistId = null,
            album = "DAMN.",
            albumId = null,
            coverArt = null,
            durationSec = 177,
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null,
            contentRating = ContentRating.Clean,
        )

        assertEquals(ContentRating.Explicit, album.toGridPoolRow("p", 0, 1L).toAlbum().contentRating)
        assertEquals(ContentRating.Clean, track.toGridPoolRow("p", 0, 1L).toTrack().contentRating)
        assertNull(album.copy(contentRating = null).toGridPoolRow("p", 0, 1L).toAlbum().contentRating)
    }
}
