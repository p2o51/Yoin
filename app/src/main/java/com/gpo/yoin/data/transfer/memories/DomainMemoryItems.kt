package com.gpo.yoin.data.transfer.memories

import com.gpo.yoin.data.local.TrackAlbumLink
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.sync.WireRecord
import com.gpo.yoin.data.sync.backup.toShared
import com.gpo.yoin.shared.portability.memories.MemoryItems
import com.gpo.yoin.shared.portability.memories.SourceAlbumFacts
import com.gpo.yoin.shared.portability.memories.SourceAlbumNote
import com.gpo.yoin.shared.portability.memories.SourceAlbumRating
import com.gpo.yoin.shared.portability.memories.SourceAlbumReview
import com.gpo.yoin.shared.portability.memories.SourceAlbumTitle
import com.gpo.yoin.shared.portability.memories.SourceMemoryCopy
import com.gpo.yoin.shared.portability.memories.SourceMemoryTitle
import com.gpo.yoin.shared.portability.memories.SourceNote
import com.gpo.yoin.shared.portability.memories.SourceRef
import com.gpo.yoin.shared.portability.memories.SourceTrackFacts
import com.gpo.yoin.shared.portability.memories.SourceTrackLink
import com.gpo.yoin.shared.portability.memories.SourceTrackRating

/*
 * The Android sources of a transfer's [MemoryItems] (:shared): a local profile's Room tables, or the app's
 * own sync records (converted for [MemoryItems.fromRecords]).
 */

/** A local profile's Memories, straight from the domain tables. */
suspend fun MemoryItems.Companion.fromProfile(db: YoinDatabase, profileId: String): MemoryItems {
    val dao = db.syncDomainDao()
    val albumRows = dao.albumRatingsForProfile(profileId)
    val memoryRows = dao.memoryCopiesForProfile(profileId)
    val notes = dao.albumNotesForProfile(profileId)
    val songNotes = dao.notesForProfile(profileId)
    val trackRatings = dao.trackRatingsForProfile(profileId)
    val plays = dao.playedTracksForProfile(profileId)
    val links = dao.trackAlbumLinksForProfile(profileId)
        .filter { it.albumId.isNotBlank() }
        .map { SourceTrackLink(SourceRef(it.provider, it.trackId), it.albumId, it.source, it.updatedAt) }
    // A noted or scored song whose album only Yoin's plays know (no track_album_links row): that
    // association is a link too, or Memories on the target couldn't place the song.
    val linked = links.mapTo(HashSet()) { it.track }
    val memorable = HashSet<SourceRef>().apply {
        songNotes.forEach { add(SourceRef(it.provider, MediaId.storedRawId(it.provider, it.trackId))) }
        trackRatings.filter { it.rating != null }.forEach { add(SourceRef(it.provider, it.songId)) }
    }
    val playedLinks = plays
        .filter { it.albumId.isNotBlank() }
        .map { SourceRef(it.provider, it.songId) to it }
        .filter { (ref, _) -> ref in memorable && ref !in linked }
        .map { (ref, row) ->
            SourceTrackLink(ref, MediaId.storedRawId(row.provider, row.albumId), TrackAlbumLink.SOURCE_PLAY, row.playedAt)
        }
    return MemoryItems(
        songNotes = songNotes.map {
            SourceNote(
                id = it.id,
                track = SourceRef(it.provider, MediaId.storedRawId(it.provider, it.trackId)),
                content = it.content,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                title = it.title,
                artist = it.artist,
                positionMs = it.positionMs,
            )
        },
        trackRatings = trackRatings.mapNotNull { row ->
            row.rating?.let { SourceTrackRating(SourceRef(row.provider, row.songId), it, row.updatedAt) }
        },
        albumRatings = albumRows.filter { it.rating > 0f }
            .map { SourceAlbumRating(SourceRef(it.provider, it.albumId), it.rating, it.updatedAt) },
        albumReviews = albumRows.filter { !it.review.isNullOrBlank() }
            .map { SourceAlbumReview(SourceRef(it.provider, it.albumId), it.review!!, it.updatedAt, it.reviewUpdatedAt) },
        albumNotes = notes.map {
            SourceAlbumNote(
                id = it.id,
                album = SourceRef(it.provider, MediaId.storedRawId(it.provider, it.albumId)),
                content = it.content,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                albumName = it.albumName,
                artist = it.artist,
            )
        },
        albumTitles = db.albumMemoryTitleDao().getAllForProfile(profileId)
            .map { SourceAlbumTitle(SourceRef(it.provider, it.albumId), it.title, it.updatedAt) },
        memoryTitles = memoryRows.filter { !it.title.isNullOrBlank() }.map {
            SourceMemoryTitle(it.provider, it.entityType, it.entityId, it.title!!, it.titlePromptHash, it.generatedAt)
        },
        memoryCopies = memoryRows.filter { it.copy.isNotBlank() }.map {
            SourceMemoryCopy(it.provider, it.entityType, it.entityId, it.copy, it.promptHash, it.generatedAt)
        },
        trackAlbumLinks = links + playedLinks,
        trackFacts = plays.associate { row ->
            SourceRef(row.provider, row.songId) to SourceTrackFacts(
                title = row.title,
                artist = row.artist,
                album = row.album.takeIf(String::isNotBlank),
                albumId = row.albumId.takeIf(String::isNotBlank),
                durationSec = (row.durationMs / 1000).toInt().takeIf { it > 0 },
            )
        },
        albumFacts = dao.playedAlbumsForProfile(profileId).associate {
            SourceRef(it.provider, MediaId.storedRawId(it.provider, it.albumId)) to SourceAlbumFacts(it.albumName, it.artist)
        } + notes.filter { it.albumName.isNotBlank() }.associate {
            SourceRef(it.provider, MediaId.storedRawId(it.provider, it.albumId)) to SourceAlbumFacts(it.albumName, it.artist)
        },
    )
}

/** One scope's Memories from the app's sync records (the replica). Tombstones are ignored. */
fun MemoryItems.Companion.fromRecords(records: List<WireRecord>, scope: String): MemoryItems =
    fromRecords(records.map { it.toShared() }, scope)
