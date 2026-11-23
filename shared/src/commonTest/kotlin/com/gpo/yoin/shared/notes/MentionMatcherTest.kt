package com.gpo.yoin.shared.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MentionMatcherTest {

    private fun track(title: String, artist: String? = null, album: String? = null) =
        NoteMention(MentionKind.Track, "spotify", "sp1", title, artist, album)

    @Test
    fun should_matchByName_when_creditsCaseAndEditionTagsDiffer() {
        val mention = track("Stay (with Justin Bieber)", "The Kid LAROI, Justin Bieber", "F*CK LOVE 3")
        assertTrue(MentionMatcher.namesMatchTrack(mention, "STAY", "The Kid LAROI & Justin Bieber", null))
        assertTrue(MentionMatcher.namesMatchTrack(track("他夏了夏天", "苏打绿"), "他夏了夏天", "苏打绿", "夏/狂热"))
    }

    @Test
    fun should_notMatch_when_artistAlbumOrVersionDisagree() {
        assertFalse(MentionMatcher.namesMatchTrack(track("Intro", "The xx"), "Intro", "M83", null))
        assertFalse(MentionMatcher.namesMatchTrack(track("Song", "A", "Album One"), "Song", "A", "Album Two"))
        assertFalse(MentionMatcher.namesMatchTrack(track("Song (Live)", "A"), "Song", "A", null))
        assertFalse(MentionMatcher.namesMatchAlbum(track("Charm", "Clairo"), "Charm", "Clairo"))
    }

    @Test
    fun should_matchAlbumsArtistsAndTrackAlbums_byName() {
        val album = NoteMention(MentionKind.Album, "applemusic", "1", "Heaven or Las Vegas", "Cocteau Twins")
        assertTrue(MentionMatcher.namesMatchAlbum(album, "Heaven Or Las Vegas", "Cocteau Twins"))
        val artist = NoteMention(MentionKind.Artist, "spotify", "2", "The Hannah Diamond")
        assertTrue(MentionMatcher.namesMatchArtist(artist, "hannah diamond"))
        assertTrue(MentionMatcher.trackMentionOnAlbum(track("Andromeda", "Weyes Blood", "Titanic Rising"), "Titanic Rising", "Weyes Blood"))
        assertFalse(MentionMatcher.trackMentionOnAlbum(track("Andromeda", "Weyes Blood"), "Titanic Rising", "Weyes Blood"))
    }

    @Test
    fun should_matchIdsExactly() {
        assertTrue(MentionMatcher.matchesId(track("x"), "spotify", "sp1"))
        assertFalse(MentionMatcher.matchesId(track("x"), "applemusic", "sp1"))
    }

    @Test
    fun should_dropCreditsFromTheSearchPrefix() {
        assertEquals("Stay", MentionMatcher.searchPrefix("Stay (feat. Justin Bieber)"))
        assertEquals(null, MentionMatcher.searchPrefix("   "))
    }
}
