package com.gpo.yoin.data.sync.adapters

import androidx.room.withTransaction
import com.gpo.yoin.data.backup.PlaylistBackupCodec
import com.gpo.yoin.data.local.PlaylistBackup
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
 * `playlist_backups` <-> [SyncKinds.PLAYLIST_BACKUP] (docs/playlist-transfer.md
 * §7): one record per backup, keyed by its id (a UUID).
 *
 * Global, not per account: a backup is restored into any profile, and Apple
 * Music accounts have no sync id to scope one by. Artifacts, not state: a
 * backup is large (every track's metadata) and rarely changes after it is
 * made — only its restore history grows.
 *
 * `content` and `restores` travel as the exact JSON text the row holds, so a
 * newer device's extra fields survive a round trip through an older one and
 * the projection hashes alike everywhere; apply checks both still decode.
 * `updatedAt` is a synced field and is written back as received (see
 * SongNoteSyncAdapter for why).
 *
 * Deleting a backup is a user action, so deletes propagate as tombstones.
 * Conflicts: [ConflictPolicy.LWW_LIVE_WINS]. Two devices changing the same
 * backup can only both be adding restore history (the snapshot never changes),
 * and the later one wins; a delete that raced a restore elsewhere loses to it,
 * because a backup can't be made again once its playlist is gone — the user
 * can delete it again, but couldn't get it back.
 */
class PlaylistBackupSyncAdapter(private val db: YoinDatabase) : SyncAdapter {
    override val kind = SyncKinds.PLAYLIST_BACKUP
    override val kindVersion = 1
    override val fileClass = FileClass.ARTIFACTS
    override val perAccount = false
    override val propagatesDeletes = true
    override val conflictPolicy = ConflictPolicy.LWW_LIVE_WINS

    private val dao get() = db.playlistBackupDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> = dao.getAll().map { row ->
        DomainRow(key = row.id, projection = projection(row), rowTs = row.updatedAt)
    }

    override fun project(payload: JsonObject): JsonObject? = rowOf(KEY_UNKNOWN, payload)?.let(::projection)

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome = db.withTransaction {
        val currentHash = dao.get(key)?.let(::projection).hashOrNull()
        val row = rowOf(key, payload)
            ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        if (currentHash != expectedLocalHash) {
            return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        }
        dao.upsert(row)
        ApplyOutcome.Applied(dao.get(key)?.let(::projection).hashOrNull())
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome =
        db.withTransaction {
            val current = dao.get(key) ?: return@withTransaction ApplyOutcome.Applied(null)
            val currentHash = projection(current).hashOrNull()
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            dao.delete(key)
            ApplyOutcome.Applied(null)
        }

    private fun projection(row: PlaylistBackup) = JsonObject(
        mapOf(
            NAME to jsonString(row.name),
            SOURCE_PROVIDER to jsonString(row.sourceProvider),
            SOURCE_PROFILE_LABEL to jsonString(row.sourceProfileLabel),
            SOURCE_PLAYLIST_ID to jsonString(row.sourcePlaylistId),
            BACKED_UP_AT to jsonLong(row.backedUpAt),
            UPDATED_AT to jsonLong(row.updatedAt),
            CONTENT to jsonString(row.contentJson),
            RESTORES to jsonString(row.restoresJson),
        ),
    )

    /** The row [payload] describes, or null when a field is missing or its JSON doesn't decode. */
    private fun rowOf(key: String, payload: JsonObject): PlaylistBackup? {
        val contentJson = payload.requiredString(CONTENT) ?: return null
        val restoresJson = payload.requiredString(RESTORES) ?: return null
        val content = runCatching { PlaylistBackupCodec.decodeContent(contentJson) }.getOrNull() ?: return null
        runCatching { PlaylistBackupCodec.decodeRestores(restoresJson) }.getOrNull() ?: return null
        return PlaylistBackup(
            id = key,
            name = payload.requiredString(NAME) ?: return null,
            sourceProvider = payload.requiredString(SOURCE_PROVIDER)?.takeIf(String::isNotEmpty) ?: return null,
            sourceProfileLabel = payload.requiredString(SOURCE_PROFILE_LABEL) ?: return null,
            sourcePlaylistId = payload.requiredString(SOURCE_PLAYLIST_ID) ?: return null,
            backedUpAt = payload.requiredLong(BACKED_UP_AT) ?: return null,
            trackCount = content.tracks.size,
            unexportedCount = content.unexported.size,
            contentJson = contentJson,
            restoresJson = restoresJson,
            updatedAt = payload.requiredLong(UPDATED_AT) ?: return null,
        )
    }

    private companion object {
        const val KEY_UNKNOWN = ""
        const val NAME = "name"
        const val SOURCE_PROVIDER = "sourceProvider"
        const val SOURCE_PROFILE_LABEL = "sourceProfileLabel"
        const val SOURCE_PLAYLIST_ID = "sourcePlaylistId"
        const val BACKED_UP_AT = "backedUpAt"
        const val UPDATED_AT = "updatedAt"
        const val CONTENT = "content"
        const val RESTORES = "restores"
    }
}
