package com.gpo.yoin.data.transfer.memories

import com.gpo.yoin.data.local.AlbumMemoryTitle
import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.MemoryCopyCache
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.TrackAlbumLink
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.sync.adapters.TrackRatingSyncAdapter
import com.gpo.yoin.data.sync.adapters.newAlbumRow
import com.gpo.yoin.shared.portability.BackupTrackAlbumLink
import com.gpo.yoin.shared.portability.memories.MemoryTransferStore
import com.gpo.yoin.shared.portability.memories.TransferAlbumNote
import com.gpo.yoin.shared.portability.memories.TransferAlbumRow
import com.gpo.yoin.shared.portability.memories.TransferAlbumTitle
import com.gpo.yoin.shared.portability.memories.TransferMemoryRow
import com.gpo.yoin.shared.portability.memories.TransferNoteState
import com.gpo.yoin.shared.portability.memories.TransferSongNote
import com.gpo.yoin.shared.portability.memories.TransferTrackRating

/**
 * The target profile's Room tables as :shared's transfer applier writes them (run inside one
 * `db.withTransaction`). Song scores go in with `needsSync = false` (nothing is pushed to the target
 * service); new album rows with the NeoDB flags unset, existing ones keep theirs.
 */
internal class RoomTransferStore(private val db: YoinDatabase) : MemoryTransferStore {
    private val dao get() = db.syncDomainDao()
    private val titles get() = db.albumMemoryTitleDao()

    override suspend fun songNote(id: String): TransferNoteState? =
        dao.noteById(id)?.let { TransferNoteState(it.profileId, it.createdAt, it.updatedAt) }

    override suspend fun putSongNote(note: TransferSongNote) {
        dao.writeNote(
            SongNote(
                id = note.id,
                profileId = note.profileId,
                trackId = note.trackId,
                provider = note.provider,
                content = note.content,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                title = note.title,
                artist = note.artist,
                positionMs = note.positionMs,
            ),
        )
    }

    override suspend fun albumNote(id: String): TransferNoteState? =
        dao.albumNoteById(id)?.let { TransferNoteState(it.profileId, it.createdAt, it.updatedAt) }

    override suspend fun putAlbumNote(note: TransferAlbumNote) {
        dao.writeAlbumNote(
            AlbumNote(
                id = note.id,
                profileId = note.profileId,
                albumId = note.albumId,
                provider = note.provider,
                content = note.content,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                albumName = note.albumName,
                artist = note.artist,
            ),
        )
    }

    override suspend fun trackRating(profileId: String, provider: String, songId: String): TransferTrackRating? =
        dao.trackRating(profileId, songId, provider)?.let { TransferTrackRating(it.rating, it.updatedAt) }

    override suspend fun putTrackRating(profileId: String, provider: String, songId: String, rating: Float, updatedAt: Long) {
        dao.upsertTrackRating(
            LocalRating(
                profileId = profileId,
                songId = songId,
                provider = provider,
                rating = rating,
                serverRating = TrackRatingSyncAdapter.serverRatingFor(rating),
                needsSync = false,
                updatedAt = updatedAt,
            ),
        )
    }

    override suspend fun albumRow(profileId: String, provider: String, albumId: String): TransferAlbumRow? =
        dao.albumRating(profileId, albumId, provider)?.let { TransferAlbumRow(it.rating, it.review, it.reviewUpdatedAt, it.updatedAt) }

    override suspend fun insertAlbumRow(profileId: String, provider: String, albumId: String, row: TransferAlbumRow) {
        dao.insertAlbumRatingIfAbsent(
            newAlbumRow(profileId, albumId, provider, rating = row.rating, review = row.review, updatedAt = row.updatedAt)
                .copy(reviewUpdatedAt = row.reviewUpdatedAt),
        )
    }

    override suspend fun updateAlbumRating(profileId: String, provider: String, albumId: String, rating: Float, updatedAt: Long) {
        dao.updateAlbumRatingValue(profileId, albumId, provider, rating, updatedAt)
    }

    override suspend fun updateAlbumReview(
        profileId: String,
        provider: String,
        albumId: String,
        review: String,
        reviewUpdatedAt: Long,
        updatedAt: Long,
    ) {
        dao.updateAlbumReviewValue(profileId, albumId, provider, review, reviewUpdatedAt, updatedAt)
    }

    override suspend fun albumTitle(profileId: String, provider: String, albumId: String): TransferAlbumTitle? =
        titles.get(profileId, provider, albumId)?.let { TransferAlbumTitle(it.title, it.updatedAt) }

    override suspend fun putAlbumTitle(profileId: String, provider: String, albumId: String, title: TransferAlbumTitle) {
        titles.upsert(AlbumMemoryTitle(profileId, provider, albumId, title.title, title.updatedAt))
    }

    override suspend fun memory(profileId: String, provider: String, entityType: String, entityId: String): TransferMemoryRow? =
        dao.memoryCopy(profileId, provider, entityType, entityId)?.let {
            TransferMemoryRow(it.provider, it.entityType, it.entityId, it.copy, it.promptHash, it.generatedAt, it.title, it.titlePromptHash)
        }

    override suspend fun putMemory(profileId: String, row: TransferMemoryRow) {
        dao.upsertMemoryCopy(
            MemoryCopyCache(
                profileId = profileId,
                provider = row.provider,
                entityType = row.entityType,
                entityId = row.entityId,
                copy = row.copyText,
                promptHash = row.promptHash,
                generatedAt = row.generatedAt,
                title = row.title,
                titlePromptHash = row.titlePromptHash,
            ),
        )
    }

    override suspend fun hasTrackAlbumLink(profileId: String, provider: String, trackId: String): Boolean =
        dao.trackAlbumLink(profileId, provider, trackId) != null

    override suspend fun putTrackAlbumLink(profileId: String, link: BackupTrackAlbumLink) {
        dao.upsertTrackAlbumLink(TrackAlbumLink(profileId, link.provider, link.trackId, link.albumId, link.source, link.updatedAt))
    }
}
