package com.gpo.yoin.shared.sync.testing

import com.gpo.yoin.shared.sync.adapters.AlbumRatingRow
import com.gpo.yoin.shared.sync.adapters.AlbumTitleRow
import com.gpo.yoin.shared.sync.adapters.LyricsCacheRow
import com.gpo.yoin.shared.sync.adapters.LyricsTranslationRow
import com.gpo.yoin.shared.sync.adapters.MemoryCopyRow
import com.gpo.yoin.shared.sync.adapters.SongAboutRow
import com.gpo.yoin.shared.sync.adapters.SongNoteRow
import com.gpo.yoin.shared.sync.adapters.SyncDomain
import com.gpo.yoin.shared.sync.adapters.TrackRatingRow
import com.gpo.yoin.shared.sync.identity.SyncProfile

/** An in-memory [SyncDomain] (what the Swift stores implement on iOS). */
class FakeSyncDomain(var language: String = "English") : SyncDomain {
    /** true: every write fails to persist (and changes nothing), as a full or unwritable disk would. */
    var failWrites = false
    val profileList = mutableListOf<SyncProfile>()
    val trackRatings = LinkedHashMap<Triple<String, String, String>, TrackRatingRow>()
    val albumRatings = LinkedHashMap<Triple<String, String, String>, AlbumRatingRow>()
    val notes = LinkedHashMap<String, SongNoteRow>()
    val albumTitles = LinkedHashMap<Triple<String, String, String>, AlbumTitleRow>()
    val memories = LinkedHashMap<List<String>, MemoryCopyRow>()
    val abouts = LinkedHashMap<List<String>, SongAboutRow>()
    val translations = LinkedHashMap<List<String>, LyricsTranslationRow>()
    val lyrics = LinkedHashMap<Pair<String, String>, LyricsCacheRow>()

    override fun profiles() = profileList.toList()

    override fun trackRatings(profileId: String) = trackRatings.filterKeys { it.first == profileId }.values.toList()

    override fun trackRating(profileId: String, provider: String, songId: String) =
        trackRatings[Triple(profileId, provider, songId)]

    override fun putTrackRating(profileId: String, row: TrackRatingRow): Boolean {
        if (failWrites) return false
        trackRatings[Triple(profileId, row.provider, row.songId)] = row
        return true
    }

    override fun deleteTrackRating(profileId: String, provider: String, songId: String): Boolean {
        if (failWrites) return false
        trackRatings.remove(Triple(profileId, provider, songId))
        return true
    }

    override fun albumRatings(profileId: String) = albumRatings.filterKeys { it.first == profileId }.values.toList()

    override fun albumRating(profileId: String, provider: String, albumId: String) =
        albumRatings[Triple(profileId, provider, albumId)]

    override fun putAlbumRating(profileId: String, row: AlbumRatingRow): Boolean {
        if (failWrites) return false
        albumRatings[Triple(profileId, row.provider, row.albumId)] = row
        return true
    }

    override fun deleteAlbumRating(profileId: String, provider: String, albumId: String): Boolean {
        if (failWrites) return false
        albumRatings.remove(Triple(profileId, provider, albumId))
        return true
    }

    override fun notes(profileId: String) = notes.values.filter { it.profileId == profileId }

    override fun note(id: String) = notes[id]

    override fun putNote(row: SongNoteRow): Boolean {
        if (failWrites) return false
        notes[row.id] = row
        return true
    }

    override fun deleteNote(id: String, profileId: String): Boolean {
        if (failWrites) return false
        if (notes[id]?.profileId == profileId) notes.remove(id)
        return true
    }

    override fun albumTitles(profileId: String) = albumTitles.filterKeys { it.first == profileId }.values.toList()

    override fun albumTitle(profileId: String, provider: String, albumId: String) =
        albumTitles[Triple(profileId, provider, albumId)]

    override fun putAlbumTitle(profileId: String, row: AlbumTitleRow): Boolean {
        if (failWrites) return false
        albumTitles[Triple(profileId, row.provider, row.albumId)] = row
        return true
    }

    override fun deleteAlbumTitle(profileId: String, provider: String, albumId: String): Boolean {
        if (failWrites) return false
        albumTitles.remove(Triple(profileId, provider, albumId))
        return true
    }

    override fun memoryCopies(profileId: String) = memories.filterKeys { it[0] == profileId }.values.toList()

    override fun memoryCopy(profileId: String, provider: String, entityType: String, entityId: String) =
        memories[listOf(profileId, provider, entityType, entityId)]

    override fun putMemoryCopy(profileId: String, row: MemoryCopyRow): Boolean {
        if (failWrites) return false
        memories[listOf(profileId, row.provider, row.entityType, row.entityId)] = row
        return true
    }

    override fun aboutLanguage() = language

    override fun songAbouts() = abouts.values.toList()

    override fun songAbout(titleKey: String, artistKey: String, albumKey: String, kind: String, entryKey: String) =
        abouts[listOf(titleKey, artistKey, albumKey, kind, entryKey)]

    override fun putSongAbout(row: SongAboutRow): Boolean {
        if (failWrites) return false
        abouts[listOf(row.titleKey, row.artistKey, row.albumKey, row.kind, row.entryKey)] = row
        return true
    }

    override fun songAboutEntryCount() = abouts.size

    override fun putTranslationLanguage(language: String): Boolean {
        if (failWrites) return false
        this.language = language
        return true
    }

    private fun LyricsTranslationRow.id() = listOf(trackProvider, trackRawId, sourceHash, targetLanguage, model)

    override fun paidLyricsTranslations() = translations.values.filterNot { it.model.startsWith("provider:") }

    override fun lyricsTranslation(
        trackProvider: String,
        trackRawId: String,
        sourceHash: String,
        targetLanguage: String,
        model: String,
    ) = translations[listOf(trackProvider, trackRawId, sourceHash, targetLanguage, model)]

    override fun insertLyricsTranslationIfAbsent(row: LyricsTranslationRow): Boolean {
        if (failWrites) return false
        translations.getOrPut(row.id()) { row }
        return true
    }

    override fun deleteLyricsTranslation(
        trackProvider: String,
        trackRawId: String,
        sourceHash: String,
        targetLanguage: String,
        model: String,
    ): Boolean {
        if (failWrites) return false
        translations.remove(listOf(trackProvider, trackRawId, sourceHash, targetLanguage, model))
        return true
    }

    override fun lyricsCache(trackProvider: String, trackRawId: String) = lyrics[trackProvider to trackRawId]

    override fun writeLyricsCache(row: LyricsCacheRow): Boolean {
        if (failWrites) return false
        lyrics[row.trackProvider to row.trackRawId] = row
        return true
    }
}
