package com.gpo.yoin.shared.sync.testing

import com.gpo.yoin.shared.sync.adapters.AlbumRatingRow
import com.gpo.yoin.shared.sync.adapters.AlbumTitleRow
import com.gpo.yoin.shared.sync.adapters.MemoryCopyRow
import com.gpo.yoin.shared.sync.adapters.SongAboutRow
import com.gpo.yoin.shared.sync.adapters.SongNoteRow
import com.gpo.yoin.shared.sync.adapters.SyncDomain
import com.gpo.yoin.shared.sync.adapters.TrackRatingRow
import com.gpo.yoin.shared.sync.identity.SyncProfile

/** An in-memory [SyncDomain] (what the Swift stores implement on iOS). */
class FakeSyncDomain(var language: String = "English") : SyncDomain {
    val profileList = mutableListOf<SyncProfile>()
    val trackRatings = LinkedHashMap<Triple<String, String, String>, TrackRatingRow>()
    val albumRatings = LinkedHashMap<Triple<String, String, String>, AlbumRatingRow>()
    val notes = LinkedHashMap<String, SongNoteRow>()
    val albumTitles = LinkedHashMap<Triple<String, String, String>, AlbumTitleRow>()
    val memories = LinkedHashMap<List<String>, MemoryCopyRow>()
    val abouts = LinkedHashMap<List<String>, SongAboutRow>()

    override fun profiles() = profileList.toList()

    override fun trackRatings(profileId: String) = trackRatings.filterKeys { it.first == profileId }.values.toList()

    override fun trackRating(profileId: String, provider: String, songId: String) =
        trackRatings[Triple(profileId, provider, songId)]

    override fun putTrackRating(profileId: String, row: TrackRatingRow) {
        trackRatings[Triple(profileId, row.provider, row.songId)] = row
    }

    override fun deleteTrackRating(profileId: String, provider: String, songId: String) {
        trackRatings.remove(Triple(profileId, provider, songId))
    }

    override fun albumRatings(profileId: String) = albumRatings.filterKeys { it.first == profileId }.values.toList()

    override fun albumRating(profileId: String, provider: String, albumId: String) =
        albumRatings[Triple(profileId, provider, albumId)]

    override fun putAlbumRating(profileId: String, row: AlbumRatingRow) {
        albumRatings[Triple(profileId, row.provider, row.albumId)] = row
    }

    override fun deleteAlbumRating(profileId: String, provider: String, albumId: String) {
        albumRatings.remove(Triple(profileId, provider, albumId))
    }

    override fun notes(profileId: String) = notes.values.filter { it.profileId == profileId }

    override fun note(id: String) = notes[id]

    override fun putNote(row: SongNoteRow) {
        notes[row.id] = row
    }

    override fun deleteNote(id: String, profileId: String) {
        if (notes[id]?.profileId == profileId) notes.remove(id)
    }

    override fun albumTitles(profileId: String) = albumTitles.filterKeys { it.first == profileId }.values.toList()

    override fun albumTitle(profileId: String, provider: String, albumId: String) =
        albumTitles[Triple(profileId, provider, albumId)]

    override fun putAlbumTitle(profileId: String, row: AlbumTitleRow) {
        albumTitles[Triple(profileId, row.provider, row.albumId)] = row
    }

    override fun deleteAlbumTitle(profileId: String, provider: String, albumId: String) {
        albumTitles.remove(Triple(profileId, provider, albumId))
    }

    override fun memoryCopies(profileId: String) = memories.filterKeys { it[0] == profileId }.values.toList()

    override fun memoryCopy(profileId: String, provider: String, entityType: String, entityId: String) =
        memories[listOf(profileId, provider, entityType, entityId)]

    override fun putMemoryCopy(profileId: String, row: MemoryCopyRow) {
        memories[listOf(profileId, row.provider, row.entityType, row.entityId)] = row
    }

    override fun aboutLanguage() = language

    override fun songAbouts() = abouts.values.toList()

    override fun songAbout(titleKey: String, artistKey: String, albumKey: String, kind: String, entryKey: String) =
        abouts[listOf(titleKey, artistKey, albumKey, kind, entryKey)]

    override fun putSongAbout(row: SongAboutRow) {
        abouts[listOf(row.titleKey, row.artistKey, row.albumKey, row.kind, row.entryKey)] = row
    }
}
