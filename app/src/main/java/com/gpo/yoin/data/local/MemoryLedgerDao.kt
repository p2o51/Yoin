package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Read-only queries for the memory ledger (Home's Memories grid and the All Memories page): every
 * account's writings and scores at once. These are the only reads in the app that are NOT scoped by
 * profile, on purpose: every account's memories count as the same listener's (owner 2026-11-08, as in
 * #178). Nothing here writes; edits keep going through each table's own DAO.
 */
@Dao
interface MemoryLedgerDao {

    @Query("SELECT * FROM album_notes")
    suspend fun albumNotes(): List<AlbumNote>

    /** Song notes with the album their track belongs to: the track→album link, else a Yoin play of it. */
    @Query(
        "SELECT n.*, COALESCE(l.albumId, (" +
            "SELECT ph.albumId FROM play_history ph WHERE ph.profileId = n.profileId AND ph.provider = n.provider " +
            "AND ph.songId = n.trackId AND ph.albumId != '' LIMIT 1)) AS linkedAlbumId " +
            "FROM song_notes n LEFT JOIN track_album_links l " +
            "ON l.profileId = n.profileId AND l.provider = n.provider AND l.trackId = n.trackId",
    )
    suspend fun songNotes(): List<LedgerSongNoteRow>

    @Query("SELECT * FROM album_ratings")
    suspend fun albumRatings(): List<AlbumRating>

    /** Rated tracks (a cleared score keeps its row with a null rating: left out), with their album. */
    @Query(
        "SELECT r.profileId AS profileId, r.provider AS provider, r.songId AS songId, r.rating AS rating, " +
            "r.updatedAt AS updatedAt, COALESCE(l.albumId, (" +
            "SELECT ph.albumId FROM play_history ph WHERE ph.profileId = r.profileId AND ph.provider = r.provider " +
            "AND ph.songId = r.songId AND ph.albumId != '' LIMIT 1)) AS linkedAlbumId " +
            "FROM local_ratings r LEFT JOIN track_album_links l " +
            "ON l.profileId = r.profileId AND l.provider = r.provider AND l.trackId = r.songId " +
            "WHERE r.rating IS NOT NULL",
    )
    suspend fun trackRatings(): List<LedgerTrackRatingRow>

    @Query("SELECT * FROM album_memory_titles")
    suspend fun titles(): List<AlbumMemoryTitle>

    /** What Yoin's own plays know about each album: its name, artist and cover, from the latest play. */
    @Query(
        "SELECT profileId, provider, albumId, album AS albumName, artist, coverArtId, MAX(playedAt) AS playedAt " +
            "FROM play_history WHERE albumId != '' GROUP BY profileId, provider, albumId",
    )
    suspend fun albumFacts(): List<LedgerAlbumFactRow>

    /**
     * Changes whenever any of the ledger's tables does (Room re-runs it on each write to them), so the
     * ledger can reload. The value itself means nothing.
     */
    @Query(
        "SELECT (SELECT COUNT(*) + IFNULL(MAX(updatedAt), 0) FROM album_notes) + " +
            "(SELECT COUNT(*) + IFNULL(MAX(updatedAt), 0) FROM song_notes) + " +
            "(SELECT COUNT(*) + IFNULL(MAX(updatedAt), 0) FROM album_ratings) + " +
            "(SELECT COUNT(*) + IFNULL(MAX(updatedAt), 0) FROM local_ratings) + " +
            "(SELECT COUNT(*) + IFNULL(MAX(updatedAt), 0) FROM album_memory_titles)",
    )
    fun changes(): Flow<Long>
}

data class LedgerSongNoteRow(
    @Embedded val note: SongNote,
    val linkedAlbumId: String?,
)

data class LedgerTrackRatingRow(
    val profileId: String,
    val provider: String,
    val songId: String,
    val rating: Float?,
    val updatedAt: Long,
    val linkedAlbumId: String?,
)

data class LedgerAlbumFactRow(
    val profileId: String,
    val provider: String,
    val albumId: String,
    val albumName: String,
    val artist: String,
    val coverArtId: String?,
    val playedAt: Long,
)
