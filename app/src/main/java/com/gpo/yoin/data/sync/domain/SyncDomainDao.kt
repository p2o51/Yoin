package com.gpo.yoin.data.sync.domain

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.HomeLayoutPreference
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.LyricsCache
import com.gpo.yoin.data.local.LyricsTranslationCache
import com.gpo.yoin.data.local.MemoryCopyCache
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.SpotifyConfig

/**
 * Every read and write cloud sync makes against the app's domain tables.
 *
 * Kept apart from the feature DAOs so sync never widens or changes them, and
 * so the writes are field-level: applying a synced album rating must not
 * touch the review, NeoDB's push state or anything else the user (or NeoDB)
 * owns on the same row. Callers wrap read-check-write sequences in
 * `YoinDatabase.withTransaction`.
 */
@Dao
interface SyncDomainDao {
    // ---- song_notes

    @Query("SELECT * FROM song_notes WHERE profileId = :profileId")
    suspend fun notesForProfile(profileId: String): List<SongNote>

    @Query("SELECT * FROM song_notes WHERE id = :id LIMIT 1")
    suspend fun noteById(id: String): SongNote?

    /** Callers must have checked [noteById] first: REPLACE on `id` would re-parent another profile's note. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeNote(note: SongNote)

    @Query("DELETE FROM song_notes WHERE id = :id AND profileId = :profileId")
    suspend fun deleteNote(id: String, profileId: String): Int

    // ---- local_ratings (track ratings)

    @Query("SELECT * FROM local_ratings WHERE profileId = :profileId")
    suspend fun trackRatingsForProfile(profileId: String): List<LocalRating>

    @Query(
        "SELECT * FROM local_ratings " +
            "WHERE profileId = :profileId AND songId = :songId AND provider = :provider LIMIT 1",
    )
    suspend fun trackRating(profileId: String, songId: String, provider: String): LocalRating?

    @Upsert
    suspend fun upsertTrackRating(rating: LocalRating)

    @Query(
        "DELETE FROM local_ratings " +
            "WHERE profileId = :profileId AND songId = :songId AND provider = :provider",
    )
    suspend fun deleteTrackRating(profileId: String, songId: String, provider: String): Int

    // ---- album_ratings (rating + review share one row)

    @Query("SELECT * FROM album_ratings WHERE profileId = :profileId")
    suspend fun albumRatingsForProfile(profileId: String): List<AlbumRating>

    @Query(
        "SELECT * FROM album_ratings " +
            "WHERE profileId = :profileId AND albumId = :albumId AND provider = :provider LIMIT 1",
    )
    suspend fun albumRating(profileId: String, albumId: String, provider: String): AlbumRating?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumRatingIfAbsent(row: AlbumRating): Long

    /** Rating only: leaves review, neoDbReviewUuid and the *NeedsSync flags alone. */
    @Query(
        "UPDATE album_ratings SET rating = :rating, updatedAt = :updatedAt " +
            "WHERE profileId = :profileId AND albumId = :albumId AND provider = :provider",
    )
    suspend fun updateAlbumRatingValue(
        profileId: String,
        albumId: String,
        provider: String,
        rating: Float,
        updatedAt: Long,
    ): Int

    /** Review only: leaves rating, neoDbReviewUuid and the *NeedsSync flags alone. */
    @Query(
        "UPDATE album_ratings SET review = :review, updatedAt = :updatedAt " +
            "WHERE profileId = :profileId AND albumId = :albumId AND provider = :provider",
    )
    suspend fun updateAlbumReviewValue(
        profileId: String,
        albumId: String,
        provider: String,
        review: String?,
        updatedAt: Long,
    ): Int

    @Query(
        "DELETE FROM album_ratings " +
            "WHERE profileId = :profileId AND albumId = :albumId AND provider = :provider",
    )
    suspend fun deleteAlbumRating(profileId: String, albumId: String, provider: String): Int

    // ---- home_layout

    @Query("SELECT * FROM home_layout WHERE profileId = :profileId LIMIT 1")
    suspend fun homeLayout(profileId: String): HomeLayoutPreference?

    @Upsert
    suspend fun upsertHomeLayout(preference: HomeLayoutPreference)

    @Query("DELETE FROM home_layout WHERE profileId = :profileId")
    suspend fun deleteHomeLayout(profileId: String): Int

    // ---- gemini_config (targetLanguage only; apiKey is never read into a projection nor overwritten)

    @Query("SELECT * FROM gemini_config WHERE id = 1 LIMIT 1")
    suspend fun geminiConfig(): GeminiConfig?

    @Query("UPDATE gemini_config SET targetLanguage = :targetLanguage WHERE id = 1")
    suspend fun updateGeminiTargetLanguage(targetLanguage: String): Int

    /** Use with `apiKey = ""` only: seeds the row when absent, never replaces an existing key. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGeminiConfigIfAbsent(config: GeminiConfig): Long

    @Query("SELECT COUNT(*) FROM song_about_entries")
    suspend fun songAboutEntryCount(): Int

    // ---- spotify_config

    @Query("SELECT * FROM spotify_config WHERE id = 1 LIMIT 1")
    suspend fun spotifyConfig(): SpotifyConfig?

    @Upsert
    suspend fun upsertSpotifyConfig(config: SpotifyConfig)

    // ---- profiles (read-only for sync)

    @Query("SELECT * FROM profiles ORDER BY createdAt ASC")
    suspend fun allProfiles(): List<Profile>

    @Query("SELECT COUNT(*) FROM profiles WHERE provider = :provider")
    suspend fun profileCount(provider: String): Int

    // ---- lyrics_translation_cache (paid rows only; `provider:*` rows are free and re-fetchable)

    @Query("SELECT * FROM lyrics_translation_cache WHERE model NOT LIKE 'provider:%'")
    suspend fun paidTranslations(): List<LyricsTranslationCache>

    @Query(
        "SELECT * FROM lyrics_translation_cache " +
            "WHERE trackProvider = :trackProvider AND trackRawId = :trackRawId " +
            "AND sourceHash = :sourceHash AND targetLanguage = :targetLanguage AND model = :model " +
            "LIMIT 1",
    )
    suspend fun translation(
        trackProvider: String,
        trackRawId: String,
        sourceHash: String,
        targetLanguage: String,
        model: String,
    ): LyricsTranslationCache?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTranslationIfAbsent(entry: LyricsTranslationCache): Long

    @Query(
        "DELETE FROM lyrics_translation_cache " +
            "WHERE trackProvider = :trackProvider AND trackRawId = :trackRawId " +
            "AND sourceHash = :sourceHash AND targetLanguage = :targetLanguage AND model = :model",
    )
    suspend fun deleteTranslation(
        trackProvider: String,
        trackRawId: String,
        sourceHash: String,
        targetLanguage: String,
        model: String,
    ): Int

    // ---- lyrics_cache (any age: the 30-day freshness filter is the repository's, not sync's)

    @Query(
        "SELECT * FROM lyrics_cache WHERE trackProvider = :trackProvider AND trackRawId = :trackRawId LIMIT 1",
    )
    suspend fun lyricsCache(trackProvider: String, trackRawId: String): LyricsCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeLyricsCache(entry: LyricsCache)

    // ---- memory_copy_cache (AI album copy and title)

    @Query("SELECT * FROM memory_copy_cache WHERE profileId = :profileId")
    suspend fun memoryCopiesForProfile(profileId: String): List<MemoryCopyCache>

    @Query(
        "SELECT * FROM memory_copy_cache WHERE profileId = :profileId AND provider = :provider " +
            "AND entityType = :entityType AND entityId = :entityId LIMIT 1",
    )
    suspend fun memoryCopy(profileId: String, provider: String, entityType: String, entityId: String): MemoryCopyCache?

    @Upsert
    suspend fun upsertMemoryCopy(row: MemoryCopyCache)

    // ---- song_about_entries (Gemini About + the user's Ask Q&A)

    @Query("SELECT * FROM song_about_entries")
    suspend fun allSongAbout(): List<SongAboutEntry>

    @Query(
        "SELECT * FROM song_about_entries WHERE titleKey = :titleKey AND artistKey = :artistKey " +
            "AND albumKey = :albumKey AND kind = :kind AND entryKey = :entryKey LIMIT 1",
    )
    suspend fun songAbout(titleKey: String, artistKey: String, albumKey: String, kind: String, entryKey: String): SongAboutEntry?

    @Upsert
    suspend fun upsertSongAbout(entry: SongAboutEntry)
}
