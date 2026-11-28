package com.gpo.yoin.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The album header's artist link (owner 2026-11-27: "tapping the artist name under the album title doesn't open
 * the artist page"). Android's header drew the name as plain text; these pin which pages it now opens.
 */
class AlbumArtistLinksTest {

    private fun album(
        id: MediaId,
        artist: String? = "Kanye West",
        artistId: MediaId? = null,
        credits: List<ArtistCredit> = emptyList(),
        tracks: List<Track> = emptyList(),
    ) = Album(
        id = id, name = "The College Dropout", artist = artist, artistId = artistId, coverArt = null,
        songCount = tracks.size, durationSec = null, year = 2004, genre = null, tracks = tracks,
        artistCredits = credits,
    )

    private fun track(id: MediaId, artist: String?, artistId: MediaId?, extras: Map<String, String> = emptyMap()) = Track(
        id = id, title = "Song", artist = artist, artistId = artistId, album = null, albumId = null, coverArt = null,
        durationSec = null, trackNumber = null, year = null, genre = null, userRating = null, extras = extras,
    )

    private val am = MediaId.PROVIDER_APPLE_MUSIC

    @Test
    fun should_openTheAlbumsArtist_when_theServiceNamesIt() {
        // Apple Music catalogue album: the `artists` relationship's one artist.
        val links = albumArtistLinks(album(MediaId(am, "1440873498"), artistId = MediaId(am, "2715720")))
        assertEquals(listOf(ArtistCredit("Kanye West", MediaId(am, "2715720"))), links)
    }

    @Test
    fun should_offerEveryCreditedArtist_when_theAlbumHasSeveral() {
        // Spotify "Watch the Throne": album artists [JAY-Z, Kanye West]; the header shows the first.
        val jay = MediaId.spotify("3nFkdlSjzX9mRTtwJOzDYB")
        val ye = MediaId.spotify("5K4W6rqBFWDnAN6FQUkS6x")
        val links = albumArtistLinks(
            album(
                MediaId.spotify("2P2Xwvh2xWXIZ1OWY9S9o5"),
                artist = "JAY-Z",
                artistId = jay,
                credits = listOf(ArtistCredit("JAY-Z", jay), ArtistCredit("Kanye West", ye)),
            ),
        )
        assertEquals(listOf(jay, ye), links.map { it.id })
        assertEquals(listOf("JAY-Z", "Kanye West"), links.map { it.name })
    }

    @Test
    fun should_fallBackToTheTracksArtist_when_appleMusicLeavesTheAlbumsArtistOut() {
        // A library album Apple answered without its `artists` relationship: its songs still carry theirs.
        val artist = MediaId(am, "library:r.4GZQ1aq")
        val links = albumArtistLinks(
            album(
                MediaId(am, "library:l.Kz9a"),
                tracks = listOf(
                    track(MediaId(am, "library:i.1"), "Kanye West", artist),
                    track(MediaId(am, "library:i.2"), "Kanye West feat. Syleena Johnson", MediaId(am, "library:r.x")),
                    track(MediaId(am, "library:i.3"), "Kanye West", artist),
                ),
            ),
        )
        assertEquals(listOf(ArtistCredit("Kanye West", artist)), links)
    }

    @Test
    fun should_useTheOneIdEveryTrackShares_when_noTrackIsCreditedByTheAlbumsName() {
        val server = MediaId.subsonic("ar-1")
        val links = albumArtistLinks(
            album(
                MediaId.subsonic("al-1"),
                artist = "カネ",
                tracks = listOf(track(MediaId.subsonic("s1"), "Kanye", server), track(MediaId.subsonic("s2"), "Ye", server)),
            ),
        )
        assertEquals(listOf(server), links.map { it.id })
    }

    @Test
    fun should_readOpenSubsonicCredits_andNotRepeatTheAlbumArtistUnderAnotherId() {
        val links = albumArtistLinks(
            album(
                MediaId.subsonic("al-2"),
                artist = "Kanye West",
                artistId = MediaId.subsonic("aa-kanye"),
                credits = listOf(
                    ArtistCredit("Kanye West", MediaId.subsonic("ar-kanye")),
                    ArtistCredit("Kid Cudi", MediaId.subsonic("ar-cudi")),
                ),
            ),
        )
        assertEquals(listOf("ar-kanye", "ar-cudi"), links.map { it.id!!.rawId })
    }

    @Test
    fun should_beEmpty_when_noPageIsKnown() {
        val links = albumArtistLinks(
            album(
                MediaId.spotify("x"),
                tracks = listOf(
                    track(MediaId.spotify("t1"), "A", MediaId.spotify("a")),
                    track(MediaId.spotify("t2"), "B", MediaId.spotify("b")),
                ),
            ),
        )
        assertTrue(links.isEmpty())
    }
}
