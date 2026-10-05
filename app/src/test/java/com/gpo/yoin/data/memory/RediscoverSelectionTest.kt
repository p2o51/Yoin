package com.gpo.yoin.data.memory

import com.gpo.yoin.data.local.SongMemoryAggregate
import com.gpo.yoin.data.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RediscoverSelectionTest {

    @Test
    fun should_scoreAlbumRating_when_present() {
        val score = rediscoverScore(
            candidate(albumRating = 8.5f, averageSongRating = 6f, ratingCoverage = 1f),
        )

        assertEquals(8.5f, score ?: 0f, 0.0001f)
    }

    @Test
    fun should_scoreTrackAverage_when_noAlbumRatingAndCoverageAtLeastSixtyPercent() {
        val score = rediscoverScore(
            candidate(albumRating = null, averageSongRating = 8.4f, ratingCoverage = 0.6f),
        )

        assertEquals(8.4f, score ?: 0f, 0.0001f)
    }

    @Test
    fun should_notScore_when_trackAverageCoverageBelowSixtyPercent() {
        val score = rediscoverScore(
            candidate(albumRating = null, averageSongRating = 9f, ratingCoverage = 0.5f),
        )

        assertNull(score)
    }

    @Test
    fun should_include_when_scoreBelowEight() {
        val picks = select(candidate(albumRating = 3.5f))

        assertEquals(listOf("album-1"), picks.albumIds())
        assertEquals(3.5f, picks.single().score ?: 0f, 0.0001f)
    }

    @Test
    fun should_includeWithoutScore_when_albumHasOnlyNotes() {
        val picks = select(candidate(albumRating = null, noteCount = 2))

        assertEquals(listOf("album-1"), picks.albumIds())
        assertNull(picks.single().score)
    }

    @Test
    fun should_includeWithoutScore_when_albumHasOnlyAReview() {
        val picks = select(candidate(albumRating = null, hasAlbumReview = true))

        assertEquals(listOf("album-1"), picks.albumIds())
        assertNull(picks.single().score)
    }

    @Test
    fun should_includeWithoutScore_when_trackRatingsCoverUnderSixtyPercent() {
        val picks = select(candidate(albumRating = null, averageSongRating = 9f, ratingCoverage = 0.3f))

        assertEquals(listOf("album-1"), picks.albumIds())
        assertNull(picks.single().score)
    }

    @Test
    fun should_exclude_when_albumHasNoMemory() {
        // Plays (and an Ask AI row) alone are not a memory.
        val picks = select(candidate(albumRating = null).copy(askAiCount = 3))

        assertTrue(picks.isEmpty())
    }

    @Test
    fun should_detectMemory_when_anyKeptSignalIsPresent() {
        assertTrue(hasRediscoverMemory(candidate(albumRating = 6f)))
        assertTrue(hasRediscoverMemory(candidate(albumRating = null, hasAlbumReview = true)))
        assertTrue(hasRediscoverMemory(candidate(albumRating = null, noteCount = 1)))
        assertTrue(hasRediscoverMemory(candidate(albumRating = null, averageSongRating = 7f, ratingCoverage = 0.1f)))
        assertFalse(hasRediscoverMemory(candidate(albumRating = null)))
        assertFalse(hasRediscoverMemory(candidate(albumRating = 0f)))
    }

    @Test
    fun should_orderUnscoredAfterScored_when_unscoredIsLongerAway() {
        val picks = select(
            candidate(
                albumId = "noted",
                albumRating = null,
                noteCount = 4,
                lastPlayedFromHistoryAt = NOW - 900 * DAY_MS,
            ),
            candidate(albumId = "low", albumRating = 4f, lastPlayedFromHistoryAt = NOW - 100 * DAY_MS),
            candidate(
                albumId = "reviewed",
                albumRating = null,
                hasAlbumReview = true,
                lastPlayedFromHistoryAt = NOW - 95 * DAY_MS,
            ),
            candidate(albumId = "high", albumRating = 9f, lastPlayedFromHistoryAt = NOW - 95 * DAY_MS),
        )

        assertEquals(listOf("high", "low", "noted", "reviewed"), picks.albumIds())
    }

    @Test
    fun should_exclude_when_playedWithinNinetyDays() {
        val picks = select(candidate(lastPlayedFromHistoryAt = NOW - REDISCOVER_AWAY_MS + 1))

        assertTrue(picks.isEmpty())
    }

    @Test
    fun should_include_when_exactlyNinetyDaysAway() {
        val picks = select(candidate(lastPlayedFromHistoryAt = NOW - REDISCOVER_AWAY_MS))

        assertEquals(listOf("album-1"), picks.albumIds())
        assertEquals(NOW - REDISCOVER_AWAY_MS, picks.single().lastPlayedAt)
    }

    @Test
    fun should_exclude_when_onlyVisited() {
        // An old visit sets the Memory lastPlayedAt but never the history field.
        val picks = select(
            candidate(lastPlayedFromHistoryAt = null, lastPlayedAt = NOW - 200 * DAY_MS),
        )

        assertTrue(picks.isEmpty())
    }

    @Test
    fun should_orderByScoreThenLongestAway() {
        val picks = select(
            candidate(albumId = "recent-nine", albumRating = 9f, lastPlayedFromHistoryAt = NOW - 100 * DAY_MS),
            candidate(albumId = "top-score", albumRating = 9.5f, lastPlayedFromHistoryAt = NOW - 95 * DAY_MS),
            candidate(albumId = "oldest-nine", albumRating = 9f, lastPlayedFromHistoryAt = NOW - 200 * DAY_MS),
        )

        assertEquals(listOf("top-score", "oldest-nine", "recent-nine"), picks.albumIds())
    }

    @Test
    fun should_excludeJbiMemoryAndPillAlbums() {
        val picks = selectRediscover(
            candidates = listOf(
                candidate(albumId = "jbi-memory"),
                candidate(albumId = "${MediaId.PROVIDER_SUBSONIC}:pill-latest"),
                candidate(albumId = "keeper"),
            ),
            nowMillis = NOW,
            excludeRawAlbumIds = setOf("jbi-memory", "pill-latest"),
        )

        assertEquals(listOf("keeper"), picks.albumIds())
    }

    @Test
    fun should_dedupeByRawAlbumId_when_legacyPrefixedSeedDuplicates() {
        val picks = select(
            candidate(albumId = "${MediaId.PROVIDER_SUBSONIC}:album-1", albumRating = 8.5f),
            candidate(albumId = "album-1", albumRating = 9f),
        )

        val pick = picks.single()
        assertEquals("album-1", pick.candidate.albumId)
        assertEquals(9f, pick.score ?: 0f, 0.0001f)
    }

    @Test
    fun should_capAtLimit() {
        val candidates = (1..8).map { index -> candidate(albumId = "album-$index") }

        assertEquals(REDISCOVER_LIMIT, select(*candidates.toTypedArray()).size)
        assertEquals(
            2,
            selectRediscover(candidates, nowMillis = NOW, excludeRawAlbumIds = emptySet(), limit = 2).size,
        )
    }

    // ── The album track gate ──────────────────────────────────────────

    @Test
    fun should_excludeAlbum_when_itHasFewerThanFourTracks() {
        val picks = select(
            candidate(albumId = "single").copy(totalTracks = 1),
            candidate(albumId = "short-ep").copy(totalTracks = MEMORY_MIN_TRACK_COUNT - 1),
            candidate(albumId = "ep").copy(totalTracks = MEMORY_MIN_TRACK_COUNT),
        )

        assertEquals(listOf("ep"), picks.albumIds())
    }

    @Test
    fun should_keepTrackGateOpen_when_albumDetailNeverLoaded() {
        // totalTracks 0 = no detail: unknown, as meetsMemoryTrackCount(null).
        assertTrue(meetsRediscoverTrackCount(candidate().copy(totalTracks = 0)))
        assertEquals(listOf("album-1"), select(candidate().copy(totalTracks = 0)).albumIds())
    }

    @Test
    fun should_trustTheMemoryGate_when_candidateIsMemoryEligible() {
        // A partly loaded track list (two of twelve) on an album the builder already counted in full.
        val partial = candidate().copy(totalTracks = 2, isMemoryEligible = true)

        assertTrue(meetsRediscoverTrackCount(partial))
        assertFalse(meetsRediscoverTrackCount(partial.copy(isMemoryEligible = false)))
    }

    // ── Songs ─────────────────────────────────────────────────────────

    @Test
    fun should_includeSong_when_ratedAndAwayNinetyDays() {
        val picks = selectRediscoverSongs(listOf(song("s1", rating = 8.5f)), nowMillis = NOW)

        val pick = picks.single()
        assertEquals("s1", pick.song.songId)
        assertEquals(8.5f, pick.score ?: 0f, 0.0001f)
        assertEquals(NOW - 120 * DAY_MS, pick.lastPlayedAt)
    }

    @Test
    fun should_includeSongWithoutScore_when_itHasOnlyANote() {
        val pick = selectRediscoverSongs(listOf(song("s1", rating = null, note = "kept")), nowMillis = NOW).single()

        assertNull(pick.score)
    }

    @Test
    fun should_excludeSong_when_itHasNoMemory() {
        val picks = selectRediscoverSongs(
            listOf(
                song("unrated", rating = null),
                song("zero", rating = 0f),
                song("blank-note", rating = null, note = "   "),
            ),
            nowMillis = NOW,
        )

        assertTrue(picks.isEmpty())
    }

    @Test
    fun should_excludeSong_when_playedWithinNinetyDays() {
        val picks = selectRediscoverSongs(
            listOf(
                song("recent", lastPlayedAt = NOW - REDISCOVER_AWAY_MS + 1),
                song("exact", lastPlayedAt = NOW - REDISCOVER_AWAY_MS),
            ),
            nowMillis = NOW,
        )

        assertEquals(listOf("exact"), picks.map { it.song.songId })
    }

    @Test
    fun should_orderSongsLikeAlbums_when_scoredAndUnscoredMix() {
        val picks = selectRediscoverSongs(
            listOf(
                song("noted-old", rating = null, note = "kept", lastPlayedAt = NOW - 900 * DAY_MS),
                song("low", rating = 4f, lastPlayedAt = NOW - 100 * DAY_MS),
                song("high-recent", rating = 9f, lastPlayedAt = NOW - 100 * DAY_MS),
                song("high-old", rating = 9f, lastPlayedAt = NOW - 300 * DAY_MS),
            ),
            nowMillis = NOW,
        )

        assertEquals(listOf("high-old", "high-recent", "low", "noted-old"), picks.map { it.song.songId })
    }

    @Test
    fun should_dropExcludedSongs_when_songOrItsAlbumIsExcluded() {
        val picks = selectRediscoverSongs(
            listOf(song("played"), song("of-played-album", albumId = "gone"), song("keeper")),
            nowMillis = NOW,
            excludeRawSongIds = setOf("played"),
            excludeRawAlbumIds = setOf("gone"),
        )

        assertEquals(listOf("keeper"), picks.map { it.song.songId })
    }

    // ── The shelf ─────────────────────────────────────────────────────

    @Test
    fun should_interleaveAlbumsAndSongs_when_buildingTheShelf() {
        val albums = select(
            candidate(albumId = "a9", albumRating = 9f),
            candidate(albumId = "a7", albumRating = 7f),
            candidate(
                albumId = "a-noted",
                albumRating = null,
                noteCount = 1,
                lastPlayedFromHistoryAt = NOW - 400 * DAY_MS,
            ),
        )
        val songs = selectRediscoverSongs(
            listOf(
                song("s8", rating = 8f),
                song("s-noted", rating = null, note = "kept", lastPlayedAt = NOW - 500 * DAY_MS),
            ),
            nowMillis = NOW,
        )

        val shelf = selectRediscoverShelf(albums, songs)

        assertEquals(listOf("a9", "s8", "a7", "s-noted", "a-noted"), shelf.ids())
    }

    @Test
    fun should_putTheAlbumFirst_when_albumAndSongTie() {
        val albums = select(
            candidate(albumId = "album", albumRating = 8f, lastPlayedFromHistoryAt = NOW - 120 * DAY_MS),
        )
        val songs = selectRediscoverSongs(listOf(song("song", rating = 8f, lastPlayedAt = NOW - 120 * DAY_MS)), NOW)

        assertEquals(listOf("album", "song"), selectRediscoverShelf(albums, songs).ids())
    }

    @Test
    fun should_dropSong_when_itsAlbumIsOnTheShelf() {
        val albums = select(candidate(albumId = "album-1", albumRating = 7f))
        val songs = selectRediscoverSongs(
            listOf(song("from-album-1", rating = 9f, albumId = "album-1"), song("elsewhere", rating = 6f)),
            nowMillis = NOW,
        )

        assertEquals(listOf("album-1", "elsewhere"), selectRediscoverShelf(albums, songs).ids())
    }

    @Test
    fun should_keepSong_when_itsAlbumOnlyQualifiesPastTheLimit() {
        val albums = select(
            candidate(albumId = "a1", albumRating = 9.5f),
            candidate(albumId = "a2", albumRating = 9.4f),
            candidate(albumId = "late", albumRating = 5f),
            limit = Int.MAX_VALUE,
        )
        val songs = selectRediscoverSongs(listOf(song("from-late", rating = 9f, albumId = "late")), NOW)

        assertEquals(listOf("a1", "a2", "from-late"), selectRediscoverShelf(albums, songs, limit = 3).ids())
    }

    @Test
    fun should_refillTheShelf_when_aSongLeavesForItsAlbum() {
        // Dropping s-a3 (album a3 made the shelf) frees a slot, which the next card takes.
        val albums = select(
            candidate(albumId = "a1", albumRating = 9f),
            candidate(albumId = "a3", albumRating = 7f),
            limit = Int.MAX_VALUE,
        )
        val songs = selectRediscoverSongs(
            listOf(song("s-a3", rating = 8f, albumId = "a3"), song("s5", rating = 5f), song("s4", rating = 4f)),
            nowMillis = NOW,
        )

        assertEquals(listOf("a1", "a3", "s5"), selectRediscoverShelf(albums, songs, limit = 3).ids())
    }

    @Test
    fun should_capTheShelf_when_moreCardsQualify() {
        val albums = select(*(1..5).map { candidate(albumId = "a$it", albumRating = 9f) }.toTypedArray(), limit = 99)
        val songs = selectRediscoverSongs((1..5).map { song("s$it", rating = 8f) }, NOW)

        assertEquals(REDISCOVER_LIMIT, selectRediscoverShelf(albums, songs).size)
    }

    private fun select(vararg candidates: AlbumMemoryCandidate, limit: Int): List<RediscoverPick> =
        selectRediscover(candidates.toList(), nowMillis = NOW, excludeRawAlbumIds = emptySet(), limit = limit)

    private fun List<RediscoverEntry>.ids(): List<String> = map { entry ->
        when (entry) {
            is RediscoverPick -> entry.candidate.albumId
            is RediscoverSongPick -> entry.song.songId
        }
    }

    private fun song(
        songId: String,
        rating: Float? = 8f,
        note: String? = null,
        albumId: String = "album-of-$songId",
        lastPlayedAt: Long = NOW - 120 * DAY_MS,
    ): SongMemoryAggregate = SongMemoryAggregate(
        songId = songId,
        provider = MediaId.PROVIDER_SUBSONIC,
        title = "Song $songId",
        artist = "Artist",
        album = "Album $albumId",
        albumId = albumId,
        coverArtId = null,
        durationMs = 200_000L,
        playCount = 3,
        firstPlayedAt = lastPlayedAt - 30 * DAY_MS,
        lastPlayedAt = lastPlayedAt,
        rating = rating,
        noteCount = if (note != null) 1 else 0,
        latestNote = note,
    )

    private fun select(vararg candidates: AlbumMemoryCandidate): List<RediscoverPick> =
        selectRediscover(candidates.toList(), nowMillis = NOW, excludeRawAlbumIds = emptySet())

    private fun List<RediscoverPick>.albumIds(): List<String> = map { pick -> pick.candidate.albumId }

    private fun candidate(
        albumId: String = "album-1",
        albumRating: Float? = 9f,
        averageSongRating: Float? = null,
        ratingCoverage: Float = if (averageSongRating != null) 1f else 0f,
        hasAlbumReview: Boolean = false,
        noteCount: Int = 0,
        lastPlayedFromHistoryAt: Long? = NOW - 120 * DAY_MS,
        lastPlayedAt: Long? = lastPlayedFromHistoryAt,
    ): AlbumMemoryCandidate = AlbumMemoryCandidate(
        profileId = "profile-a",
        provider = MediaId.PROVIDER_SUBSONIC,
        albumId = albumId,
        albumName = "Album $albumId",
        artistName = "Artist",
        totalTracks = 10,
        // Any rated track counts as a memory, however thin the coverage.
        ratedTrackCount = if (averageSongRating != null) maxOf(1, (ratingCoverage * 10).toInt()) else 0,
        ratingCoverage = ratingCoverage,
        averageSongRating = averageSongRating,
        albumRating = albumRating,
        hasAlbumReview = hasAlbumReview,
        noteCount = noteCount,
        askAiCount = 0,
        firstPlayedAt = lastPlayedAt,
        lastPlayedAt = lastPlayedAt,
        playCount = 0,
        neoDbSynced = false,
        isMemoryEligible = false,
        year = null,
        durationSeconds = null,
        coverArtUrl = null,
        firstPlayedFromHistoryAt = lastPlayedFromHistoryAt,
        lastPlayedFromHistoryAt = lastPlayedFromHistoryAt,
        playCountFromHistory = if (lastPlayedFromHistoryAt == null) 0 else 4,
    )

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val NOW = 1_800_000_000_000L
    }
}
