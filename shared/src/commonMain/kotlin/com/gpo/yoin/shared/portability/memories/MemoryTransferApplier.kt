package com.gpo.yoin.shared.portability.memories

import com.gpo.yoin.shared.portability.BackupTrackAlbumLink
import com.gpo.yoin.shared.sync.SyncFormat

/** A song or album note as the target holds it (only what a copy decides on). */
data class TransferNoteState(val profileId: String, val createdAt: Long, val updatedAt: Long)

/** A song note a transfer writes (song_notes; [id] is global). */
data class TransferSongNote(
    val id: String,
    val profileId: String,
    val provider: String,
    val trackId: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String,
    val artist: String,
    val positionMs: Long?,
)

/** An album note a transfer writes (album_notes; [id] is global). */
data class TransferAlbumNote(
    val id: String,
    val profileId: String,
    val provider: String,
    val albumId: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val albumName: String,
    val artist: String,
)

/** The target's song score: null [rating] = a cleared score (the row stays so the clear syncs). */
data class TransferTrackRating(val rating: Float?, val updatedAt: Long)

/** The target's album row: score (0 = none) and review share it. */
data class TransferAlbumRow(val rating: Float, val review: String?, val reviewUpdatedAt: Long?, val updatedAt: Long)

data class TransferAlbumTitle(val title: String, val updatedAt: Long)

/** memory_copy_cache: the AI mood line ([copyText]) and AI title of one Memory. */
data class TransferMemoryRow(
    val provider: String,
    val entityType: String,
    val entityId: String,
    val copyText: String,
    val promptHash: String,
    val generatedAt: Long,
    val title: String?,
    val titlePromptHash: String?,
)

/**
 * The target profile's Memories tables as a transfer reads and writes them. Android implements it on Room
 * inside one transaction; iOS on its stores ([SyncDomainTransferStore]). Writes never push anything to a
 * service: album rows keep their NeoDB flags unset, song scores aren't sent to the target service.
 */
interface MemoryTransferStore {
    suspend fun songNote(id: String): TransferNoteState?

    suspend fun putSongNote(note: TransferSongNote)

    suspend fun albumNote(id: String): TransferNoteState?

    suspend fun putAlbumNote(note: TransferAlbumNote)

    suspend fun trackRating(profileId: String, provider: String, songId: String): TransferTrackRating?

    suspend fun putTrackRating(profileId: String, provider: String, songId: String, rating: Float, updatedAt: Long)

    suspend fun albumRow(profileId: String, provider: String, albumId: String): TransferAlbumRow?

    /** A new album row (none exists yet). */
    suspend fun insertAlbumRow(profileId: String, provider: String, albumId: String, row: TransferAlbumRow)

    /** Only the score and updatedAt of an existing row. */
    suspend fun updateAlbumRating(profileId: String, provider: String, albumId: String, rating: Float, updatedAt: Long)

    /** Only the review, its date and updatedAt of an existing row. */
    suspend fun updateAlbumReview(
        profileId: String,
        provider: String,
        albumId: String,
        review: String,
        reviewUpdatedAt: Long,
        updatedAt: Long,
    )

    suspend fun albumTitle(profileId: String, provider: String, albumId: String): TransferAlbumTitle?

    suspend fun putAlbumTitle(profileId: String, provider: String, albumId: String, title: TransferAlbumTitle)

    suspend fun memory(profileId: String, provider: String, entityType: String, entityId: String): TransferMemoryRow?

    suspend fun putMemory(profileId: String, row: TransferMemoryRow)

    suspend fun hasTrackAlbumLink(profileId: String, provider: String, trackId: String): Boolean

    suspend fun putTrackAlbumLink(profileId: String, link: BackupTrackAlbumLink)
}

/** What [MemoryTransferApplier.apply] did, and the ledger entries to store for the next run. */
data class TransferOutcome(val result: TransferResult, val ledgerEntries: Map<String, String>)

/**
 * Copies Memories (song notes, track scores, album scores and reviews, album notes, the listener's album
 * titles, the AI titles and copy, track→album links) from one account into a local profile, once matching
 * decided where each song and album goes. Copy, never move. Rules (docs/cloud-sync.md「转移 Memories」):
 *
 *  - Ids are copied as they are when both sides share an id space ([TransferRules.compatible]); otherwise
 *    the song's / album's [TrackPick] / [AlbumPick] (Matched or Picked) says where it goes.
 *  - Notes get [MigrationIds] ids, so a rerun finds its own copies and never touches the original; mentions
 *    in notes and reviews follow their song / album ([TransferRules.remapMentions]).
 *  - Conflicts in the target: notes are all kept; scores and titles: the later edit wins; reviews: both kept,
 *    the source's under "— 来自 <source account>"; the target's own AI title wins.
 *  - The ledger entries returned say what was copied (skipped next time), matched, picked or unmatched.
 *
 * Run [apply] inside the target database's transaction; store [TransferOutcome.ledgerEntries] after it.
 */
class MemoryTransferApplier(private val store: MemoryTransferStore) {
    suspend fun apply(
        source: TransferAccount,
        target: TransferAccount,
        items: MemoryItems,
        tracks: List<TrackPick>,
        albums: List<AlbumPick>,
        ledger: Map<String, String>,
        clock: () -> Long,
    ): TransferOutcome {
        val profileId = requireNotNull(target.localProfileId) { "Memories are copied into a local profile" }
        val pending = TransferRules.pendingItems(items, ledger)
        val trackMatches = tracks.associateBy { it.source }
        val albumMatches = albums.associateBy { it.source }

        fun trackTarget(ref: SourceRef): TargetRef? =
            if (TransferRules.compatible(source, target, ref)) {
                TransferRules.targetOf(ref.provider, ref.id)
            } else {
                trackMatches[ref]?.takeIf { it.status == MatchStatus.Matched || it.status == MatchStatus.Picked }?.chosen?.ref
            }

        fun albumTarget(ref: SourceRef): TargetRef? =
            if (TransferRules.compatible(source, target, ref)) {
                TransferRules.targetOf(ref.provider, ref.id)
            } else {
                albumMatches[ref]?.takeIf { it.status == MatchStatus.Matched || it.status == MatchStatus.Picked }?.chosen?.ref
            }

        fun remapMentions(text: String): String = TransferRules.remapMentions(text, ::trackTarget, ::albumTarget)

        fun memoryEntityTarget(provider: String, entityType: String, entityId: String): TargetRef? {
            val ref = SourceRef(provider, entityId)
            return when {
                entityType == TransferRules.ENTITY_ALBUM -> albumTarget(ref)
                TransferRules.compatible(source, target, ref) -> TransferRules.targetOf(provider, entityId)
                else -> null
            }
        }

        val copied = Counter()
        val updated = Counter()
        val kept = Counter()
        val unmatched = Counter()
        var joined = 0
        val entries = LinkedHashMap<String, String>()

        for (note in pending.songNotes) {
            val ledgerKey = TransferRules.songNoteKey(note)
            val track = trackTarget(note.track)
            if (track == null) {
                unmatched.songNotes++
                entries[ledgerKey] = TransferLedger.UNMATCHED
                continue
            }
            val id = MigrationIds.derive(source.scope, note.id, target.scope)
            val matched = trackMatches[note.track]?.chosen
            val row = TransferSongNote(
                id = id,
                profileId = profileId,
                provider = track.provider,
                trackId = track.id,
                content = remapMentions(note.content),
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                title = matched?.title?.takeIf(String::isNotBlank) ?: note.title,
                artist = matched?.artist?.takeIf(String::isNotBlank) ?: note.artist,
                positionMs = note.positionMs,
            )
            val existing = store.songNote(id)
            when {
                existing == null -> {
                    store.putSongNote(row)
                    copied.songNotes++
                }
                existing.profileId != profileId || existing.updatedAt >= note.updatedAt -> kept.songNotes++
                else -> {
                    store.putSongNote(row.copy(createdAt = existing.createdAt))
                    updated.songNotes++
                }
            }
            entries[ledgerKey] = TransferLedger.COPIED + id
        }

        for (rating in pending.trackRatings) {
            val ledgerKey = TransferRules.trackRatingKey(rating)
            val track = trackTarget(rating.track)
            if (track == null) {
                unmatched.trackRatings++
                entries[ledgerKey] = TransferLedger.UNMATCHED
                continue
            }
            val existing = store.trackRating(profileId, track.provider, track.id)
            when {
                existing == null -> {
                    store.putTrackRating(profileId, track.provider, track.id, rating.rating, rating.updatedAt)
                    copied.trackRatings++
                }
                existing.rating == rating.rating -> kept.trackRatings++
                existing.rating == null || rating.updatedAt > existing.updatedAt -> {
                    store.putTrackRating(profileId, track.provider, track.id, rating.rating, rating.updatedAt)
                    updated.trackRatings++
                }
                else -> kept.trackRatings++
            }
            entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(track.provider, track.id)
        }

        for (rating in pending.albumRatings) {
            val ledgerKey = TransferRules.albumRatingKey(rating)
            val album = albumTarget(rating.album)
            if (album == null) {
                unmatched.albumRatings++
                entries[ledgerKey] = TransferLedger.UNMATCHED
                continue
            }
            val existing = store.albumRow(profileId, album.provider, album.id)
            when {
                existing == null -> {
                    store.insertAlbumRow(profileId, album.provider, album.id, TransferAlbumRow(rating.rating, null, null, rating.updatedAt))
                    copied.albumRatings++
                }
                existing.rating == rating.rating -> kept.albumRatings++
                existing.rating == 0f -> {
                    // The row exists for its review only: the score itself is new here.
                    store.updateAlbumRating(profileId, album.provider, album.id, rating.rating, maxOf(existing.updatedAt, rating.updatedAt))
                    copied.albumRatings++
                }
                rating.updatedAt > existing.updatedAt -> {
                    // Rating only: the NeoDB flags stay as they are (never set: nothing is pushed for a copy).
                    store.updateAlbumRating(profileId, album.provider, album.id, rating.rating, maxOf(existing.updatedAt, rating.updatedAt))
                    updated.albumRatings++
                }
                else -> kept.albumRatings++
            }
            entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(album.provider, album.id)
        }

        for (review in pending.albumReviews) {
            val ledgerKey = TransferRules.albumReviewKey(review)
            val album = albumTarget(review.album)
            if (album == null) {
                unmatched.albumReviews++
                entries[ledgerKey] = TransferLedger.UNMATCHED
                continue
            }
            val existing = store.albumRow(profileId, album.provider, album.id)
            val text = remapMentions(review.review.trim())
            val writtenAt = review.reviewUpdatedAt ?: review.updatedAt
            val existingReview = existing?.review
            when {
                existing == null -> {
                    store.insertAlbumRow(profileId, album.provider, album.id, TransferAlbumRow(0f, text, writtenAt, review.updatedAt))
                    copied.albumReviews++
                }
                existingReview.isNullOrBlank() -> {
                    // The row exists for its score only: the review itself is new here.
                    store.updateAlbumReview(
                        profileId,
                        album.provider,
                        album.id,
                        text,
                        writtenAt,
                        maxOf(existing.updatedAt, review.updatedAt),
                    )
                    copied.albumReviews++
                }
                existingReview.contains(text) -> kept.albumReviews++
                else -> {
                    store.updateAlbumReview(
                        profileId,
                        album.provider,
                        album.id,
                        TransferRules.joinReviews(existingReview, text, source.displayName),
                        maxOf(existing.reviewUpdatedAt ?: existing.updatedAt, writtenAt),
                        maxOf(existing.updatedAt, review.updatedAt),
                    )
                    updated.albumReviews++
                    joined++
                }
            }
            entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(album.provider, album.id)
        }

        for (note in pending.albumNotes) {
            val ledgerKey = TransferRules.albumNoteKey(note)
            val album = albumTarget(note.album)
            if (album == null) {
                unmatched.albumNotes++
                entries[ledgerKey] = TransferLedger.UNMATCHED
                continue
            }
            val id = MigrationIds.derive(source.scope, note.id, target.scope)
            val matched = albumMatches[note.album]?.chosen
            val row = TransferAlbumNote(
                id = id,
                profileId = profileId,
                provider = album.provider,
                albumId = album.id,
                content = remapMentions(note.content),
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                albumName = matched?.name?.takeIf(String::isNotBlank) ?: note.albumName,
                artist = matched?.artist?.takeIf(String::isNotBlank) ?: note.artist,
            )
            val existing = store.albumNote(id)
            when {
                existing == null -> {
                    store.putAlbumNote(row)
                    copied.albumNotes++
                }
                existing.profileId != profileId || existing.updatedAt >= note.updatedAt -> kept.albumNotes++
                else -> {
                    store.putAlbumNote(row.copy(createdAt = existing.createdAt))
                    updated.albumNotes++
                }
            }
            entries[ledgerKey] = TransferLedger.COPIED + id
        }

        for (title in pending.albumTitles) {
            val ledgerKey = TransferRules.albumTitleKey(title)
            val album = albumTarget(title.album)
            if (album == null) {
                unmatched.albumTitles++
                entries[ledgerKey] = TransferLedger.UNMATCHED
                continue
            }
            val existing = store.albumTitle(profileId, album.provider, album.id)
            when {
                existing == null -> {
                    store.putAlbumTitle(profileId, album.provider, album.id, TransferAlbumTitle(title.title, title.updatedAt))
                    copied.albumTitles++
                }
                existing.title == title.title || existing.updatedAt >= title.updatedAt -> kept.albumTitles++
                else -> {
                    store.putAlbumTitle(profileId, album.provider, album.id, TransferAlbumTitle(title.title, title.updatedAt))
                    updated.albumTitles++
                }
            }
            entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(album.provider, album.id)
        }

        for (title in pending.memoryTitles) {
            val ledgerKey = TransferRules.memoryTitleKey(title)
            val entity = memoryEntityTarget(title.provider, title.entityType, title.entityId)
            if (entity == null) {
                unmatched.memoryTitles++
                entries[ledgerKey] = TransferLedger.UNMATCHED
                continue
            }
            val existing = store.memory(profileId, entity.provider, title.entityType, entity.id)
            when {
                existing == null -> {
                    store.putMemory(
                        profileId,
                        TransferMemoryRow(entity.provider, title.entityType, entity.id, "", "", 0L, title.title, title.titlePromptHash),
                    )
                    copied.memoryTitles++
                }
                // The target's own AI title wins: it was made from the target's own Memories.
                !existing.title.isNullOrBlank() -> kept.memoryTitles++
                else -> {
                    store.putMemory(profileId, existing.copy(title = title.title, titlePromptHash = title.titlePromptHash))
                    copied.memoryTitles++
                }
            }
            entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(entity.provider, title.entityType, entity.id)
        }

        for (copy in pending.memoryCopies) {
            val ledgerKey = TransferRules.memoryCopyKey(copy)
            val entity = memoryEntityTarget(copy.provider, copy.entityType, copy.entityId)
            if (entity == null) {
                unmatched.memoryCopies++
                entries[ledgerKey] = TransferLedger.UNMATCHED
                continue
            }
            val existing = store.memory(profileId, entity.provider, copy.entityType, entity.id)
            when {
                existing == null -> {
                    store.putMemory(
                        profileId,
                        TransferMemoryRow(entity.provider, copy.entityType, entity.id, copy.copy, copy.promptHash, copy.generatedAt, null, null),
                    )
                    copied.memoryCopies++
                }
                existing.copyText.isBlank() -> {
                    store.putMemory(profileId, existing.copy(copyText = copy.copy, promptHash = copy.promptHash, generatedAt = copy.generatedAt))
                    copied.memoryCopies++
                }
                copy.generatedAt > existing.generatedAt -> {
                    store.putMemory(profileId, existing.copy(copyText = copy.copy, promptHash = copy.promptHash, generatedAt = copy.generatedAt))
                    updated.memoryCopies++
                }
                else -> kept.memoryCopies++
            }
            entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(entity.provider, copy.entityType, entity.id)
        }

        // Track → album links: the source's (mapped), then what matching learned; a link already there stays.
        val linked = HashSet<TargetRef>()
        for (link in pending.trackAlbumLinks) {
            val ledgerKey = TransferRules.linkKey(link)
            val track = trackTarget(link.track)
            // The album side is mapped on its own: a catalog song can sit on a library-only album id.
            val album = trackMatches[link.track]?.chosen?.album
                ?: albumTarget(SourceRef(link.track.provider, link.albumId))
            if (track == null || album == null || album.provider != track.provider) {
                unmatched.trackAlbumLinks++
                entries[ledgerKey] = TransferLedger.UNMATCHED
                continue
            }
            val linkSource = if (track.id == link.track.id) link.source else BackupTrackAlbumLink.SOURCE_IMPORT
            if (writeLink(profileId, track, album.id, linkSource, link.updatedAt)) {
                copied.trackAlbumLinks++
            } else {
                kept.trackAlbumLinks++
            }
            linked += track
            entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(track.provider, track.id)
        }
        for (match in tracks) {
            val chosen = match.chosen ?: continue
            if (match.status != MatchStatus.Matched && match.status != MatchStatus.Picked) continue
            val album = chosen.album ?: continue
            if (chosen.ref in linked || album.provider != chosen.provider) continue
            if (writeLink(profileId, chosen.ref, album.id, BackupTrackAlbumLink.SOURCE_IMPORT, clock())) {
                copied.trackAlbumLinks++
            }
        }

        for (match in tracks) entries[TransferRules.trackKey(match.source)] = TransferRules.ledgerValue(match.status, match.chosen?.ref)
        for (match in albums) entries[TransferRules.albumKey(match.source)] = TransferRules.ledgerValue(match.status, match.chosen?.ref)

        val result = TransferResult(
            copied = copied.counts(),
            updated = updated.counts(),
            kept = kept.counts(),
            reviewsJoined = joined,
            unmatched = unmatched.counts(),
            skippedPreviously = TransferRules.countItems(items) - TransferRules.countItems(pending),
        )
        return TransferOutcome(result, entries)
    }

    private suspend fun writeLink(profileId: String, track: TargetRef, albumId: String, source: String, updatedAt: Long): Boolean {
        if (store.hasTrackAlbumLink(profileId, track.provider, track.id)) return false
        store.putTrackAlbumLink(profileId, BackupTrackAlbumLink(track.provider, track.id, albumId, source, updatedAt))
        return true
    }

    private class Counter {
        var songNotes = 0
        var trackRatings = 0
        var albumRatings = 0
        var albumReviews = 0
        var albumNotes = 0
        var albumTitles = 0
        var memoryTitles = 0
        var memoryCopies = 0
        var trackAlbumLinks = 0

        fun counts() = TransferCounts(
            songNotes,
            trackRatings,
            albumRatings,
            albumReviews,
            albumNotes,
            albumTitles,
            memoryTitles,
            memoryCopies,
            trackAlbumLinks,
        )
    }
}
