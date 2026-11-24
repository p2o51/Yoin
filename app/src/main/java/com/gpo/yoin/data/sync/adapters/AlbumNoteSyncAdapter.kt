package com.gpo.yoin.data.sync.adapters

import androidx.room.withTransaction
import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.DomainRow
import com.gpo.yoin.data.sync.FileClass
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncKinds
import kotlinx.serialization.json.JsonObject

/**
 * `album_notes` <-> [SyncKinds.ALBUM_NOTE] (notes revamp: an album holds any number of notes, 2026-11-22).
 * Modelled on [SongNoteSyncAdapter]: key = note id (globally unique), a note id that already lives under
 * another local profile is never re-parented ([SkipReason.OWNED_ELSEWHERE]), deletes are scoped by profile
 * and propagate as tombstones, last writer wins with a live edit beating a concurrent delete. `updatedAt` is a
 * synced field (YoinRepository.updateAlbumNote sets it), so apply writes the payload's value.
 *
 * iOS has the same adapter in :shared (com.gpo.yoin.shared.sync.adapters, notes phase 1b) and HarmonyOS in Dart
 * (harmony/lib/data/sync/adapters/memory_sync_adapters.dart). Engines without it (older builds) carry the
 * records opaquely and republish them, as they do for every unknown kind.
 */
class AlbumNoteSyncAdapter(private val db: YoinDatabase) : SyncAdapter {
    override val kind = SyncKinds.ALBUM_NOTE
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = true
    override val conflictPolicy = ConflictPolicy.LWW_LIVE_WINS

    private val dao get() = db.syncDomainDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "album_note is per-account" }
        return dao.albumNotesForProfile(profileId).map { note ->
            DomainRow(key = note.id, projection = Fields.of(note).projection(), rowTs = note.updatedAt)
        }
    }

    override fun project(payload: JsonObject): JsonObject? = Fields.parse(payload)?.projection()

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "album_note is per-account" }
        return db.withTransaction {
            val existing = dao.albumNoteById(key)
            if (existing != null && existing.profileId != profileId) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.OWNED_ELSEWHERE, null)
            }
            val currentHash = existing?.let { Fields.of(it).projection() }.hashOrNull()
            val fields = Fields.parse(payload)
                ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            dao.writeAlbumNote(fields.toEntity(id = key, profileId = profileId))
            ApplyOutcome.Applied(dao.albumNoteById(key)?.let { Fields.of(it).projection() }.hashOrNull())
        }
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "album_note is per-account" }
        return db.withTransaction {
            val current = dao.albumNoteById(key)?.takeIf { it.profileId == profileId }
                ?: return@withTransaction ApplyOutcome.Applied(null)
            val currentHash = Fields.of(current).projection().hashOrNull()
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            dao.deleteAlbumNote(id = key, profileId = profileId)
            ApplyOutcome.Applied(null)
        }
    }

    private data class Fields(
        val albumId: String,
        val provider: String,
        val content: String,
        val createdAt: Long,
        val updatedAt: Long,
        val albumName: String,
        val artist: String,
    ) {
        fun projection() = JsonObject(
            mapOf(
                "albumId" to jsonString(albumId),
                "provider" to jsonString(provider),
                "content" to jsonString(content),
                "createdAt" to jsonLong(createdAt),
                "updatedAt" to jsonLong(updatedAt),
                "albumName" to jsonString(albumName),
                "artist" to jsonString(artist),
            ),
        )

        fun toEntity(id: String, profileId: String) = AlbumNote(
            id = id,
            profileId = profileId,
            albumId = albumId,
            provider = provider,
            content = content,
            createdAt = createdAt,
            updatedAt = updatedAt,
            albumName = albumName,
            artist = artist,
        )

        companion object {
            fun of(note: AlbumNote) = Fields(
                albumId = note.albumId,
                provider = note.provider,
                content = note.content,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                albumName = note.albumName,
                artist = note.artist,
            )

            fun parse(payload: JsonObject): Fields? = Fields(
                albumId = payload.requiredString("albumId")?.takeIf(String::isNotEmpty) ?: return null,
                provider = payload.requiredString("provider")?.takeIf(String::isNotEmpty) ?: return null,
                content = payload.requiredString("content") ?: return null,
                createdAt = payload.requiredLong("createdAt") ?: return null,
                updatedAt = payload.requiredLong("updatedAt") ?: return null,
                albumName = payload.requiredString("albumName") ?: return null,
                artist = payload.requiredString("artist") ?: return null,
            )
        }
    }
}
