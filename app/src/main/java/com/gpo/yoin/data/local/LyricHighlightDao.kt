package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface LyricHighlightDao {
    @Upsert
    suspend fun upsert(row: LyricHighlight)

    /** Usable highlights (text present) for the profile's library on [provider]. */
    @Query(
        "SELECT * FROM lyric_highlights WHERE profileId = :profileId AND provider = :provider " +
            "AND text IS NOT NULL",
    )
    suspend fun usable(profileId: String, provider: String): List<LyricHighlight>

    @Query(
        "SELECT * FROM lyric_highlights WHERE profileId = :profileId AND provider = :provider " +
            "AND songId = :songId",
    )
    suspend fun get(profileId: String, provider: String, songId: String): LyricHighlight?

    /**
     * Song ids not to queue again: every usable highlight picked under the current question ([fingerprintPrefix])
     * or kept by the user, and unusable tries newer than [retryAfter]. A highlight from an older question is
     * picked again (it keeps showing until then).
     */
    @Query(
        "SELECT songId FROM lyric_highlights WHERE profileId = :profileId AND provider = :provider " +
            "AND ((text IS NOT NULL AND (model = 'user' OR lyricsFingerprint LIKE :fingerprintPrefix || '%')) " +
            "OR (text IS NULL AND attemptedAt >= :retryAfter))",
    )
    suspend fun settledSongIds(
        profileId: String,
        provider: String,
        retryAfter: Long,
        fingerprintPrefix: String,
    ): List<String>

    @Query(
        "UPDATE lyric_highlights SET lastShownAt = :shownAt WHERE profileId = :profileId " +
            "AND provider = :provider AND songId = :songId",
    )
    suspend fun markShown(profileId: String, provider: String, songId: String, shownAt: Long)

    @Query("SELECT COUNT(*) FROM lyric_highlights WHERE profileId = :profileId AND provider = :provider AND text IS NOT NULL")
    suspend fun usableCount(profileId: String, provider: String): Int

    /** Per played song: plays, last play, rating and note count, for the selector's pools. */
    @Query(
        "SELECT ph.songId AS songId, MAX(ph.title) AS title, MAX(ph.artist) AS artist, MAX(ph.album) AS album, " +
            "MAX(ph.albumId) AS albumId, MAX(ph.coverArtId) AS coverArtId, MAX(ph.durationMs) AS durationMs, " +
            "COUNT(*) AS playCount, MAX(ph.playedAt) AS lastPlayedAt, " +
            "(SELECT r.rating FROM local_ratings r WHERE r.profileId = :profileId AND r.provider = :provider " +
            "AND r.songId = ph.songId) AS rating, " +
            "(SELECT COUNT(*) FROM song_notes n WHERE n.profileId = :profileId AND n.provider = :provider " +
            "AND n.trackId = ph.songId AND TRIM(n.content) != '') AS noteCount " +
            "FROM play_history ph WHERE ph.profileId = :profileId AND ph.provider = :provider " +
            "GROUP BY ph.songId",
    )
    suspend fun songSignals(profileId: String, provider: String): List<HighlightSongRow>

    /** Plays since [since], for heat. */
    @Query(
        "SELECT songId, playedAt, completedPercent FROM play_history " +
            "WHERE profileId = :profileId AND provider = :provider AND playedAt >= :since",
    )
    suspend fun playsSince(profileId: String, provider: String, since: Long): List<HighlightPlayRow>
}

data class HighlightSongRow(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val coverArtId: String?,
    val durationMs: Long,
    val playCount: Int,
    val lastPlayedAt: Long,
    val rating: Float?,
    val noteCount: Int,
)

data class HighlightPlayRow(val songId: String, val playedAt: Long, val completedPercent: Float)
