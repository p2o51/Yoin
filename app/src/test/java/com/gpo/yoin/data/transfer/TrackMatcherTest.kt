package com.gpo.yoin.data.transfer

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {

    @Test
    fun should_match_when_titleArtistAndDurationAgree() {
        val source = track("s", "Bohemian Rhapsody - Remastered 2011", "Queen", 354)
        val candidate = track("t", "Bohemian Rhapsody", "Queen", 355)

        val decision = TrackMatcher.choose(source, listOf(candidate))

        assertEquals(MatchDecision.Matched(candidate, MatchMethod.Search), decision)
    }

    @Test
    fun should_ignoreFeatureCredits_when_comparingTitlesAndArtists() {
        val source = track("s", "Stay (with Justin Bieber)", "The Kid LAROI, Justin Bieber", 141)
        val candidate = track("t", "STAY", "The Kid LAROI & Justin Bieber", 142)

        val decision = TrackMatcher.choose(source, listOf(candidate))

        assertTrue(decision is MatchDecision.Matched)
    }

    @Test
    fun should_preferTheClosestDuration_when_twoCandidatesScoreAlike() {
        val source = track("s", "Lemon", "Kenshi Yonezu", 255)
        val far = track("far", "Lemon", "Kenshi Yonezu", 258)
        val near = track("near", "Lemon", "Kenshi Yonezu", 255)

        val decision = TrackMatcher.choose(source, listOf(far, near))

        assertEquals("near", (decision as MatchDecision.Matched).track.id.rawId)
    }

    @Test
    fun should_beUnsure_when_durationIsBeyondTheTolerance() {
        val source = track("s", "Hey Jude", "The Beatles", 431)
        // Same song, a different edit: 7 s off.
        val candidate = track("t", "Hey Jude", "The Beatles", 424)

        val decision = TrackMatcher.choose(source, listOf(candidate))

        assertTrue(decision is MatchDecision.Ambiguous)
    }

    @Test
    fun should_rejectCandidate_when_durationIsFarOff() {
        val source = track("s", "Hey Jude", "The Beatles", 431)
        val candidate = track("t", "Hey Jude", "The Beatles", 200)

        assertEquals(MatchDecision.NotFound, TrackMatcher.choose(source, listOf(candidate)))
    }

    @Test
    fun should_notMatch_when_artistDiffers() {
        val source = track("s", "Hallelujah", "Jeff Buckley", 414)
        val cover = track("t", "Hallelujah", "Pentatonix", 414)

        assertEquals(MatchDecision.NotFound, TrackMatcher.choose(source, listOf(cover)))
    }

    @Test
    fun should_keepLiveAndRemixApart_when_titlesDifferOnlyByThem() {
        val source = track("s", "Yellow", "Coldplay", 266)
        val live = track("t", "Yellow - Live in Buenos Aires", "Coldplay", 266)

        val decision = TrackMatcher.choose(source, listOf(live))

        assertTrue(decision !is MatchDecision.Matched)
    }

    @Test
    fun should_matchCjkTitles_when_widthAndCaseDiffer() {
        val source = track("s", "夜に駆ける", "YOASOBI", 261)
        val candidate = track("t", "夜に駆ける", "Yoasobi", 261)

        assertTrue(TrackMatcher.choose(source, listOf(candidate)) is MatchDecision.Matched)
    }

    @Test
    fun should_match_when_durationIsUnknownButTitleAndArtistAreExact() {
        val source = track("s", "Creep", "Radiohead", null)
        val candidate = track("t", "Creep", "Radiohead", 238)

        assertTrue(TrackMatcher.choose(source, listOf(candidate)) is MatchDecision.Matched)
    }

    @Test
    fun should_returnNotFound_when_thereAreNoCandidates() {
        assertEquals(MatchDecision.NotFound, TrackMatcher.choose(track("s", "Creep", "Radiohead", 238), emptyList()))
    }

    @Test
    fun should_preferTheSameAlbum_when_isrcNamesSeveralTracks() {
        val source = track("s", "Entropy", "Beach Bunny", 221, album = "Emotional Creature")
        val compilation = track("c", "Entropy", "Beach Bunny", 221, album = "Indie Hits 2022")
        val original = track("o", "Entropy", "Beach Bunny", 221, album = "Emotional Creature")

        val decision = TrackMatcher.chooseByIsrc(source, listOf(compilation, original))

        assertEquals(MatchDecision.Matched(original, MatchMethod.Isrc), decision)
    }

    @Test
    fun should_skipIsrcCandidate_when_itsDurationIsFarOff() {
        val source = track("s", "Entropy", "Beach Bunny", 221)
        val otherEdit = track("x", "Entropy", "Beach Bunny", 400)

        assertEquals(MatchDecision.NotFound, TrackMatcher.chooseByIsrc(source, listOf(otherEdit)))
    }

    @Test
    fun should_searchBareTitleAndLeadArtist_when_buildingQuery() {
        val source = track("s", "Peaches (feat. Daniel Caesar & Giveon)", "Justin Bieber, Daniel Caesar", 198)

        assertEquals("Peaches Justin Bieber", TrackMatcher.searchQuery(source))
        assertEquals("Peaches", TrackMatcher.titleQuery(source))
    }

    @Test
    fun should_askTheUser_when_theCandidateIsALiveTake() {
        val source = track("s", "BTTB — Track 4", "Big Thief", 60)
        val live = track("t", "BTTB — Track 4 (Live)", "Big Thief", 60)

        val decision = TrackMatcher.choose(source, listOf(live))

        assertEquals(MatchDecision.Ambiguous(listOf(live)), decision)
    }

    @Test
    fun should_match_when_bothSidesAreTheSameRemix() {
        val source = track("s", "Midnight City - Eric Prydz Remix", "M83", 300)
        val candidate = track("t", "Midnight City (Eric Prydz Remix)", "M83", 301)

        assertTrue(TrackMatcher.choose(source, listOf(candidate)) is MatchDecision.Matched)
    }

    private fun track(
        id: String,
        title: String,
        artist: String,
        durationSec: Int?,
        album: String? = null,
    ) = Track(
        id = MediaId("test", id),
        title = title,
        artist = artist,
        artistId = null,
        album = album,
        albumId = null,
        coverArt = null,
        durationSec = durationSec,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}
