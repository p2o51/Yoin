package com.gpo.yoin.data.openin

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.shared.openin.YoinMusicLinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenInMatcherTest {

    private fun track(id: String, title: String, artist: String, duration: Int?, isrc: String? = null) = Track(
        id = MediaId.subsonic(id),
        title = title,
        artist = artist,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = duration,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        extras = isrc?.let { mapOf(Track.EXTRA_ISRC to it) }.orEmpty(),
    )

    private val linkSong = OpenInMatcher.sourceTrack(
        MusicLinkMetadata(YoinMusicLinkKind.Track, "Blinding Lights", "The Weeknd", "After Hours", "USUG11904206", 200),
    )

    @Test
    fun should_leadWithIsrcMatch_when_accountKnowsTheCode() {
        val isrcMatch = track("isrc", "Blinding Lights", "The Weeknd", 201, isrc = "USUG11904206")
        val searched = listOf(track("live", "Blinding Lights (Live)", "The Weeknd", 230), isrcMatch)

        val ranking = OpenInMatcher.rankTracks(linkSong, byIsrc = listOf(isrcMatch), searched = searched)

        assertEquals(isrcMatch, ranking.items.first())
        assertTrue(ranking.exact)
        assertEquals(2, ranking.items.size)
    }

    @Test
    fun should_beExact_when_trackMatcherIsConfident() {
        val same = track("1", "Blinding Lights", "The Weeknd", 200)
        val cover = track("2", "Blinding Lights", "Some Cover Band", 199)

        val ranking = OpenInMatcher.rankTracks(linkSong, byIsrc = emptyList(), searched = listOf(cover, same))

        assertEquals(same, ranking.items.first())
        assertTrue(ranking.exact)
    }

    @Test
    fun should_listWithoutClaimingAMatch_when_onlyOtherVersionsExist() {
        val live = track("1", "Blinding Lights - Live", "The Weeknd", 260)
        val remix = track("2", "Blinding Lights (Remix)", "The Weeknd", 215)

        val ranking = OpenInMatcher.rankTracks(linkSong, byIsrc = emptyList(), searched = listOf(live, remix))

        assertFalse(ranking.exact)
        assertEquals(setOf(live, remix), ranking.items.toSet())
    }

    @Test
    fun should_keepUnscoredResultsLast_when_searchFoundOthers() {
        val unrelated = track("x", "Save Your Tears", "The Weeknd", 215)
        val close = track("1", "Blinding Lights", "The Weeknd", 200)

        val ranking = OpenInMatcher.rankTracks(linkSong, emptyList(), listOf(unrelated, close))

        assertEquals(listOf(close, unrelated), ranking.items)
    }

    @Test
    fun should_rankAlbumsByNameThenArtist_when_linkIsAnAlbum() {
        fun album(id: String, name: String, artist: String) =
            Album(MediaId.subsonic(id), name, artist, null, null, null, null, null, null)
        val deluxe = album("d", "After Hours (Deluxe)", "The Weeknd")
        val exact = album("e", "After Hours", "The Weeknd")
        val other = album("o", "After Hours", "Someone Else")

        val ranking = OpenInMatcher.rankAlbums("After Hours", "The Weeknd", listOf(other, deluxe, exact))

        assertTrue(ranking.items.indexOf(exact) < ranking.items.indexOf(other))
        assertTrue(ranking.exact)
    }

    @Test
    fun should_notBeExact_when_noAlbumIsClose() {
        val album = Album(MediaId.subsonic("1"), "Starboy", "The Weeknd", null, null, null, null, null, null)

        assertFalse(OpenInMatcher.rankAlbums("After Hours", "The Weeknd", listOf(album)).exact)
    }

    @Test
    fun should_rankArtistsByName_when_linkIsAnArtist() {
        val weeknd = Artist(MediaId.subsonic("1"), "The Weeknd", null, null)
        val tribute = Artist(MediaId.subsonic("2"), "The Weeknd Tribute Band", null, null)

        val ranking = OpenInMatcher.rankArtists("The Weeknd", listOf(tribute, weeknd))

        assertEquals(weeknd, ranking.items.first())
        assertTrue(ranking.exact)
    }
}
