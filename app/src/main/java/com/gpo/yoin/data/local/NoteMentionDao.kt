package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Read-only queries for note mentions (notes revamp): the writings that mention something (被提及), and the
 * local names a mention can be matched against when its id means nothing to the account in use.
 *
 * Mentions live inside the text (`[@Title](yoin:…)`, shared/notes/NoteMarkup.kt); there is no mention
 * table. The writings are pre-filtered with `LIKE '%](yoin:%'` and parsed in Kotlin: a profile's notes are
 * a few thousand rows at most, the filter leaves only those with a link, and nothing derived has to be kept
 * in step with every write path (the editor, cloud sync, imports, NeoDB pulls, restores).
 */
@Dao
interface NoteMentionDao {

    /** The profile's song notes holding a mention link, with the album their track belongs to (when known). */
    @Query(
        "SELECT n.*, COALESCE(l.albumId, (" +
            "SELECT ph.albumId FROM play_history ph WHERE ph.profileId = n.profileId AND ph.provider = n.provider " +
            "AND ph.songId = n.trackId AND ph.albumId != '' LIMIT 1)) AS linkedAlbumId " +
            "FROM song_notes n LEFT JOIN track_album_links l " +
            "ON l.profileId = n.profileId AND l.provider = n.provider AND l.trackId = n.trackId " +
            "WHERE n.profileId = :profileId AND n.content LIKE '%](yoin:%'",
    )
    fun observeSongNotesWithMentions(profileId: String): Flow<List<LedgerSongNoteRow>>

    @Query("SELECT * FROM album_notes WHERE profileId = :profileId AND content LIKE '%](yoin:%'")
    fun observeAlbumNotesWithMentions(profileId: String): Flow<List<AlbumNote>>

    /** The profile's reviews holding a mention link, with the album's name as Yoin's plays or album notes know it. */
    @Query(
        "SELECT r.*, " +
            "COALESCE((SELECT ph.album FROM play_history ph WHERE ph.profileId = r.profileId " +
            "AND ph.provider = r.provider AND ph.albumId = r.albumId AND ph.album != '' " +
            "ORDER BY ph.playedAt DESC LIMIT 1), " +
            "(SELECT an.albumName FROM album_notes an WHERE an.profileId = r.profileId " +
            "AND an.provider = r.provider AND an.albumId = r.albumId AND an.albumName != '' LIMIT 1)) AS albumName, " +
            "COALESCE((SELECT an.artist FROM album_notes an WHERE an.profileId = r.profileId " +
            "AND an.provider = r.provider AND an.albumId = r.albumId AND an.artist != '' LIMIT 1), " +
            "(SELECT ph.artist FROM play_history ph WHERE ph.profileId = r.profileId " +
            "AND ph.provider = r.provider AND ph.albumId = r.albumId AND ph.artist != '' " +
            "ORDER BY ph.playedAt DESC LIMIT 1)) AS albumArtist " +
            "FROM album_ratings r WHERE r.profileId = :profileId AND r.review LIKE '%](yoin:%'",
    )
    fun observeReviewsWithMentions(profileId: String): Flow<List<MentionReviewRow>>

    /**
     * Tracks the profile knows on [provider] whose title starts with [titlePrefix] (LIKE-escaped with `\`):
     * from its plays and its notes. The caller compares names properly.
     */
    @Query(
        "SELECT songId AS id, title, artist, album FROM play_history " +
            "WHERE profileId = :profileId AND provider = :provider AND title LIKE :titlePrefix || '%' ESCAPE '\\' " +
            "UNION " +
            "SELECT trackId AS id, title, artist, NULL AS album FROM song_notes " +
            "WHERE profileId = :profileId AND provider = :provider AND title LIKE :titlePrefix || '%' ESCAPE '\\' " +
            "LIMIT 200",
    )
    suspend fun tracksTitled(profileId: String, provider: String, titlePrefix: String): List<MentionNameRow>

    /** Albums the profile knows on [provider] whose name starts with [titlePrefix]: from its plays and album notes. */
    @Query(
        "SELECT albumId AS id, album AS title, artist, NULL AS album FROM play_history " +
            "WHERE profileId = :profileId AND provider = :provider AND albumId != '' " +
            "AND album LIKE :titlePrefix || '%' ESCAPE '\\' " +
            "UNION " +
            "SELECT albumId AS id, albumName AS title, artist, NULL AS album FROM album_notes " +
            "WHERE profileId = :profileId AND provider = :provider " +
            "AND albumName LIKE :titlePrefix || '%' ESCAPE '\\' " +
            "LIMIT 200",
    )
    suspend fun albumsTitled(profileId: String, provider: String, titlePrefix: String): List<MentionNameRow>
}

data class MentionReviewRow(
    @Embedded val rating: AlbumRating,
    val albumName: String?,
    val albumArtist: String?,
)

/** A local item's id (as stored) and names, for matching a mention by name. */
data class MentionNameRow(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
)
