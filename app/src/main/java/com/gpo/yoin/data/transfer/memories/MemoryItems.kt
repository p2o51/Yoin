package com.gpo.yoin.data.transfer.memories

import com.gpo.yoin.data.local.MemoryCopyCache
import com.gpo.yoin.data.local.TrackAlbumLink
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.WireRecord
import com.gpo.yoin.data.sync.adapters.Field
import com.gpo.yoin.data.sync.adapters.TrackRatingSyncAdapter
import com.gpo.yoin.data.sync.adapters.optionalLong
import com.gpo.yoin.data.sync.adapters.optionalString
import com.gpo.yoin.data.sync.adapters.rating
import com.gpo.yoin.data.sync.adapters.requiredLong
import com.gpo.yoin.data.sync.adapters.requiredString
import com.gpo.yoin.data.sync.backup.BackupFields
import com.gpo.yoin.data.sync.backup.BackupKinds

data class SourceNote(
    val id: String,
    val track: SourceRef,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String,
    val artist: String,
    val positionMs: Long?,
)

data class SourceTrackRating(val track: SourceRef, val rating: Float, val updatedAt: Long)

data class SourceAlbumRating(val album: SourceRef, val rating: Float, val updatedAt: Long)

data class SourceAlbumReview(val album: SourceRef, val review: String, val updatedAt: Long, val reviewUpdatedAt: Long?)

data class SourceAlbumNote(
    val id: String,
    val album: SourceRef,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val albumName: String,
    val artist: String,
)

data class SourceAlbumTitle(val album: SourceRef, val title: String, val updatedAt: Long)

/** memory_copy_cache's AI title; [entityType] "album" keys an album, anything else is copied as is. */
data class SourceMemoryTitle(
    val provider: String,
    val entityType: String,
    val entityId: String,
    val title: String,
    val titlePromptHash: String?,
    val updatedAt: Long,
)

data class SourceMemoryCopy(
    val provider: String,
    val entityType: String,
    val entityId: String,
    val copy: String,
    val promptHash: String,
    val generatedAt: Long,
)

data class SourceTrackLink(val track: SourceRef, val albumId: String, val source: String, val updatedAt: Long)

/** A song the source knows something about (Yoin's plays): what matching searches with. */
data class SourceTrackFacts(val title: String, val artist: String, val album: String?, val albumId: String?, val durationSec: Int?)

/** Every Memories item of one source account, normalized from either the domain or sync records. */
data class MemoryItems(
    val songNotes: List<SourceNote> = emptyList(),
    val trackRatings: List<SourceTrackRating> = emptyList(),
    val albumRatings: List<SourceAlbumRating> = emptyList(),
    val albumReviews: List<SourceAlbumReview> = emptyList(),
    val albumNotes: List<SourceAlbumNote> = emptyList(),
    val albumTitles: List<SourceAlbumTitle> = emptyList(),
    val memoryTitles: List<SourceMemoryTitle> = emptyList(),
    val memoryCopies: List<SourceMemoryCopy> = emptyList(),
    val trackAlbumLinks: List<SourceTrackLink> = emptyList(),
    /** Local facts about source songs (title, album, duration), keyed by song. */
    val trackFacts: Map<SourceRef, SourceTrackFacts> = emptyMap(),
    /** Local facts about source albums: name and artist. */
    val albumFacts: Map<SourceRef, Pair<String, String>> = emptyMap(),
) {
    /** Every song with a note, a score or a link. */
    val tracks: Set<SourceRef>
        get() = LinkedHashSet<SourceRef>().apply {
            songNotes.forEach { add(it.track) }
            trackRatings.forEach { add(it.track) }
            trackAlbumLinks.forEach { add(it.track) }
        }

    /** Every album with album-keyed data. */
    val albums: Set<SourceRef>
        get() = LinkedHashSet<SourceRef>().apply {
            albumRatings.forEach { add(it.album) }
            albumReviews.forEach { add(it.album) }
            albumNotes.forEach { add(it.album) }
            albumTitles.forEach { add(it.album) }
            memoryTitles.filter { it.entityType == MemoryCopyCache.ENTITY_ALBUM }.forEach { add(SourceRef(it.provider, it.entityId)) }
            memoryCopies.filter { it.entityType == MemoryCopyCache.ENTITY_ALBUM }.forEach { add(SourceRef(it.provider, it.entityId)) }
        }

    companion object {
        /** A local profile's Memories, straight from the domain tables. */
        suspend fun fromProfile(db: YoinDatabase, profileId: String): MemoryItems {
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
                    SourceRef(it.provider, MediaId.storedRawId(it.provider, it.albumId)) to (it.albumName to it.artist)
                } + notes.filter { it.albumName.isNotBlank() }.associate {
                    SourceRef(it.provider, MediaId.storedRawId(it.provider, it.albumId)) to (it.albumName to it.artist)
                },
            )
        }

        /** One scope's Memories from sync records (the replica, or a backup snapshot). Tombstones are ignored. */
        fun fromRecords(records: List<WireRecord>, scope: String): MemoryItems {
            val notes = ArrayList<SourceNote>()
            val ratings = ArrayList<SourceTrackRating>()
            val albumRatings = ArrayList<SourceAlbumRating>()
            val reviews = ArrayList<SourceAlbumReview>()
            val albumNotes = ArrayList<SourceAlbumNote>()
            val titles = ArrayList<SourceAlbumTitle>()
            val memoryTitles = ArrayList<SourceMemoryTitle>()
            val memoryCopies = ArrayList<SourceMemoryCopy>()
            val links = ArrayList<SourceTrackLink>()
            for (record in records) {
                if (record.scope != scope || record.deleted) continue
                val p = record.payload ?: continue
                when (record.kind) {
                    SyncKinds.SONG_NOTE -> {
                        val provider = p.requiredString("provider") ?: continue
                        notes += SourceNote(
                            id = record.key,
                            track = SourceRef(provider, p.requiredString("trackId")?.let { MediaId.storedRawId(provider, it) } ?: continue),
                            content = p.requiredString("content") ?: continue,
                            createdAt = p.requiredLong("createdAt") ?: record.ts,
                            updatedAt = p.requiredLong("updatedAt") ?: record.ts,
                            title = p.requiredString("title").orEmpty(),
                            artist = p.requiredString("artist").orEmpty(),
                            positionMs = (p.optionalLong("positionMs") as? Field.Ok)?.value,
                        )
                    }
                    SyncKinds.TRACK_RATING -> {
                        val wire = p.rating("rating") ?: continue
                        val rating = TrackRatingSyncAdapter.ratingFrom(wire, p) ?: continue
                        ratings += SourceTrackRating(
                            SourceRef(p.requiredString("provider") ?: continue, p.requiredString("songId") ?: continue),
                            rating,
                            record.ts,
                        )
                    }
                    SyncKinds.ALBUM_RATING -> {
                        val rating = p.rating("rating")?.takeIf { it > 0f } ?: continue
                        albumRatings += SourceAlbumRating(albumRef(record) ?: continue, rating, record.ts)
                    }
                    SyncKinds.ALBUM_REVIEW -> {
                        val review = (p.optionalString("review") as? Field.Ok)?.value?.takeIf(String::isNotBlank) ?: continue
                        reviews += SourceAlbumReview(albumRef(record) ?: continue, review, record.ts, record.ts)
                    }
                    SyncKinds.ALBUM_TITLE -> {
                        val title = p.requiredString("title")?.trim()?.takeIf(String::isNotEmpty) ?: continue
                        titles += SourceAlbumTitle(albumRef(record) ?: continue, title, record.ts)
                    }
                    SyncKinds.MEMORY_TITLE -> {
                        val parts = record.key.split(SyncFormat.KEY_SEPARATOR).takeIf { it.size == 3 } ?: continue
                        val title = p.requiredString("title")?.takeIf(String::isNotBlank) ?: continue
                        memoryTitles += SourceMemoryTitle(
                            parts[0],
                            parts[1],
                            parts[2],
                            title,
                            (p.optionalString("titlePromptHash") as? Field.Ok)?.value,
                            record.ts,
                        )
                    }
                    SyncKinds.MEMORY_COPY -> {
                        val parts = record.key.split(SyncFormat.KEY_SEPARATOR).takeIf { it.size == 3 } ?: continue
                        memoryCopies += SourceMemoryCopy(
                            parts[0],
                            parts[1],
                            parts[2],
                            p.requiredString("copy")?.takeIf(String::isNotBlank) ?: continue,
                            p.requiredString("promptHash") ?: continue,
                            p.requiredLong("generatedAt") ?: record.ts,
                        )
                    }
                    BackupKinds.ALBUM_NOTE -> {
                        val provider = p.requiredString(BackupFields.PROVIDER) ?: continue
                        albumNotes += SourceAlbumNote(
                            id = record.key,
                            album = SourceRef(provider, p.requiredString(BackupFields.ALBUM_ID) ?: continue),
                            content = p.requiredString(BackupFields.CONTENT) ?: continue,
                            createdAt = p.requiredLong(BackupFields.CREATED_AT) ?: record.ts,
                            updatedAt = p.requiredLong(BackupFields.UPDATED_AT) ?: record.ts,
                            albumName = p.requiredString(BackupFields.ALBUM_NAME).orEmpty(),
                            artist = p.requiredString(BackupFields.ARTIST).orEmpty(),
                        )
                    }
                    BackupKinds.TRACK_ALBUM_LINK -> {
                        val provider = p.requiredString(BackupFields.PROVIDER) ?: continue
                        links += SourceTrackLink(
                            SourceRef(provider, p.requiredString(BackupFields.TRACK_ID) ?: continue),
                            p.requiredString(BackupFields.ALBUM_ID)?.takeIf(String::isNotBlank) ?: continue,
                            p.requiredString(BackupFields.SOURCE) ?: "import",
                            record.ts,
                        )
                    }
                }
            }
            return MemoryItems(
                songNotes = notes,
                trackRatings = ratings,
                albumRatings = albumRatings,
                albumReviews = reviews,
                albumNotes = albumNotes,
                albumTitles = titles,
                memoryTitles = memoryTitles,
                memoryCopies = memoryCopies,
                trackAlbumLinks = links,
                albumFacts = albumNotes.filter { it.albumName.isNotBlank() }.associate { it.album to (it.albumName to it.artist) },
            )
        }

        private fun albumRef(record: WireRecord): SourceRef? =
            record.key.split(SyncFormat.KEY_SEPARATOR).takeIf { it.size == 2 }?.let { SourceRef(it[0], it[1]) }
    }
}
