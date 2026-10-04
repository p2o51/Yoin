package com.gpo.yoin.data.memory

import com.gpo.yoin.data.model.MediaId
import org.junit.Assert.assertEquals
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
    fun should_exclude_when_scoreBelowEight() {
        val picks = select(candidate(albumRating = 7.9f))

        assertTrue(picks.isEmpty())
    }

    @Test
    fun should_include_when_scoreExactlyEight() {
        val picks = select(
            candidate(albumId = "album-rated", albumRating = 8f),
            // A track average that should read as 8.0.
            candidate(
                albumId = "album-average",
                albumRating = null,
                averageSongRating = listOf(7f, 8f, 9f).average().toFloat(),
                ratingCoverage = 1f,
            ),
        )

        assertEquals(listOf("album-rated", "album-average"), picks.albumIds())
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
        assertEquals(9f, pick.score, 0.0001f)
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

    private fun select(vararg candidates: AlbumMemoryCandidate): List<RediscoverPick> =
        selectRediscover(candidates.toList(), nowMillis = NOW, excludeRawAlbumIds = emptySet())

    private fun List<RediscoverPick>.albumIds(): List<String> = map { pick -> pick.candidate.albumId }

    private fun candidate(
        albumId: String = "album-1",
        albumRating: Float? = 9f,
        averageSongRating: Float? = null,
        ratingCoverage: Float = 0f,
        lastPlayedFromHistoryAt: Long? = NOW - 120 * DAY_MS,
        lastPlayedAt: Long? = lastPlayedFromHistoryAt,
    ): AlbumMemoryCandidate = AlbumMemoryCandidate(
        profileId = "profile-a",
        provider = MediaId.PROVIDER_SUBSONIC,
        albumId = albumId,
        albumName = "Album $albumId",
        artistName = "Artist",
        totalTracks = 10,
        ratedTrackCount = (ratingCoverage * 10).toInt(),
        ratingCoverage = ratingCoverage,
        averageSongRating = averageSongRating,
        albumRating = albumRating,
        hasAlbumReview = false,
        noteCount = 0,
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
