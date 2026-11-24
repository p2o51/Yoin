package com.gpo.yoin.shared.portability.memories

import com.gpo.yoin.shared.portability.BackupTrackAlbumLink

/** An in-memory [MemoryTransferStore] for the shared transfer tests. */
class FakeTransferStore : MemoryTransferStore {
    val songNotes = LinkedHashMap<String, TransferSongNote>()
    val albumNotes = LinkedHashMap<String, TransferAlbumNote>()
    val trackRatings = LinkedHashMap<List<String>, TransferTrackRating>()
    val albums = LinkedHashMap<List<String>, TransferAlbumRow>()
    val titles = LinkedHashMap<List<String>, TransferAlbumTitle>()
    val memories = LinkedHashMap<List<String>, TransferMemoryRow>()
    val links = LinkedHashMap<List<String>, BackupTrackAlbumLink>()

    override suspend fun songNote(id: String) = songNotes[id]?.let { TransferNoteState(it.profileId, it.createdAt, it.updatedAt) }

    override suspend fun putSongNote(note: TransferSongNote) {
        songNotes[note.id] = note
    }

    override suspend fun albumNote(id: String) = albumNotes[id]?.let { TransferNoteState(it.profileId, it.createdAt, it.updatedAt) }

    override suspend fun putAlbumNote(note: TransferAlbumNote) {
        albumNotes[note.id] = note
    }

    override suspend fun trackRating(profileId: String, provider: String, songId: String) = trackRatings[listOf(profileId, provider, songId)]

    override suspend fun putTrackRating(profileId: String, provider: String, songId: String, rating: Float, updatedAt: Long) {
        trackRatings[listOf(profileId, provider, songId)] = TransferTrackRating(rating, updatedAt)
    }

    override suspend fun albumRow(profileId: String, provider: String, albumId: String) = albums[listOf(profileId, provider, albumId)]

    override suspend fun insertAlbumRow(profileId: String, provider: String, albumId: String, row: TransferAlbumRow) {
        albums.getOrPut(listOf(profileId, provider, albumId)) { row }
    }

    override suspend fun updateAlbumRating(profileId: String, provider: String, albumId: String, rating: Float, updatedAt: Long) {
        val key = listOf(profileId, provider, albumId)
        albums[key] = albums.getValue(key).copy(rating = rating, updatedAt = updatedAt)
    }

    override suspend fun updateAlbumReview(
        profileId: String,
        provider: String,
        albumId: String,
        review: String,
        reviewUpdatedAt: Long,
        updatedAt: Long,
    ) {
        val key = listOf(profileId, provider, albumId)
        albums[key] = albums.getValue(key).copy(review = review, reviewUpdatedAt = reviewUpdatedAt, updatedAt = updatedAt)
    }

    override suspend fun albumTitle(profileId: String, provider: String, albumId: String) = titles[listOf(profileId, provider, albumId)]

    override suspend fun putAlbumTitle(profileId: String, provider: String, albumId: String, title: TransferAlbumTitle) {
        titles[listOf(profileId, provider, albumId)] = title
    }

    override suspend fun memory(profileId: String, provider: String, entityType: String, entityId: String) =
        memories[listOf(profileId, provider, entityType, entityId)]

    override suspend fun putMemory(profileId: String, row: TransferMemoryRow) {
        memories[listOf(profileId, row.provider, row.entityType, row.entityId)] = row
    }

    override suspend fun hasTrackAlbumLink(profileId: String, provider: String, trackId: String) =
        listOf(profileId, provider, trackId) in links

    override suspend fun putTrackAlbumLink(profileId: String, link: BackupTrackAlbumLink) {
        links[listOf(profileId, link.provider, link.trackId)] = link
    }
}
