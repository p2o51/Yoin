package com.gpo.yoin.ui.home

import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.memories.MemoryScoreKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMemoryPillTest {

    @Test
    fun should_pickMostRecentlyWrittenMemory_when_severalEligible() {
        val strong = candidate("strong", review = true, lastWrittenAt = 1_000L, lastPlayedAt = 9_000L)
        val fresh = candidate("fresh", lastWrittenAt = 5_000L, lastPlayedAt = 2_000L)
        // Ranked strongest-first like the builder hands them over.
        assertEquals("fresh", pickLatestMemory(listOf(strong, fresh))?.albumId)
    }

    @Test
    fun should_fallBackToLastPlayed_when_noWriteTimestamp() {
        val a = candidate("a", lastWrittenAt = null, lastPlayedAt = 3_000L)
        val b = candidate("b", lastWrittenAt = null, lastPlayedAt = 7_000L)
        assertEquals("b", pickLatestMemory(listOf(a, b))?.albumId)
    }

    @Test
    fun should_keepStrongerMemory_when_writeTimesTie() {
        val stronger = candidate("stronger", review = true, lastWrittenAt = 4_000L)
        val weaker = candidate("weaker", lastWrittenAt = 4_000L)
        assertEquals("stronger", pickLatestMemory(listOf(stronger, weaker))?.albumId)
    }

    @Test
    fun should_useAlbumRatingKind_when_albumRatingPresent() {
        val pill = buildHomeMemoryPill(
            listOf(candidate("a", albumRating = 8.4f, averageSongRating = 7.1f)),
            noteCount = 2,
        )
        assertEquals(MemoryScoreKind.ALBUM_RATING, pill.latest?.scoreKind)
        assertEquals("8.4", pill.latest?.scoreText)
    }

    @Test
    fun should_useTrackAverageKind_when_onlyTrackRatings() {
        val pill = buildHomeMemoryPill(
            listOf(candidate("a", albumRating = null, averageSongRating = 7.56f)),
            noteCount = 0,
        )
        assertEquals(MemoryScoreKind.AVERAGE_TRACK_RATING, pill.latest?.scoreKind)
        assertEquals("7.6", pill.latest?.scoreText)
    }

    @Test
    fun should_omitScore_when_memoryQualifiesByNotesOnly() {
        val pill = buildHomeMemoryPill(
            listOf(candidate("a", albumRating = null, averageSongRating = null, noteCount = 3)),
            noteCount = 3,
        )
        assertEquals(MemoryScoreKind.NONE, pill.latest?.scoreKind)
        assertNull(pill.latest?.scoreText)
    }

    @Test
    fun should_formatTenAsTenPointZero_when_scoreIsTen() {
        val pill = buildHomeMemoryPill(listOf(candidate("a", albumRating = 10f)), noteCount = 0)
        assertEquals("10.0", pill.latest?.scoreText)
    }

    @Test
    fun should_stripStoredProviderPrefix_when_buildingAlbumId() {
        val pill = buildHomeMemoryPill(
            listOf(candidate("subsonic:al-1", albumRating = 8f)),
            noteCount = 0,
        )
        assertEquals(MediaId.subsonic("al-1"), pill.latest?.albumId)
    }

    @Test
    fun should_buildGhost_when_noCandidatesAndNoNotes() {
        val pill = buildHomeMemoryPill(emptyList(), noteCount = 0)
        assertNull(pill.latest)
        assertEquals(MemoryPillForm.Ghost, resolveMemoryPillForm(pill, PillEmptyForm.Ghost))
        // The quiet variant keeps today's bare chevron instead.
        assertEquals(MemoryPillForm.Unresolved, resolveMemoryPillForm(pill, PillEmptyForm.Chevron))
    }

    @Test
    fun should_buildNotesOnly_when_noCandidatesButNotes() {
        val pill = buildHomeMemoryPill(emptyList(), noteCount = 3)
        assertEquals(MemoryPillForm.NotesOnly, resolveMemoryPillForm(pill, PillEmptyForm.Chevron))
    }

    @Test
    fun should_stayUnresolved_when_pillNotLoaded() {
        assertEquals(MemoryPillForm.Unresolved, resolveMemoryPillForm(null, PillEmptyForm.Ghost))
    }

    @Test
    fun should_chooseFullNotes_when_widthFits() {
        assertEquals(NotesForm.Full, chooseNotesForm(available = 200, base = 100, fullExtra = 90, shortExtra = 40))
    }

    @Test
    fun should_chooseShortNotes_when_fullDoesNotFit() {
        assertEquals(NotesForm.Short, chooseNotesForm(available = 160, base = 100, fullExtra = 90, shortExtra = 40))
    }

    @Test
    fun should_hideNotes_when_neitherFormFits() {
        assertEquals(NotesForm.None, chooseNotesForm(available = 120, base = 100, fullExtra = 90, shortExtra = 40))
    }

    @Test
    fun should_capLabel_when_countAbove999() {
        assertEquals("999+ notes", formatNoteCount(1_234))
        assertEquals("999+", formatNoteCountShort(1_234))
        assertEquals("1 note", formatNoteCount(1))
        assertEquals("12 notes", formatNoteCount(12))
    }

    @Test
    fun should_describeLatestScoreAndNotes_when_buildingContentDescription() {
        val pill = buildHomeMemoryPill(
            listOf(candidate("a", name = "Describe", artist = "Hannah Jadagu", albumRating = 8.4f)),
            noteCount = 12,
        )
        val description = memoryPillContentDescription(pill, MemoryPillForm.Populated)
        assertTrue(description.contains("Describe by Hannah Jadagu"))
        assertTrue(description.contains("Your album rating 8.4"))
        assertTrue(description.contains("12 notes"))
        assertEquals(
            "Memories, nothing kept yet",
            memoryPillContentDescription(HomeMemoryPill(null, 0), MemoryPillForm.Ghost),
        )
    }

    @Test
    fun should_developOnlyAfterShellSurfaces_when_unrolling() {
        assertEquals(0f, develop(0f), 0f)
        assertEquals(0f, develop(0.45f), 0f)
        assertEquals(1f, develop(1f), 1e-6f)
        assertTrue(develop(0.7f) in 0.01f..0.99f)
    }

    @Test
    fun should_preferAnotherAlbumForJbiCard_when_pillShowsLatest() {
        // The plain recipe would pick "latest" (the only review) — the avoid
        // rule must hand the 1×2 the other memory instead.
        val latest = candidate("latest", review = true, albumRating = 9f, lastWrittenAt = 9_000L)
        val older = candidate("older", albumRating = 7f, lastWrittenAt = 1_000L)
        val candidates = listOf(latest, older)
        val pill = buildHomeMemoryPill(candidates, noteCount = 0)
        assertEquals("latest", pill.latest?.albumId?.rawId)
        assertEquals("older", pickJbiMemoryCandidate(candidates, pill.latest?.albumId?.rawId)?.albumId)
    }

    @Test
    fun should_keepShownJbiAlbum_when_itStillQualifies() {
        val latest = candidate("latest", review = true, albumRating = 9f, lastWrittenAt = 9_000L)
        val older = candidate("older", albumRating = 7f, lastWrittenAt = 1_000L)
        assertEquals(
            "latest",
            pickJbiMemoryCandidate(
                candidates = listOf(latest, older),
                avoidRawAlbumId = "latest",
                preferRawAlbumId = "latest",
            )?.albumId,
        )
    }

    @Test
    fun should_dropShownJbiAlbum_when_itNoLongerQualifies() {
        val unrated = candidate("shown", albumRating = null, averageSongRating = null, noteCount = 3)
        val rated = candidate("rated", albumRating = 7f)
        assertEquals(
            "rated",
            pickJbiMemoryCandidate(listOf(unrated, rated), avoidRawAlbumId = null, preferRawAlbumId = "shown")?.albumId,
        )
    }

    @Test
    fun should_reuseSameAlbumForJbiCard_when_onlyOneMemory() {
        val only = candidate("only", albumRating = 8f)
        assertEquals("only", pickJbiMemoryCandidate(listOf(only), avoidRawAlbumId = "only")?.albumId)
    }

    @Test
    fun should_skipUnscoredMemoryForJbiCard_when_itHasNoRatingOrReview() {
        // Eligible by notes alone → no score to show on the 1×2.
        val notesOnly = candidate("notes", albumRating = null, averageSongRating = null, noteCount = 4)
        assertNull(pickJbiMemoryCandidate(listOf(notesOnly), avoidRawAlbumId = null))
    }

    private fun candidate(
        albumId: String,
        name: String = albumId,
        artist: String? = "Artist",
        review: Boolean = false,
        albumRating: Float? = 8f,
        averageSongRating: Float? = null,
        noteCount: Int = 0,
        lastWrittenAt: Long? = null,
        lastPlayedAt: Long? = null,
    ): AlbumMemoryCandidate = AlbumMemoryCandidate(
        profileId = "profile",
        provider = MediaId.PROVIDER_SUBSONIC,
        albumId = albumId,
        albumName = name,
        artistName = artist,
        totalTracks = 10,
        ratedTrackCount = 0,
        ratingCoverage = 0f,
        averageSongRating = averageSongRating,
        albumRating = albumRating,
        hasAlbumReview = review,
        noteCount = noteCount,
        askAiCount = 0,
        firstPlayedAt = null,
        lastPlayedAt = lastPlayedAt,
        lastWrittenAt = lastWrittenAt,
        playCount = 0,
        neoDbSynced = false,
        isMemoryEligible = true,
        year = null,
        durationSeconds = null,
        coverArtUrl = null,
    )
}
