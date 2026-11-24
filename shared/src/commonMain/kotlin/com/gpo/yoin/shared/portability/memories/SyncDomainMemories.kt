package com.gpo.yoin.shared.portability.memories

import com.gpo.yoin.shared.portability.BackupTrackAlbumLink
import com.gpo.yoin.shared.portability.YoinTrackAlbumLinkStore
import com.gpo.yoin.shared.sync.SyncMetaEntity
import com.gpo.yoin.shared.sync.SyncStore
import com.gpo.yoin.shared.sync.adapters.AlbumNoteRow
import com.gpo.yoin.shared.sync.adapters.AlbumRatingRow
import com.gpo.yoin.shared.sync.adapters.AlbumTitleRow
import com.gpo.yoin.shared.sync.adapters.MemoryCopyRow
import com.gpo.yoin.shared.sync.adapters.SongNoteRow
import com.gpo.yoin.shared.sync.adapters.SyncDomain
import com.gpo.yoin.shared.sync.adapters.TrackRatingRow
import com.gpo.yoin.shared.sync.adapters.orFailWrite

/*
 * The transfer core over the shared SyncDomain (the iOS stores) and SyncStore (the iOS replica): what
 * Android's DomainMemoryItems / RoomTransferStore / SyncMetaTransferLedger do on Room.
 */

/**
 * A local profile's Memories from [domain] (+ [links]). Unlike Android's Room source there are no play
 * facts here (the iOS stores don't expose plays), so matching works from note titles and the catalog.
 */
fun MemoryItems.Companion.fromDomain(domain: SyncDomain, links: YoinTrackAlbumLinkStore, profileId: String): MemoryItems {
    val albumRows = domain.albumRatings(profileId)
    val memoryRows = domain.memoryCopies(profileId)
    val notes = domain.albumNotes(profileId)
    return MemoryItems(
        songNotes = domain.notes(profileId).map {
            SourceNote(
                id = it.id,
                track = SourceRef(it.provider, TransferRules.storedRawId(it.provider, it.trackId)),
                content = it.content,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                title = it.title,
                artist = it.artist,
                positionMs = it.positionMs,
            )
        },
        trackRatings = domain.trackRatings(profileId).mapNotNull { row ->
            row.rating?.let { SourceTrackRating(SourceRef(row.provider, row.songId), it.toFloat(), row.updatedAt) }
        },
        albumRatings = albumRows.filter { it.rating > 0.0 }
            .map { SourceAlbumRating(SourceRef(it.provider, it.albumId), it.rating.toFloat(), it.updatedAt) },
        albumReviews = albumRows.filter { !it.review.isNullOrBlank() }
            .map { SourceAlbumReview(SourceRef(it.provider, it.albumId), it.review!!, it.updatedAt, it.reviewUpdatedAt) },
        albumNotes = notes.map {
            SourceAlbumNote(
                id = it.id,
                album = SourceRef(it.provider, TransferRules.storedRawId(it.provider, it.albumId)),
                content = it.content,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                albumName = it.albumName,
                artist = it.artist,
            )
        },
        albumTitles = domain.albumTitles(profileId).map { SourceAlbumTitle(SourceRef(it.provider, it.albumId), it.title, it.updatedAt) },
        memoryTitles = memoryRows.filter { !it.title.isNullOrBlank() }.map {
            SourceMemoryTitle(it.provider, it.entityType, it.entityId, it.title!!, it.titlePromptHash, it.generatedAt)
        },
        memoryCopies = memoryRows.filter { it.copyText.isNotBlank() }.map {
            SourceMemoryCopy(it.provider, it.entityType, it.entityId, it.copyText, it.promptHash, it.generatedAt)
        },
        trackAlbumLinks = links.links(profileId).filter { it.albumId.isNotBlank() }
            .map { SourceTrackLink(SourceRef(it.provider, it.trackId), it.albumId, it.source, it.updatedAt) },
        albumFacts = notes.filter { it.albumName.isNotBlank() }.associate {
            SourceRef(it.provider, TransferRules.storedRawId(it.provider, it.albumId)) to SourceAlbumFacts(it.albumName, it.artist)
        },
    )
}

/** [MemoryTransferStore] over [SyncDomain] (+ [links]); every write must be durable or it throws. */
class SyncDomainTransferStore(
    private val domain: SyncDomain,
    private val links: YoinTrackAlbumLinkStore,
) : MemoryTransferStore {
    override suspend fun songNote(id: String): TransferNoteState? =
        domain.note(id)?.let { TransferNoteState(it.profileId, it.createdAt, it.updatedAt) }

    override suspend fun putSongNote(note: TransferSongNote) {
        domain.putNote(
            SongNoteRow(note.id, note.profileId, note.trackId, note.provider, note.content, note.createdAt, note.updatedAt, note.title, note.artist, note.positionMs),
        ).orFailWrite("a note")
    }

    override suspend fun albumNote(id: String): TransferNoteState? =
        domain.albumNote(id)?.let { TransferNoteState(it.profileId, it.createdAt, it.updatedAt) }

    override suspend fun putAlbumNote(note: TransferAlbumNote) {
        domain.putAlbumNote(
            AlbumNoteRow(note.id, note.profileId, note.albumId, note.provider, note.content, note.createdAt, note.updatedAt, note.albumName, note.artist),
        ).orFailWrite("an album note")
    }

    override suspend fun trackRating(profileId: String, provider: String, songId: String): TransferTrackRating? =
        domain.trackRating(profileId, provider, songId)?.let { TransferTrackRating(it.rating?.toFloat(), it.updatedAt) }

    override suspend fun putTrackRating(profileId: String, provider: String, songId: String, rating: Float, updatedAt: Long) {
        domain.putTrackRating(profileId, TrackRatingRow(provider, songId, rating.toDouble(), updatedAt)).orFailWrite("a rating")
    }

    override suspend fun albumRow(profileId: String, provider: String, albumId: String): TransferAlbumRow? =
        domain.albumRating(profileId, provider, albumId)?.let { TransferAlbumRow(it.rating.toFloat(), it.review, it.reviewUpdatedAt, it.updatedAt) }

    override suspend fun insertAlbumRow(profileId: String, provider: String, albumId: String, row: TransferAlbumRow) {
        if (domain.albumRating(profileId, provider, albumId) != null) return
        domain.putAlbumRating(profileId, AlbumRatingRow(provider, albumId, row.rating.toDouble(), row.review, row.reviewUpdatedAt, row.updatedAt))
            .orFailWrite("an album rating")
    }

    override suspend fun updateAlbumRating(profileId: String, provider: String, albumId: String, rating: Float, updatedAt: Long) {
        val row = domain.albumRating(profileId, provider, albumId) ?: return
        domain.putAlbumRating(profileId, row.copy(rating = rating.toDouble(), updatedAt = updatedAt)).orFailWrite("an album rating")
    }

    override suspend fun updateAlbumReview(
        profileId: String,
        provider: String,
        albumId: String,
        review: String,
        reviewUpdatedAt: Long,
        updatedAt: Long,
    ) {
        val row = domain.albumRating(profileId, provider, albumId) ?: return
        domain.putAlbumRating(profileId, row.copy(review = review, reviewUpdatedAt = reviewUpdatedAt, updatedAt = updatedAt))
            .orFailWrite("an album review")
    }

    override suspend fun albumTitle(profileId: String, provider: String, albumId: String): TransferAlbumTitle? =
        domain.albumTitle(profileId, provider, albumId)?.let { TransferAlbumTitle(it.title, it.updatedAt) }

    override suspend fun putAlbumTitle(profileId: String, provider: String, albumId: String, title: TransferAlbumTitle) {
        domain.putAlbumTitle(profileId, AlbumTitleRow(provider, albumId, title.title, title.updatedAt)).orFailWrite("an album title")
    }

    override suspend fun memory(profileId: String, provider: String, entityType: String, entityId: String): TransferMemoryRow? =
        domain.memoryCopy(profileId, provider, entityType, entityId)?.let {
            TransferMemoryRow(it.provider, it.entityType, it.entityId, it.copyText, it.promptHash, it.generatedAt, it.title, it.titlePromptHash)
        }

    override suspend fun putMemory(profileId: String, row: TransferMemoryRow) {
        domain.putMemoryCopy(
            profileId,
            MemoryCopyRow(row.provider, row.entityType, row.entityId, row.copyText, row.promptHash, row.generatedAt, row.title, row.titlePromptHash),
        ).orFailWrite("a Memory")
    }

    override suspend fun hasTrackAlbumLink(profileId: String, provider: String, trackId: String): Boolean =
        links.link(profileId, provider, trackId) != null

    override suspend fun putTrackAlbumLink(profileId: String, link: BackupTrackAlbumLink) {
        links.putLink(profileId, link).orFailWrite("a track album link")
    }
}

/** The transfer ledger in the shared replica's meta table: the same keys Android keeps in sync_meta. */
class SyncStoreTransferLedger(private val store: SyncStore) : TransferLedger {
    override suspend fun read(srcScope: String, dstProfileId: String): Map<String, String> {
        val prefix = TransferLedger.metaPrefix(srcScope, dstProfileId)
        return store.metaWithPrefix(prefix)
            .filter { it.key.startsWith(prefix) }
            .associate { it.key.removePrefix(prefix) to it.value }
    }

    override suspend fun write(srcScope: String, dstProfileId: String, entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val prefix = TransferLedger.metaPrefix(srcScope, dstProfileId)
        store.transaction {
            for ((key, value) in entries) store.putMeta(SyncMetaEntity(prefix + key, value))
        }
    }
}
