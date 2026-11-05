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
 *
 * Every write returns whether it reached durable storage. A store that could
 * not persist must leave its in-memory state as it was and return false: the
 * adapter then fails that record ([SyncDomainWriteException]), so the engine
 * keeps it unapplied and retries it, instead of recording an apply that
 * disappears on relaunch.
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

/**
 * `lyrics_translation_cache`: one translation of a track's lyrics (Android LyricsTranslationCache).
 * [translationsJson] is kept as the exact text it was written or received as.
 */
data class LyricsTranslationRow(
    val trackProvider: String,
    val trackRawId: String,
    val sourceHash: String,
    val targetLanguage: String,
    val model: String,
    val translationsJson: String,
    val cachedAt: Long,
)

/**
 * `lyrics_cache`: the lyrics a track loads (Android LyricsCache). [lyricsProvider] "manual" = typed by the
 * user; a [lyricsProviderSongId] starting "user|" = a provider's lyrics the user chose (LyricsCachePolicy).
 * [cachedAt] is when an automatic row was fetched.
 */
data class LyricsCacheRow(
    val trackProvider: String,
    val trackRawId: String,
    val lyricsProvider: String,
    val lyricsProviderSongId: String?,
    val lrc: String,
    val cachedAt: Long,
)

/** `home_layout`: one account's Home layout document, kept as the exact text it was written or received as. */
data class HomeLayoutRow(
    val sectionsJson: String,
    val updatedAt: Long,
)

interface SyncDomain {
    /** Every local music account. */
    fun profiles(): List<SyncProfile>

    fun trackRatings(profileId: String): List<TrackRatingRow>

    fun trackRating(profileId: String, provider: String, songId: String): TrackRatingRow?

    fun putTrackRating(profileId: String, row: TrackRatingRow): Boolean

    fun deleteTrackRating(profileId: String, provider: String, songId: String): Boolean

    fun albumRatings(profileId: String): List<AlbumRatingRow>

    fun albumRating(profileId: String, provider: String, albumId: String): AlbumRatingRow?

    fun putAlbumRating(profileId: String, row: AlbumRatingRow): Boolean

    fun deleteAlbumRating(profileId: String, provider: String, albumId: String): Boolean

    fun notes(profileId: String): List<SongNoteRow>

    /** By id across every profile (ids are global; see SongNoteSyncAdapter). */
    fun note(id: String): SongNoteRow?

    fun putNote(row: SongNoteRow): Boolean

    fun deleteNote(id: String, profileId: String): Boolean

    fun albumTitles(profileId: String): List<AlbumTitleRow>

    fun albumTitle(profileId: String, provider: String, albumId: String): AlbumTitleRow?

    fun putAlbumTitle(profileId: String, row: AlbumTitleRow): Boolean

    fun deleteAlbumTitle(profileId: String, provider: String, albumId: String): Boolean

    fun memoryCopies(profileId: String): List<MemoryCopyRow>

    fun memoryCopy(profileId: String, provider: String, entityType: String, entityId: String): MemoryCopyRow?

    fun putMemoryCopy(profileId: String, row: MemoryCopyRow): Boolean

    /** The language About / Ask rows are written in (Android `GeminiConfig.normalizeTargetLanguage`). */
    fun aboutLanguage(): String

    fun songAbouts(): List<SongAboutRow>

    fun songAbout(titleKey: String, artistKey: String, albumKey: String, kind: String, entryKey: String): SongAboutRow?

    fun putSongAbout(row: SongAboutRow): Boolean

    /** Every About / Ask row on this device, any language (Android `songAboutEntryCount`). */
    fun songAboutEntryCount(): Int

    /**
     * Sets the Gemini answer language (Android `gemini_config.targetLanguage`; the API key is never touched).
     * [aboutLanguage] reads it back.
     */
    fun putTranslationLanguage(language: String): Boolean

    /** Every Gemini (paid) translation: rows whose model doesn't start "provider:" (Android `paidTranslations`). */
    fun paidLyricsTranslations(): List<LyricsTranslationRow>

    fun lyricsTranslation(
        trackProvider: String,
        trackRawId: String,
        sourceHash: String,
        targetLanguage: String,
        model: String,
    ): LyricsTranslationRow?

    /** Adds [row] unless one with its key exists (Android `insertTranslationIfAbsent`). */
    fun insertLyricsTranslationIfAbsent(row: LyricsTranslationRow): Boolean

    fun deleteLyricsTranslation(
        trackProvider: String,
        trackRawId: String,
        sourceHash: String,
        targetLanguage: String,
        model: String,
    ): Boolean

    /** The lyrics the track loads from this device's cache, or null (Android `lyrics_cache`). */
    fun lyricsCache(trackProvider: String, trackRawId: String): LyricsCacheRow?

    /** Replaces the track's cached lyrics with [row] (Android `writeLyricsCache`). */
    fun writeLyricsCache(row: LyricsCacheRow): Boolean

    /** The account's Home layout; null = never customized (the default). */
    fun homeLayout(profileId: String): HomeLayoutRow?

    fun putHomeLayout(profileId: String, row: HomeLayoutRow): Boolean

    /** Back to the default layout. */
    fun deleteHomeLayout(profileId: String): Boolean
}

/** A [SyncDomain] write that did not reach storage; the record stays unapplied and is retried. */
class SyncDomainWriteException(what: String) : Exception("Couldn't save $what on this device")

/** Throws [SyncDomainWriteException] unless the write was durable. */
internal fun Boolean.orFailWrite(what: String) {
    if (!this) throw SyncDomainWriteException(what)
}
