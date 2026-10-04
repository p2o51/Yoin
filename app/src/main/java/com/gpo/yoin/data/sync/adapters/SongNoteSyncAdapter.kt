package com.gpo.yoin.data.sync.adapters

import androidx.room.withTransaction
import com.gpo.yoin.data.local.SongNote
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
 * `song_notes` <-> [SyncKinds.SONG_NOTE]. Key = note id (globally unique), so
 * a note id that already lives under another local profile is never
 * re-parented ([SkipReason.OWNED_ELSEWHERE]) and deletes are always scoped by
 * profile.
 *
 * `updatedAt` is itself a synced field here, so apply writes the payload's
 * value rather than the version ts: that keeps the read-back projection
 * identical to the payload's (crash repair compares the two). They differ
 * only under clock skew: a create or an edit (YoinRepository.updateNote sets
 * updatedAt = now) is captured at max(now, previous ts + 1).
 */
class SongNoteSyncAdapter(private val db: YoinDatabase) : SyncAdapter {
    override val kind = SyncKinds.SONG_NOTE
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = true
    override val conflictPolicy = ConflictPolicy.LWW_LIVE_WINS

    private val dao get() = db.syncDomainDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "song_note is per-account" }
        return dao.notesForProfile(profileId).map { note ->
            DomainRow(key = note.id, projection = NoteFields.of(note).projection(), rowTs = note.updatedAt)
        }
    }

    override fun project(payload: JsonObject): JsonObject? = NoteFields.parse(payload)?.projection()

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "song_note is per-account" }
        return db.withTransaction {
            val existing = dao.noteById(key)
            if (existing != null && existing.profileId != profileId) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.OWNED_ELSEWHERE, null)
            }
            val currentHash = existing?.let { NoteFields.of(it).projection() }.hashOrNull()
            val fields = NoteFields.parse(payload)
                ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            dao.writeNote(fields.toEntity(id = key, profileId = profileId))
            ApplyOutcome.Applied(dao.noteById(key)?.let { NoteFields.of(it).projection() }.hashOrNull())
        }
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "song_note is per-account" }
        return db.withTransaction {
            val current = dao.noteById(key)?.takeIf { it.profileId == profileId }
                ?: return@withTransaction ApplyOutcome.Applied(null)
            val currentHash = NoteFields.of(current).projection().hashOrNull()
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            dao.deleteNote(id = key, profileId = profileId)
            ApplyOutcome.Applied(null)
        }
    }

    private data class NoteFields(
        val trackId: String,
        val provider: String,
        val content: String,
        val createdAt: Long,
        val updatedAt: Long,
        val title: String,
        val artist: String,
        val positionMs: Long?,
    ) {
        fun projection() = JsonObject(
            mapOf(
                "trackId" to jsonString(trackId),
                "provider" to jsonString(provider),
                "content" to jsonString(content),
                "createdAt" to jsonLong(createdAt),
                "updatedAt" to jsonLong(updatedAt),
                "title" to jsonString(title),
                "artist" to jsonString(artist),
                "positionMs" to jsonLong(positionMs),
            ),
        )

        fun toEntity(id: String, profileId: String) = SongNote(
            id = id,
            profileId = profileId,
            trackId = trackId,
            provider = provider,
            content = content,
            createdAt = createdAt,
            updatedAt = updatedAt,
            title = title,
            artist = artist,
            positionMs = positionMs,
        )

        companion object {
            fun of(note: SongNote) = NoteFields(
                trackId = note.trackId,
                provider = note.provider,
                content = note.content,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                title = note.title,
                artist = note.artist,
                positionMs = note.positionMs,
            )

            fun parse(payload: JsonObject): NoteFields? {
                val positionMs = payload.optionalLong("positionMs").orElse { return null }
                return NoteFields(
                    trackId = payload.requiredString("trackId")?.takeIf(String::isNotEmpty) ?: return null,
                    provider = payload.requiredString("provider")?.takeIf(String::isNotEmpty) ?: return null,
                    content = payload.requiredString("content") ?: return null,
                    createdAt = payload.requiredLong("createdAt") ?: return null,
                    updatedAt = payload.requiredLong("updatedAt") ?: return null,
                    title = payload.requiredString("title") ?: return null,
                    artist = payload.requiredString("artist") ?: return null,
                    positionMs = positionMs,
                )
            }
        }
    }
}
