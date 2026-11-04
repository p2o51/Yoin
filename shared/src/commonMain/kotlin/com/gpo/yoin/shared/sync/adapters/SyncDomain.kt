package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.sync.identity.SyncProfile

/*
 * The app's own data as the sync adapters see it. Android's adapters read Room
 * (`YoinDatabase` DAOs) directly; on iOS the Swift stores (NotesStore,
 * LocalRatings, AlbumMemoryStore, ...) implement [SyncDomain]. Every row type
 * here holds exactly the columns Android's adapter reads or writes for that
 * kind, under the same names, so the shared adapters below are the same rules.
 *
 * Calls are synchronous and come from the sync coordinator's dispatcher (the
 * main thread on iOS). Each write is its own unit (iOS stores are
 * single-threaded, so "re-read, compare, write" in one call is atomic).
 */

/** `local_ratings`: Yoin's 0..10 track rating; null = not rated (a cleared row, kept so the clear syncs). */
data class TrackRatingRow(
    val provider: String,
    val songId: String,
    val rating: Double?,
    val updatedAt: Long,
)

/** `album_ratings`: the album rating and the long review share one row. */
data class AlbumRatingRow(
    val provider: String,
    val albumId: String,
    /** 0 = no rating. */
    val rating: Double,
    val review: String?,
    /** When the review text was last written (album page date); null once cleared. */
    val reviewUpdatedAt: Long?,
    val updatedAt: Long,
)

/** `song_notes`. [id] is globally unique (a UUID). */
data class SongNoteRow(
    val id: String,
    val profileId: String,
    val trackId: String,
    val provider: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String,
    val artist: String,
    val positionMs: Long?,
)

/** `album_memory_titles`: the title the user gave an album's Memory. */
data class AlbumTitleRow(
    val provider: String,
    val albumId: String,
    val title: String,
    val updatedAt: Long,
)

/**
 * `memory_copy_cache`: the Gemini mood line ([copyText], Android's `copy` column; renamed because `copy`
 * clashes with NSObject's in Swift) and the AI album title ([title]) of one Memory.
 */
data class MemoryCopyRow(
    val provider: String,
    val entityType: String,
    val entityId: String,
    val copyText: String,
    val promptHash: String,
    val generatedAt: Long,
    val title: String?,
    val titlePromptHash: String?,
)

/** `song_about_entries`: Gemini's About rows and Ask Gemini answers (global, keyed by song metadata). */
data class SongAboutRow(
    val titleKey: String,
    val artistKey: String,
    val albumKey: String,
    val titleDisplay: String,
    val artistDisplay: String,
    val albumDisplay: String,
    val kind: String,
    val entryKey: String,
    val promptText: String?,
    val titleText: String?,
    val answerText: String,
    val createdAt: Long,
    val updatedAt: Long,
)

interface SyncDomain {
    /** Every local music account. */
    fun profiles(): List<SyncProfile>

    fun trackRatings(profileId: String): List<TrackRatingRow>

    fun trackRating(profileId: String, provider: String, songId: String): TrackRatingRow?

    fun putTrackRating(profileId: String, row: TrackRatingRow)

    fun deleteTrackRating(profileId: String, provider: String, songId: String)

    fun albumRatings(profileId: String): List<AlbumRatingRow>

    fun albumRating(profileId: String, provider: String, albumId: String): AlbumRatingRow?

    fun putAlbumRating(profileId: String, row: AlbumRatingRow)

    fun deleteAlbumRating(profileId: String, provider: String, albumId: String)

    fun notes(profileId: String): List<SongNoteRow>

    /** By id across every profile (ids are global; see SongNoteSyncAdapter). */
    fun note(id: String): SongNoteRow?

    fun putNote(row: SongNoteRow)

    fun deleteNote(id: String, profileId: String)

    fun albumTitles(profileId: String): List<AlbumTitleRow>

    fun albumTitle(profileId: String, provider: String, albumId: String): AlbumTitleRow?

    fun putAlbumTitle(profileId: String, row: AlbumTitleRow)

    fun deleteAlbumTitle(profileId: String, provider: String, albumId: String)

    fun memoryCopies(profileId: String): List<MemoryCopyRow>

    fun memoryCopy(profileId: String, provider: String, entityType: String, entityId: String): MemoryCopyRow?

    fun putMemoryCopy(profileId: String, row: MemoryCopyRow)

    /** The language About / Ask rows are written in (Android `GeminiConfig.normalizeTargetLanguage`). */
    fun aboutLanguage(): String

    fun songAbouts(): List<SongAboutRow>

    fun songAbout(titleKey: String, artistKey: String, albumKey: String, kind: String, entryKey: String): SongAboutRow?

    fun putSongAbout(row: SongAboutRow)
}
