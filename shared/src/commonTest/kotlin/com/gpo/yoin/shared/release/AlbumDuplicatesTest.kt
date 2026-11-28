package com.gpo.yoin.shared.release

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fixtures shaped like what Spotify's `GET /artists/{id}/albums` and search answer (name, first artist, release
 * date, total_tracks, album_type; no rating, no length) beside full album reads (rating and length known).
 */
class AlbumDuplicatesTest {

    private fun copy(
        name: String,
        date: String? = "2004-02-10",
        tracks: Int? = 21,
        artist: String? = "Kanye West",
        kind: ReleaseType? = ReleaseType.Album,
        rating: ContentRating? = null,
        duration: Int? = null,
    ) = AlbumCopy(name, artist, date, tracks, duration, rating, kind)

    private fun same(a: AlbumCopy, b: AlbumCopy) = AlbumDuplicates.likelySameRelease(a, b)

    @Test
    fun should_match_when_spotifyListsTheSameReleaseUnderTwoIds() {
        assertTrue(same(copy("The College Dropout"), copy("The College Dropout")))
        // A full read beside a simplified one: what only one side knows doesn't count.
        assertTrue(same(copy("The College Dropout", rating = ContentRating.Explicit, duration = 4_563), copy("The College Dropout")))
    }

    @Test
    fun should_keepApart_when_oneIsExplicitAndTheOtherClean() {
        assertFalse(
            same(
                copy("The Life of Pablo", "2016-02-14", 20, rating = ContentRating.Explicit),
                copy("The Life of Pablo", "2016-02-14", 20, rating = ContentRating.Clean),
            ),
        )
    }

    @Test
    fun should_keepEditionsApart_when_theNamesSayDeluxeRemasterOrVersion() {
        assertFalse(same(copy("Donda", "2021-08-29", 27), copy("Donda (Deluxe)", "2021-08-29", 27)))
        assertFalse(
            same(
                copy("Abbey Road", "1969-09-26", 17, artist = "The Beatles"),
                copy("Abbey Road (Remastered 2009)", "1969-09-26", 17, artist = "The Beatles"),
            ),
        )
        assertFalse(
            same(
                copy("Fearless", "2008-11-11", 13, artist = "Taylor Swift"),
                copy("Fearless (Taylor's Version)", "2021-04-09", 26, artist = "Taylor Swift"),
            ),
        )
    }

    @Test
    fun should_keepApart_when_theSameNameCameOutOnAnotherDayOrWithMoreTracks() {
        assertFalse(same(copy("folklore", "2020-07-24", 16), copy("folklore", "2020-08-18", 17)))
        assertFalse(same(copy("Jesus Is King", "2019-10-25", 11), copy("Jesus Is King", "2019-10-25", 12)))
    }

    @Test
    fun should_matchDatesAtTheSharedPrecision() {
        assertTrue(same(copy("Blonde", date = "2016"), copy("Blonde", date = "2016-08-20")))
        assertFalse(same(copy("Blonde", date = "2016-08-20"), copy("Blonde", date = "2016-08-21")))
        assertFalse(same(copy("Blonde", date = "2016-08-20"), copy("Blonde", date = null)))
        assertTrue(same(copy("Blonde", date = null), copy("Blonde", date = null)))
        assertTrue(same(copy("Blonde", date = "0000"), copy("Blonde", date = null)))
    }

    @Test
    fun should_keepApart_when_theArtistOrKindDiffers() {
        assertFalse(same(copy("Greatest Hits", "1981", 17, artist = "Queen"), copy("Greatest Hits", "1981", 17, artist = "ABBA")))
        assertFalse(
            same(
                copy("Heartless", "2008-11-04", 1, kind = ReleaseType.Single),
                copy("Heartless", "2008-11-04", 1, kind = ReleaseType.Album),
            ),
        )
    }

    @Test
    fun should_keepApart_when_theLengthsDifferBeyondTheSlack() {
        val a = copy("Kids See Ghosts", "2018-06-08", 7, duration = 1_438)
        assertTrue(same(a, a.copy(durationSec = 1_447)))
        assertFalse(same(a, a.copy(durationSec = 1_600)))
    }

    @Test
    fun should_ignoreCaseWidthQuotesAndSpaces_when_comparingNames() {
        assertTrue(same(copy("Yandhi — It's Here"), copy("  YANDHI – It’s   Here ")))
        assertTrue(same(copy("ＴＨＥ　ＢＯＯＫ", artist = "YOASOBI"), copy("THE BOOK", artist = "yoasobi")))
        assertEquals("the book ii", AlbumDuplicates.normalizedName("  THE\tBOOK  ＩＩ "))
    }

    @Test
    fun should_neverMatch_when_theNameIsBlank() {
        assertFalse(same(copy(""), copy("")))
        assertFalse(same(copy("  "), copy("  ")))
    }
}
